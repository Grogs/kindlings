package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.MacroCommons
import hearth.fp.data.NonEmptyList
import hearth.fp.effect.*
import hearth.fp.syntax.*
import hearth.std.*
import hearth.kindlings.reactivemongobsonderivation.{KindlingsBsonDocumentHandler, TypeNaming}
import reactivemongo.api.bson.BSONDocument
import scala.util.Try

/** Derivation rule for sealed traits and Scala 3 enums. */
trait BsonEnumDerivation {
  this: BsonDocumentHandlerMacrosImpl & MacroCommons & StdExtensions & AnnotationSupport =>

  object HandleAsEnumRule extends DerivationRule("handle as enum") {
    def apply[A: DerivationCtx]: MIO[Rule.Applicability[Expr[KindlingsBsonDocumentHandler[A]]]] =
      Log.info(s"Attempting to handle ${Type[A].prettyPrint} as an enum") >> {
        Enum.parse[A].toEither match {
          case Right(enumm) =>
            deriveEnumHandler[A](enumm).map(Rule.matched)
          case Left(reason) =>
            MIO.pure(Rule.yielded(reason))
        }
      }

    private def deriveEnumHandler[A: DerivationCtx](
        enumm: Enum[A]
    ): MIO[Expr[KindlingsBsonDocumentHandler[A]]] = {
      @scala.annotation.nowarn("msg=is never used")
      implicit val StringT: Type[String] = Types.String
      implicit val BsonDocumentT: Type[BSONDocument] = Types.BsonDocument
      @scala.annotation.nowarn("msg=is never used")
      implicit val TryAT: Type[scala.util.Try[A]] = Types.TryCtor[A]
      implicit val TryBsonDocumentT: Type[scala.util.Try[BSONDocument]] = Types.TryCtor[BSONDocument]

      val childrenList: List[(String, ??<:[A])] = enumm.exhaustiveChildren match {
        case Some(ec) => ec.toList
        case None     => enumm.directChildren.toList
      }

      NonEmptyList.fromList(childrenList) match {
        case None =>
          val err = BsonDocumentHandlerDerivationError.NoChildrenInSealedTrait(Type[A].prettyPrint)
          Log.error(err.message) >> MIO.fail(err)

        case Some(_) =>
          // Extract discriminator field name at compile time if possible, otherwise use default
          val discriminatorFieldExpr: Expr[String] =
            ctx.evaluatedConfig.flatMap(_.discriminatorFieldName) match {
              case Some(discriminator) =>
                // semiEval succeeded and discriminator is set, use compile-time constant
                Expr(discriminator)
              case None =>
                // semiEval failed or discriminator not set, splice config at runtime
                // Capture config in a local val to ensure proper initialization
                Expr.quote {
                  val config = Expr.splice(ctx.config)
                  config.discriminatorFieldName.getOrElse("className")
                }
            }

          // Build a function that produces the discriminator Expr[String] for a child from its simple/full names.
          // For compile-time-known SimpleName/FullName we emit a constant; otherwise we call config.typeNaming at runtime.
          val discriminatorFor: (String, String) => Expr[String] =
            ctx.evaluatedConfig.map(_.typeNaming) match {
              case Some(TypeNaming.SimpleName) => (simpleName, _) => Expr(simpleName)
              case Some(TypeNaming.FullName)   => (_, fullName) => Expr(fullName)
              case _                           =>
                (simpleName, fullName) =>
                  Expr.quote {
                    Expr
                      .splice(ctx.config)
                      .typeNaming(
                        Expr.splice(Expr(simpleName)),
                        Expr.splice(Expr(fullName))
                      )
                  }
            }

          val childrenWithDiscriminators: List[(Expr[String], ??<:[A])] = childrenList.map { case (_, child) =>
            import child.Underlying as ChildType
            val simpleName = Type[ChildType].shortName
            val fullName = fullNameOf[ChildType]
            (discriminatorFor(simpleName, fullName), child)
          }
          val childrenDiscriminatorNel = NonEmptyList(childrenWithDiscriminators.head, childrenWithDiscriminators.tail)

          val knownNames: String = childrenList
            .map { case (_, child) =>
              import child.Underlying as ChildType
              Type[ChildType].shortName
            }
            .mkString(", ")
          val knownNamesExpr = Expr(knownNames)

          // Inline writing has no reader call site. Avoid deriving child read dispatchers (and their handler helpers)
          // altogether; only the write body is needed.
          if (ctx.writeOnly) {
            ctx
              .cacheWriteBody[A] { valueExpr =>
                enumm
                  .parMatchOn[MIO, scala.util.Try[BSONDocument]](valueExpr) { matched =>
                    import matched.{value as enumCaseValue, Underlying as ChildType}
                    val discriminatorNameExpr = discriminatorFor(Type[ChildType].shortName, fullNameOf[ChildType])
                    Expr.singletonOf[ChildType] match {
                      case Some(_) =>
                        MIO.pure(Expr.quote {
                          scala.util.Success(
                            BSONDocument(Expr.splice(discriminatorFieldExpr) -> Expr.splice(discriminatorNameExpr))
                          )
                        })
                      case None =>
                        resolveBsonWriter[ChildType](ctx.nest[ChildType]).map { writer =>
                          Expr.quote {
                            Expr.splice(writer).writeTry(Expr.splice(enumCaseValue)).flatMap {
                              case document: BSONDocument =>
                                scala.util.Success(
                                  document ++ BSONDocument(
                                    Expr.splice(discriminatorFieldExpr) -> Expr.splice(discriminatorNameExpr)
                                  )
                                )
                              case value =>
                                scala.util.Failure(new IllegalArgumentException(s"Expected BSONDocument, got $value"))
                            }
                          }
                        }
                    }
                  }
                  .flatMap {
                    case Some(result) => MIO.pure(result)
                    case None         =>
                      MIO.fail(BsonDocumentHandlerDerivationError.NoChildrenInSealedTrait(Type[A].prettyPrint))
                  }
              }
              .map { writeBody =>
                Expr.quote {
                  hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories
                    .handlerInstance[A](
                      readFn =
                        (_: BSONDocument) => scala.util.Failure(new UnsupportedOperationException("read body omitted")),
                      writeFn = Expr.splice(writeBody)
                    )
                }
              }
          } else
            // Derive typed read-dispatch functions for each child
            childrenDiscriminatorNel
              .parTraverse { case (discriminatorNameExpr, child) =>
                import child.Underlying as ChildType
                deriveChildReadDispatch[A, ChildType](discriminatorNameExpr, discriminatorFieldExpr)
              }
              .flatMap { readDispatchersNel =>
                val readDispatchers = readDispatchersNel.toList

                // Build read lambda - fold dispatch chain (reverse so earlier children match first)
                val readLambdaIO =
                  ctx.cacheReadBody[A] { docExpr =>
                    val errorExpr = Expr.quote {
                      scala.util
                        .Failure(
                          new IllegalArgumentException(
                            "Unknown type discriminator: " +
                              Expr.splice(docExpr).get(Expr.splice(discriminatorFieldExpr)).getOrElse("<none>") +
                              ". Expected one of: " + Expr.splice(knownNamesExpr)
                          )
                        ): scala.util.Try[A]
                    }
                    MIO.pure(
                      readDispatchers.foldRight(errorExpr) { case (dispatcher, elseExpr) =>
                        dispatcher(docExpr, elseExpr)
                      }
                    )
                  }

                // Build write lambda via Enum.parMatchOn
                val writeLambdaIO =
                  ctx.cacheWriteBody[A] { valueExpr =>
                    enumm
                      .parMatchOn[MIO, scala.util.Try[BSONDocument]](valueExpr) { matched =>
                        import matched.{value as enumCaseValue, Underlying as ChildType}
                        // Compute discriminator value from the configured TypeNaming
                        val simpleName = Type[ChildType].shortName
                        val fullName = fullNameOf[ChildType]
                        val discriminatorNameExpr: Expr[String] = discriminatorFor(simpleName, fullName)
                        Expr.singletonOf[ChildType] match {
                          case Some(_) =>
                            MIO.pure(Expr.quote {
                              scala.util.Success(
                                BSONDocument(
                                  Expr.splice(discriminatorFieldExpr) -> Expr.splice(discriminatorNameExpr)
                                )
                              )
                            })
                          case None =>
                            deriveResultRecursively[ChildType](using ctx.nest[ChildType]).map { childHandler =>
                              Expr.quote {
                                Expr.splice(childHandler).writeTry(Expr.splice(enumCaseValue)).map { childDoc =>
                                  childDoc ++
                                    BSONDocument(
                                      Expr.splice(discriminatorFieldExpr) -> Expr.splice(discriminatorNameExpr)
                                    )
                                }
                              }
                            }
                        }
                      }
                      .flatMap {
                        case Some(result) => MIO.pure(result)
                        case None         =>
                          val err = BsonDocumentHandlerDerivationError.NoChildrenInSealedTrait(Type[A].prettyPrint)
                          Log.error(err.message) >> MIO.fail(err)
                      }
                  }

                for {
                  readLambda <- readLambdaIO
                  writeLambda <- writeLambdaIO
                } yield Expr.quote {
                  hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories
                    .handlerInstance[A](
                      readFn = Expr.splice(readLambda),
                      writeFn = Expr.splice(writeLambda)
                    )
                }
              }
      }
    }

