package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.*
import hearth.fp.effect.*
import hearth.fp.syntax.*
import hearth.kindlings.reactivemongobsonderivation.{BsonDocumentHandlerConfig, KindlingsBsonDocumentHandler}
import hearth.std.*
import reactivemongo.api.bson.BSONDocument

import scala.util.Try

private[compiletime] trait BsonCompatibilityHandlerDerivation {
  this: BsonDocumentHandlerMacrosImpl & MacroCommons & StdExtensions =>

  // Context

  final case class DerivationCtx[A](
      tpe: Type[A],
      cache: MLocal[ValDefsCache],
      derivedType: Option[??],
      config: Expr[BsonDocumentHandlerConfig],
      evaluatedConfig: Option[BsonDocumentHandlerConfig],
      flattenStack: List[String],
      writeOnly: Boolean
  ) {

    def nest[B: Type]: DerivationCtx[B] = DerivationCtx(
      tpe = Type[B],
      cache = cache,
      derivedType = derivedType,
      config = config,
      evaluatedConfig = evaluatedConfig,
      flattenStack = flattenStack,
      writeOnly = writeOnly
    )

    def nestFlattened[B: Type]: DerivationCtx[B] =
      nest[B].copy(flattenStack = tpe.prettyPrint :: flattenStack)

    def getInstance[B: Type]: MIO[Option[Expr[KindlingsBsonDocumentHandler[B]]]] = {
      implicit val HandlerB: Type[KindlingsBsonDocumentHandler[B]] = Types.BsonDocumentHandler[B]
      cache.get0Ary[KindlingsBsonDocumentHandler[B]]("cached-handler-instance")
    }
    def setInstance[B: Type](instance: Expr[KindlingsBsonDocumentHandler[B]]): MIO[Unit] = {
      implicit val HandlerB: Type[KindlingsBsonDocumentHandler[B]] = Types.BsonDocumentHandler[B]
      Log.info(s"Caching BSONDocumentHandler instance for ${Type[B].prettyPrint}") >>
        cache.buildCachedWith(
          "cached-handler-instance",
          ValDefBuilder.ofLazy[KindlingsBsonDocumentHandler[B]](s"handler_${Type[B].shortName}")
        )(_ => instance)
    }

    def getHelper[B: Type]: MIO[Option[Expr[Unit] => Expr[KindlingsBsonDocumentHandler[B]]]] = {
      implicit val UnitT: Type[Unit] = Type.of[Unit]
      implicit val HandlerB: Type[KindlingsBsonDocumentHandler[B]] = Types.BsonDocumentHandler[B]
      cache.get1Ary[Unit, KindlingsBsonDocumentHandler[B]]("cached-handler-method")
    }
    def setHelper[B: Type](helper: MIO[Expr[KindlingsBsonDocumentHandler[B]]]): MIO[Unit] = {
      implicit val UnitT: Type[Unit] = Type.of[Unit]
      implicit val HandlerB: Type[KindlingsBsonDocumentHandler[B]] = Types.BsonDocumentHandler[B]
      val defBuilder = ValDefBuilder.ofDef1[Unit, KindlingsBsonDocumentHandler[B]](
        s"handler_${Type[B].shortName}",
        "unit"
      )
      for {
        _ <- Log.info(s"Forward-declaring BSONDocumentHandler helper for ${Type[B].prettyPrint}")
        _ <- cache.forwardDeclare("cached-handler-method", defBuilder)
        // Use buildCachedWith to store the helper MIO WITHOUT evaluating it.
        // The body is evaluated later (when the def is emitted), which allows
        // recursive types to find the cached helper before its body is built.
        _ <- MIO.scoped { runSafe =>
          runSafe(cache.buildCachedWith("cached-handler-method", defBuilder) { _ =>
            runSafe(helper)
          })
        }
        _ <- Log.info(s"Defined BSONDocumentHandler helper for ${Type[B].prettyPrint}")
      } yield ()
    }

    /** Cache a handler read body as a named def, then expose it as a regular lambda. The def is emitted around the
      * final handler instance, so it is safe to call from Scala 3 sibling splices and does not require LambdaBuilder.
      */
    def cacheReadBody[B: Type](
        body: Expr[BSONDocument] => MIO[Expr[Try[B]]]
    ): MIO[Expr[BSONDocument => Try[B]]] = {
      implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
      implicit val TryBT: Type[Try[B]] = Types.TryCtor[B]
      val key = "cached-read-body"
      val builder = ValDefBuilder.ofDef1[BSONDocument, Try[B]](s"read_${Type[B].shortName}", "document")
      if (writeOnly)
        MIO.pure(Expr.quote { (_: BSONDocument) =>
          scala.util.Failure(new UnsupportedOperationException("read body omitted")): Try[B]
        })
      else
        for {
          state <- cache.get
          _ <-
            if (builder.isBuilt(state, key)) MIO.pure(())
            else
              for {
                _ <- cache.forwardDeclare(key, builder)
                _ <- MIO.scoped { runSafe =>
                  runSafe(cache.buildCachedWith(key, builder) { case (_, document) => runSafe(body(document)) })
                }
              } yield ()
          caller <- cache.get1Ary[BSONDocument, Try[B]](key)
        } yield {
          val call = caller.get
          directLambda[BSONDocument, Try[B]](call)
        }
    }

    /** See [[cacheReadBody]]. */
    def cacheWriteBody[B: Type](
        body: Expr[B] => MIO[Expr[Try[BSONDocument]]]
    ): MIO[Expr[B => Try[BSONDocument]]] = {
      implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
      implicit val TryDocumentT: Type[Try[BSONDocument]] = Types.TryCtor[BSONDocument]
      val key = "cached-write-body"
      val builder = ValDefBuilder.ofDef1[B, Try[BSONDocument]](s"write_${Type[B].shortName}", "value")
      for {
        state <- cache.get
        _ <-
          if (builder.isBuilt(state, key)) MIO.pure(())
          else
            for {
              _ <- cache.forwardDeclare(key, builder)
              _ <- MIO.scoped { runSafe =>
                runSafe(cache.buildCachedWith(key, builder) { case (_, value) => runSafe(body(value)) })
              }
            } yield ()
        caller <- cache.get1Ary[B, Try[BSONDocument]](key)
      } yield {
        val call = caller.get
        directLambda[B, Try[BSONDocument]](call)
      }
    }

    override def toString: String = s"BSONDocumentHandler[${tpe.prettyPrint}]"
  }

  object DerivationCtx {
    def from[A: Type](
        derivedType: Option[??],
        config: Expr[BsonDocumentHandlerConfig],
        evaluatedConfig: Option[BsonDocumentHandlerConfig],
        writeOnly: Boolean = false
    ): DerivationCtx[A] =
      DerivationCtx(
        tpe = Type[A],
        cache = ValDefsCache.mlocal,
        derivedType = derivedType,
        config = config,
        evaluatedConfig = evaluatedConfig,
        flattenStack = Nil,
        writeOnly = writeOnly
      )
  }

  def ctx[A](implicit A: DerivationCtx[A]): DerivationCtx[A] = A
  implicit def currentType[A: DerivationCtx]: Type[A] = ctx.tpe

  abstract class DerivationRule(val name: String) extends Rule {
    def apply[A: DerivationCtx]: MIO[Rule.Applicability[Expr[KindlingsBsonDocumentHandler[A]]]]
  }

  // Derivation logic

  def deriveResultRecursively[A: DerivationCtx]: MIO[Expr[KindlingsBsonDocumentHandler[A]]] =
    ctx.getHelper[A].flatMap {
      case Some(helperCall) =>
        Log.info(s"Using cached helper for ${Type[A].prettyPrint}") >> MIO.pure(helperCall(Expr.quote(())))
      case None =>
        ctx.getInstance[A].flatMap {
          case Some(instance) =>
            Log.info(s"Using cached instance for ${Type[A].prettyPrint}") >> MIO.pure(instance)
          case None =>
            tryInlineLeafType[A].flatMap {
              case Some(result) => MIO.pure(result)
              case None         =>
                // Go through setHelper/getHelper to support recursive types.
                ctx.setHelper[A](deriveResultRecursivelyViaRules[A]) >>
                  ctx.getHelper[A].flatMap {
                    case Some(helperCall) => MIO.pure(helperCall(Expr.quote(())))
                    case None => MIO.fail(new Exception(s"Failed to build helper for ${Type[A].prettyPrint}"))
                  }
            }
        }
    }

  private def tryInlineLeafType[A: DerivationCtx]: MIO[Option[Expr[KindlingsBsonDocumentHandler[A]]]] = {
    implicit val HandlerA: Type[KindlingsBsonDocumentHandler[A]] = Types.BsonDocumentHandler[A]
    if (ctx.derivedType.exists(_.Underlying =:= Type[A]))
      MIO.pure(None)
    else
      Type[KindlingsBsonDocumentHandler[A]].summonExprIgnoring(Types.ignoredAutoDerivationMethods*).toEither match {
        case Right(instance) =>
          ctx.setInstance[A](instance) >> MIO.pure(Some(instance))
        case Left(_) => MIO.pure(None)
      }
  }

  protected def deriveResultRecursivelyViaRules[A: DerivationCtx]: MIO[Expr[KindlingsBsonDocumentHandler[A]]] =
    Log.namedScope(s"Deriving BSONDocumentHandler for type ${Type[A].prettyPrint}") {
      Rules(
        UseImplicitWhenAvailableRule,
        DerivationPolicyRule,
        HandleAsValueTypeRule,
        HandleAsCollectionRule,
        HandleAsOptionRule,
        HandleAsNamedTupleRule,
        HandleAsCaseClassRule,
        HandleAsEnumRule
      )(_[A]).flatMap {
        case Right(result) =>
          Log.info(s"Derived BSONDocumentHandler for ${Type[A].prettyPrint}: ${result.prettyPrint}") >> MIO.pure(result)
        case Left(reasons) =>
          val reasonsStrings = reasons.toListMap.view.map { case (rule, reasons) =>
            if (reasons.isEmpty) s"The rule ${rule.name} was not applicable"
            else s" - The rule ${rule.name} was not applicable, for the following reasons: ${reasons.mkString(", ")}"
          }.toList
          val err = BsonDocumentHandlerDerivationError.UnsupportedType(Type[A].prettyPrint, reasonsStrings)
          Log.error(err.message) >> MIO.fail(err)
      }
    }

  // Rules

  object UseImplicitWhenAvailableRule extends DerivationRule("use implicit when available") {
    def apply[A: DerivationCtx]: MIO[Rule.Applicability[Expr[KindlingsBsonDocumentHandler[A]]]] = {
      implicit val HandlerA: Type[KindlingsBsonDocumentHandler[A]] = Types.BsonDocumentHandler[A]
      implicit val ParentHandlerA: Type[reactivemongo.api.bson.BSONDocumentHandler[A]] =
        Types.ExternalBsonDocumentHandler[A]
      Log.info(s"Attempting to summon implicit BSONDocumentHandler[${Type[A].prettyPrint}]") >> {
        // A Scala 3 `derives KindlingsBsonDocumentHandler` expansion puts its generated
        // Kindlings handler in implicit scope. Since that handler is a BSONDocumentHandler
        // subtype, the parent search would find it while it is being initialized and generate
        // an infinite self-call. A plain external BSONDocumentHandler remains valid at the
        // root, so only skip parent lookup when a Kindlings handler is already in scope.
        val rootHasKindlingsHandler =
          ctx.derivedType.exists(_.Underlying =:= Type[A]) &&
            Type[KindlingsBsonDocumentHandler[A]]
              .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
              .toOption
              .nonEmpty
        if (rootHasKindlingsHandler)
          MIO.pure(
            Rule.yielded(s"The type ${Type[A].prettyPrint} is the type being derived, skipping implicit search")
          )
        else
          Type[reactivemongo.api.bson.BSONDocumentHandler[A]]
            .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
            .toEither match {
            case Right(parent) =>
              val instance = Expr.quote {
                hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories
                  .handlerInstance[A](
                    (document: BSONDocument) => Expr.splice(parent).readDocument(document),
                    (value: A) => Expr.splice(parent).writeTry(value)
                  )
              }
              Log.info(s"Found implicit BSONDocumentHandler[${Type[A].prettyPrint}]") >>
                ctx.setInstance[A](instance) >> MIO.pure(Rule.matched(instance))
            case Left(reason) =>
              MIO.pure(Rule.yielded(s"No implicit BSONDocumentHandler found: $reason"))
          }
      }
    }
  }

  object DerivationPolicyRule extends DerivationRule("derivation policy") {
    def apply[A: DerivationCtx]: MIO[Rule.Applicability[Expr[KindlingsBsonDocumentHandler[A]]]] =
      checkDerivationPolicyOncePerExpansion(Type[A].prettyPrint).map(_ => Rule.yielded())
  }

  object HandleAsValueTypeRule extends DerivationRule("handle as value type") {
    def apply[A: DerivationCtx]: MIO[Rule.Applicability[Expr[KindlingsBsonDocumentHandler[A]]]] =
      if (Type[A].isNamedTuple)
        MIO.pure(Rule.yielded(s"${Type[A].prettyPrint} is a named tuple"))
      else
        Type[A] match {
          case IsValueType(isValueType) =>
            import isValueType.Underlying as Inner
            Log.info(s"Handling ${Type[A].prettyPrint} as value type wrapping ${Type[Inner].prettyPrint}") >> {
              val wrapLambda = directLambda[Inner, A] { innerExpr =>
                isValueType.value.wrap match {
                  case _: CtorLikeOf.EitherStringOrValue[?, ?] =>
                    val wrapped = isValueType.value.wrap.apply(innerExpr).asInstanceOf[Expr[Either[String, A]]]
                    Expr.quote {
                      Expr.splice(wrapped) match {
                        case scala.Right(value)  => value
                        case scala.Left(message) => throw new IllegalArgumentException(message)
                      }
                    }
                  case _ => isValueType.value.wrap.apply(innerExpr).asInstanceOf[Expr[A]]
                }
              }
              val unwrapLambda = directLambda[A, Inner](isValueType.value.unwrap)
              // A write-only expansion never evaluates the read body. In particular, do not derive an inner
              // handler merely to obtain a reader when only its writer is required.
              val readerWriterMIO = if (ctx.writeOnly) {
                resolveBsonWriter[Inner](ctx.nest[Inner]).map { writer =>
                  val unusedReader: Expr[reactivemongo.api.bson.BSONReader[Inner]] = Expr.quote {
                    new reactivemongo.api.bson.BSONReader[Inner] {
                      def readTry(bson: reactivemongo.api.bson.BSONValue): Try[Inner] =
                        scala.util.Failure(new UnsupportedOperationException("read body omitted"))
                    }
                  }
                  (unusedReader, writer)
                }
              } else {
                implicit val readerType: Type[reactivemongo.api.bson.BSONReader[Inner]] =
                  Types.BsonReader[Inner]
                implicit val writerType: Type[reactivemongo.api.bson.BSONWriter[Inner]] =
                  Types.BsonWriter[Inner]
                (
                  Type[reactivemongo.api.bson.BSONReader[Inner]].summonExprIgnoring().toOption,
                  Type[reactivemongo.api.bson.BSONWriter[Inner]].summonExprIgnoring().toOption
                ) match {
                  case (Some(r), Some(w)) => MIO.pure((r, w))
                  case _                  =>
                    // Fall back to full derivation (for nested case classes)
                    deriveResultRecursively[Inner](using ctx.nest[Inner]).map { h =>
                      (
                        h.asInstanceOf[Expr[reactivemongo.api.bson.BSONReader[Inner]]],
                        h.asInstanceOf[Expr[reactivemongo.api.bson.BSONWriter[Inner]]]
                      )
                    }
                }
              }
              readerWriterMIO.flatMap { case (innerReaderExpr, innerWriterExpr) =>
                for {
                  readBody <- ctx.cacheReadBody[A] { doc =>
                    MIO.pure(Expr.quote {
                      Expr
                        .splice(innerReaderExpr)
                        .readTry(Expr.splice(doc).get("value").getOrElse(reactivemongo.api.bson.BSONNull))
                        .map(innerValue => Expr.splice(wrapLambda).apply(innerValue))
                    })
                  }
                  writeBody <- ctx.cacheWriteBody[A] { value =>
                    MIO.pure(Expr.quote {
                      Expr
                        .splice(innerWriterExpr)
                        .writeTry(Expr.splice(unwrapLambda)(Expr.splice(value)))
                        .map(bsonValue => BSONDocument("value" -> bsonValue))
                    })
                  }
                } yield Rule.matched(Expr.quote {
                  hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories
                    .handlerInstance[A](readFn = Expr.splice(readBody), writeFn = Expr.splice(writeBody))
                })
              }
            }
          case _ =>
            MIO.pure(Rule.yielded(s"${Type[A].prettyPrint} is not a value type"))
        }
  }

  object HandleAsOptionRule extends DerivationRule("handle as Option") {
    def apply[A: DerivationCtx]: MIO[Rule.Applicability[Expr[KindlingsBsonDocumentHandler[A]]]] =
      Log.info(s"Attempting to handle ${Type[A].prettyPrint} as Option") >> {
        Type[A] match {
          case IsOption(isOption) =>
            import isOption.Underlying as Inner
            val innerCtx = ctx.nest[Inner]
            for {
              innerReader <- resolveBsonReader[Inner](innerCtx)
              innerWriter <- resolveBsonWriter[Inner](innerCtx)
              readBody <- ctx.cacheReadBody[A] { doc =>
                MIO.pure(Expr.quote {
                  Expr.splice(doc).get("value") match {
                    case None | Some(reactivemongo.api.bson.BSONNull) => scala.util.Success(None.asInstanceOf[A])
                    case Some(v) => Expr.splice(innerReader).readTry(v).map(_.asInstanceOf[A])
                  }
                })
              }
              writeBody <- ctx.cacheWriteBody[A] { value =>
                MIO.pure(Expr.quote {
                  Expr.splice(value) match {
                    case Some(v) =>
                      Expr.splice(innerWriter).writeTry(v.asInstanceOf[Inner]).map(bsv => BSONDocument("value" -> bsv))
                    case None => scala.util.Success(BSONDocument.empty)
                  }
                })
              }
            } yield Rule.matched(Expr.quote {
              hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories
                .handlerInstance[A](readFn = Expr.splice(readBody), writeFn = Expr.splice(writeBody))
            })
          case _ => MIO.pure(Rule.yielded(s"${Type[A].prettyPrint} is not an Option"))
        }
      }
  }

  /** Compile-time zero value for a field type — used as the initial value of a typed local var so the constructor never
    * sees an uninitialized field even if the read path short-circuits. Reference: jsoniter's `deriveZeroValue` (see
    * `kindlings-runtime-perf` skill, technique #2). Without typed vars, decayed reads go through `Array[Any]` which
    * boxes every primitive into `java.lang.Integer` / `Boolean` etc.
    */
  @scala.annotation.nowarn("msg=is never used")
  private[compiletime] def deriveZeroValue[A: Type]: Expr[A] =
    if (Type[A] <:< Type.of[AnyRef]) Expr.quote(null.asInstanceOf[A])
    else if (Type[A] =:= Type.of[Boolean]) Expr.quote(false.asInstanceOf[A])
    else if (Type[A] =:= Type.of[Byte]) Expr.quote(0.toByte.asInstanceOf[A])
    else if (Type[A] =:= Type.of[Short]) Expr.quote(0.toShort.asInstanceOf[A])
    else if (Type[A] =:= Type.of[Int]) Expr.quote(0.asInstanceOf[A])
    else if (Type[A] =:= Type.of[Long]) Expr.quote(0L.asInstanceOf[A])
    else if (Type[A] =:= Type.of[Float]) Expr.quote(0.0f.asInstanceOf[A])
    else if (Type[A] =:= Type.of[Double]) Expr.quote(0.0.asInstanceOf[A])
    else if (Type[A] =:= Type.of[Char]) Expr.quote(' '.asInstanceOf[A])
    else Expr.quote(null.asInstanceOf[A])

  object HandleAsNamedTupleRule extends DerivationRule("handle as named tuple") {
    def apply[A: DerivationCtx]: MIO[Rule.Applicability[Expr[KindlingsBsonDocumentHandler[A]]]] =
      Log.info(s"Attempting to handle ${Type[A].prettyPrint} as a named tuple") >> {
        NamedTuple.parse[A].toEither match {
          case Right(namedTuple) => HandleAsCaseClassRule.deriveNamedTuple[A](namedTuple).map(Rule.matched)
          case Left(reason)      => MIO.pure(Rule.yielded(reason))
        }
      }
  }

}