    /** Derives a read-dispatch function for a single enum child. */
    private def deriveChildReadDispatch[A: DerivationCtx, ChildType: Type](
        discriminatorNameExpr: Expr[String],
        discriminatorFieldExpr: Expr[String]
    ): MIO[(Expr[BSONDocument], Expr[scala.util.Try[A]]) => Expr[scala.util.Try[A]]] =

      // Check if child is a singleton - return it directly without deriving a handler
      Expr.singletonOf[ChildType] match {
        case Some(singleton) =>
          MIO.pure { (docExpr: Expr[BSONDocument], elseExpr: Expr[scala.util.Try[A]]) =>
            Expr.quote {
              Expr.splice(docExpr).get(Expr.splice(discriminatorFieldExpr)) match {
                case Some(bsv) if bsv == reactivemongo.api.bson.BSONString(Expr.splice(discriminatorNameExpr)) =>
                  scala.util.Success(Expr.splice(singleton).asInstanceOf[A])
                case _ => Expr.splice(elseExpr)
              }
            }
          }
        case None =>
          deriveResultRecursively[ChildType](using ctx.nest[ChildType]).map {
            childHandler => (docExpr: Expr[BSONDocument], elseExpr: Expr[scala.util.Try[A]]) =>
              Expr.quote {
                Expr.splice(docExpr).get(Expr.splice(discriminatorFieldExpr)) match {
                  case Some(bsv) if bsv == reactivemongo.api.bson.BSONString(Expr.splice(discriminatorNameExpr)) =>
                    Expr
                      .splice(childHandler)
                      .readDocument(Expr.splice(docExpr) -- Expr.splice(discriminatorFieldExpr))
                      .map(_.asInstanceOf[A])
                  case _ => Expr.splice(elseExpr)
                }
              }
          }
      }
  }
}
