package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.*
import hearth.fp.data.NonEmptyList
import hearth.fp.effect.*
import hearth.fp.syntax.*
import hearth.kindlings.reactivemongobsonderivation.{BsonDocumentHandlerConfig, KindlingsBsonDocumentWriter}
import hearth.std.*
import reactivemongo.api.bson.BSONDocument

import scala.util.Try

private[compiletime] trait BsonDirectionalWriterDerivation {
  this: BsonDocumentHandlerMacrosImpl & MacroCommons & StdExtensions & AnnotationSupport =>

  /** Independent writer tracer bullet. Its context and cache contain no reader state. */
  def deriveWriterTypeClass[A: Type](
      configExpr: Expr[BsonDocumentHandlerConfig]
  ): Expr[KindlingsBsonDocumentWriter[A]] = {
    implicit val WriterA: Type[KindlingsBsonDocumentWriter[A]] =
      Type.Ctor1.of[KindlingsBsonDocumentWriter][A]
    implicit val ParentWriterA: Type[reactivemongo.api.bson.BSONDocumentWriter[A]] =
      Types.ExternalBsonDocumentWriter[A]
    val rootHasKindlingsWriter = Type[KindlingsBsonDocumentWriter[A]]
      .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
      .toOption
      .nonEmpty
    if (rootHasKindlingsWriter) deriveWriterStructurally[A](configExpr)
    else
      Type[reactivemongo.api.bson.BSONDocumentWriter[A]]
        .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
        .toEither match {
        case Right(parent) =>
          Expr.quote {
            hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories
              .writerInstance[A]((value: A) => Expr.splice(parent).writeTry(value))
          }
        case Left(_) => deriveWriterStructurally[A](configExpr)
      }
  }

  private def deriveWriterStructurally[A: Type](
      configExpr: Expr[BsonDocumentHandlerConfig]
  ): Expr[KindlingsBsonDocumentWriter[A]] = {
    implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
    implicit val TryDocumentT: Type[Try[BSONDocument]] = Types.TryCtor[BSONDocument]
    Log
      .namedScope(s"Deriving BSONDocumentWriter for ${Type[A].prettyPrint}") {
        MIO.scoped { runSafe =>
          val writerCtx = WriterCtx.from[A](configExpr, configExpr.semiEval.toOption)
          runSafe {
            for {
              _ <- ensureStandardExtensionsLoaded()
              _ <- checkDerivationPolicyOncePerExpansion(Type[A].prettyPrint)
              _ <- deriveWriterBody[A](writerCtx)
              caller <- writerCtx.cache.get1Ary[A, Try[BSONDocument]]("cached-writer-body")
              cache <- writerCtx.cache.get
            } yield cache.toValDefs.use { _ =>
              val call = caller.getOrElse(
                Environment.reportErrorAndAbort(s"No writer body generated for ${Type[A].prettyPrint}")
              )
              Expr.quote {
                hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories
                  .writerInstance[A]((value: A) => Expr.splice(call(Expr.quote(value))))
              }
            }
          }
        }
      }
      .runToExprOrFail("KindlingsBsonDocumentWriter.derived", timeout = derivationTimeout) { (_, errors) =>
        s"Cannot derive BSON document writer for ${Type[A].prettyPrint}: ${errors.map(_.getMessage).mkString(", ")}"
      }
  }

  protected def deriveRecordWriterBody[A: Type](writerCtx: WriterCtx[A]): MIO[Unit] = {
    implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
    implicit val TryDocumentT: Type[Try[BSONDocument]] = Types.TryCtor[BSONDocument]
    val key = "cached-writer-body"
    val builder = ValDefBuilder.ofDef1[A, Try[BSONDocument]](s"write_${Type[A].shortName}", "value")
    for {
      state <- writerCtx.cache.get
      _ <-
        if (builder.isBuilt(state, key)) MIO.pure(())
        else
          directionalRecordPlan[A] match {
            case Left(reason) => MIO.fail(new Exception(reason))
            case Right(plan)  =>
              writerCtx.cache.forwardDeclare(key, builder) >> MIO.scoped { runSafe =>
                runSafe(writerCtx.cache.buildCachedWith(key, builder) { case (_, value) =>
                  runSafe {
                    implicit val IgnoreT: Type[hearth.kindlings.reactivemongobsonderivation.annotations.Ignore] =
                      Types.ignoreAnn
                    val parameters = plan.fields
                    val fieldValues = directionalRecordFieldValues[A](value).filterNot { case (name, _) =>
                      val parameter = parameters.find(_._1 == name).get._2
                      hasAnnotationType[hearth.kindlings.reactivemongobsonderivation.annotations.Ignore](parameter)
                    }
                    val deriveField = { (entry: (String, Expr_??)) =>
                      val (name, fieldValue) = entry
                      import fieldValue.Underlying as Field
                      val parameter = parameters.find(_._1 == name).get._2
                      val key = resolveDirectionalFieldKey(
                        name,
                        parameter,
                        writerCtx.config,
                        writerCtx.evaluatedConfig
                      )
                      if (isDirectionalFlattened(parameter)) {
                        if (writerCtx.flattenStack.contains(Type[Field].prettyPrint) || Type[Field] =:= Type[A])
                          MIO.fail(
                            BsonDocumentHandlerDerivationError.CannotFlattenRecursiveField(name, Type[A].prettyPrint)
                          )
                        else
                          annotatedWriter[Field](parameter) match {
                            case Some(fieldWriter) =>
                              MIO.pure(Expr.quote {
                                Expr
                                  .splice(fieldWriter)
                                  .writeTry(Expr.splice(fieldValue.value.asInstanceOf[Expr[Field]]))
                                  .flatMap {
                                    case document: BSONDocument => scala.util.Success(document.elements.toList)
                                    case value                  =>
                                      scala.util.Failure(
                                        new IllegalArgumentException(
                                          "@Flatten @Writer must produce BSONDocument, got " + value
                                        )
                                      )
                                  }
                              })
                            case None =>
                              resolveDirectionalFlattenedWriter[Field](name, writerCtx.nestFlattened[Field]).map {
                                fieldWriter =>
                                  Expr.quote {
                                    Expr
                                      .splice(fieldWriter)
                                      .writeTry(Expr.splice(fieldValue.value.asInstanceOf[Expr[Field]]))
                                      .map(_.elements.toList)
                                  }
                              }
                          }
                      } else
                        annotatedWriter[Field](parameter)
                          .fold(resolveDirectionalWriter[Field](writerCtx.nest[Field]))(MIO.pure)
                          .map { fieldWriter =>
                            val value = fieldValue.value.asInstanceOf[Expr[Field]]
                            implicit val NoneAsNullT: Type[
                              hearth.kindlings.reactivemongobsonderivation.annotations.NoneAsNull
                            ] = Types.noneAsNullAnn
                            val noneAsNull = hasAnnotationType[
                              hearth.kindlings.reactivemongobsonderivation.annotations.NoneAsNull
                            ](parameter)
                            if (isDirectionalOption[Field] && !noneAsNull)
                              Expr.quote {
                                Expr.splice(value).asInstanceOf[Option[Any]] match {
                                  case None => scala.util.Success(Nil)
                                  case _    =>
                                    Expr.splice(fieldWriter).writeTry(Expr.splice(value)).map { bson =>
                                      List(reactivemongo.api.bson.BSONElement(Expr.splice(key), bson))
                                    }
                                }
                              }
                            else
                              Expr.quote {
                                Expr.splice(fieldWriter).writeTry(Expr.splice(value)).map { bson =>
                                  List(reactivemongo.api.bson.BSONElement(Expr.splice(key), bson))
                                }
                              }
                          }
                    }
                    val derivedFields = fieldValues match {
                      case head :: tail => NonEmptyList(head, tail).parTraverse(deriveField).map(_.toList)
                      case Nil          => MIO.pure(List.empty[Expr[Try[List[reactivemongo.api.bson.BSONElement]]]])
                    }
                    derivedFields.map { elements =>
                      val sequenced = elements.toList.foldRight(
                        Expr.quote(
                          scala.util.Success(List.empty[List[reactivemongo.api.bson.BSONElement]]): Try[
                            List[List[reactivemongo.api.bson.BSONElement]]
                          ]
                        )
                      ) { (next, tail) =>
                        Expr.quote(for { head <- Expr.splice(next); rest <- Expr.splice(tail) } yield head :: rest)
                      }
                      Expr.quote(Expr.splice(sequenced).map(values => BSONDocument(values.flatten*)))
                    }
                  }
                })
              }
          }
    } yield ()
  }

  protected def deriveEnumWriterBody[A: Type](enumm: Enum[A], writerCtx: WriterCtx[A]): MIO[Unit] = {
    val children = enumm.exhaustiveChildren.fold(enumm.directChildren.toList)(_.toList)
    if (children.isEmpty)
      MIO.fail(BsonDocumentHandlerDerivationError.NoChildrenInSealedTrait(Type[A].prettyPrint))
    else {
      implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
      implicit val TryDocumentT: Type[Try[BSONDocument]] = Types.TryCtor[BSONDocument]
      val key = "cached-writer-body"
      val builder = ValDefBuilder.ofDef1[A, Try[BSONDocument]](s"write_${Type[A].shortName}", "value")
      val metadata = directionalEnumMetadata(enumm, writerCtx.config, writerCtx.evaluatedConfig)
      for {
        state <- writerCtx.cache.get
        _ <-
          if (builder.isBuilt(state, key)) MIO.pure(())
          else
            writerCtx.cache.forwardDeclare(key, builder) >> MIO.scoped { runSafe =>
              runSafe(writerCtx.cache.buildCachedWith(key, builder) { case (_, value) =>
                runSafe(
                  enumm
                    .parMatchOn[MIO, Try[BSONDocument]](value) { matched =>
                      import matched.{Underlying as Child, value as childValue}
                      val discriminator = directionalDiscriminator[Child](writerCtx.config, writerCtx.evaluatedConfig)
                      Expr.singletonOf[Child] match {
                        case Some(_) =>
                          MIO.pure(Expr.quote {
                            scala.util.Success(
                              BSONDocument(Expr.splice(metadata.discriminatorField) -> Expr.splice(discriminator))
                            )
                          })
                        case None =>
                          deriveWriterBody[Child](writerCtx.nest[Child]) >> writerCtx.cache
                            .get1Ary[Child, Try[BSONDocument]](key)
                            .flatMap {
                              case Some(call) =>
                                MIO.pure(Expr.quote {
                                  Expr.splice(call(childValue)).map { document =>
                                    document ++ BSONDocument(
                                      Expr.splice(metadata.discriminatorField) -> Expr.splice(discriminator)
                                    )
                                  }
                                })
                              case None =>
                                MIO.fail(new Exception(s"No cached writer body for ${Type[Child].prettyPrint}"))
                            }
                      }
                    }
                    .flatMap {
                      case Some(result) => MIO.pure(result)
                      case None         =>
                        MIO.fail(BsonDocumentHandlerDerivationError.NoChildrenInSealedTrait(Type[A].prettyPrint))
                    }
                )
              })
            }
      } yield ()
    }
  }

  private def directionalRecordFieldValues[A: Type](value: Expr[A]): List[(String, Expr_??)] =
    CaseClass.parse[A].toEither match {
      case Right(caseClass) => caseClass.caseFieldValuesAt(value).toList
      case Left(_)          =>
        NamedTuple.parse[A].toEither match {
          case Right(namedTuple) =>
            namedTuple.primaryConstructor.parameters.flatten.toList.map { case (name, parameter) =>
              import parameter.tpe.Underlying as Field
              val index = Expr(parameter.index)
              name -> Expr.quote {
                Expr.splice(value).asInstanceOf[Product].productElement(Expr.splice(index)).asInstanceOf[Field]
              }.as_??
            }
          case Left(reason) => Environment.reportErrorAndAbort(reason)
        }
    }

  private def resolveDirectionalFlattenedWriter[A: Type](
      fieldName: String,
      writerCtx: WriterCtx[A]
  ): MIO[Expr[reactivemongo.api.bson.BSONDocumentWriter[A]]] = {
    implicit val WriterT: Type[reactivemongo.api.bson.BSONDocumentWriter[A]] = Types.ExternalBsonDocumentWriter[A]
    implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
    implicit val TryDocumentT: Type[Try[BSONDocument]] = Types.TryCtor[BSONDocument]
    Type[reactivemongo.api.bson.BSONDocumentWriter[A]]
      .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
      .toEither match {
      case Right(writer)                                                                        => MIO.pure(writer)
      case Left(_) if directionalRecordConstructor[A].isRight || Enum.parse[A].toEither.isRight =>
        deriveWriterBody[A](writerCtx) >> writerCtx.cache.get1Ary[A, Try[BSONDocument]]("cached-writer-body").flatMap {
          case Some(call) =>
            MIO.pure(Expr.quote {
              reactivemongo.api.bson.BSONDocumentWriter.from[A](value => Expr.splice(call(Expr.quote(value))))
            })
          case None => MIO.fail(new Exception(s"No cached writer body for ${Type[A].prettyPrint}"))
        }
      case Left(_) =>
        MIO.fail(BsonDocumentHandlerDerivationError.CannotFlattenNonDocumentField(fieldName, Type[A].prettyPrint))
    }
  }

  private def resolveDirectionalWriter[A: Type](
      writerCtx: WriterCtx[A]
  ): MIO[Expr[reactivemongo.api.bson.BSONWriter[A]]] = {
    implicit val WriterA: Type[reactivemongo.api.bson.BSONWriter[A]] = Types.BsonWriter[A]
    Type[reactivemongo.api.bson.BSONWriter[A]]
      .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
      .toEither match {
      case Right(writer)                  => MIO.pure(writer)
      case Left(_) if isDirectionalMap[A] =>
        Type[A] match {
          case IsMap(isMap) =>
            import isMap.Underlying as Pair
            deriveDirectionalMapWriter[A, Pair](isMap.value, writerCtx)
          case _ => MIO.fail(new Exception(s"Could not inspect map ${Type[A].prettyPrint}"))
        }
      case Left(_) if isDirectionalOption[A] =>
        Type[A] match {
          case IsOption(option) =>
            import option.Underlying as Inner
            resolveDirectionalWriter[Inner](writerCtx.nest[Inner]).map { innerWriter =>
              Expr.quote {
                new reactivemongo.api.bson.BSONWriter[A] {
                  def writeTry(value: A): Try[reactivemongo.api.bson.BSONValue] =
                    value.asInstanceOf[Option[Inner]] match {
                      case Some(inner) => Expr.splice(innerWriter).writeTry(inner)
                      case None        => scala.util.Success(reactivemongo.api.bson.BSONNull)
                    }
                }
              }
            }
          case _ => MIO.fail(new Exception(s"Could not inspect Option ${Type[A].prettyPrint}"))
        }
      case Left(_) if isDirectionalCollection[A] =>
        Type[A] match {
          case IsCollection(isCollection) =>
            import isCollection.Underlying as Item
            deriveDirectionalCollectionWriter[A, Item](isCollection.value, writerCtx)
          case _ => MIO.fail(new Exception(s"Could not inspect collection ${Type[A].prettyPrint}"))
        }
      case Left(_) if isDirectionalValueType[A] =>
        Type[A] match {
          case IsValueType(valueType) =>
            import valueType.Underlying as Inner
            resolveDirectionalWriter[Inner](writerCtx.nest[Inner]).map { innerWriter =>
              val unwrap = directLambda[A, Inner](valueType.value.unwrap)
              Expr.quote {
                new reactivemongo.api.bson.BSONWriter[A] {
                  def writeTry(value: A): Try[reactivemongo.api.bson.BSONValue] =
                    Expr.splice(innerWriter).writeTry(Expr.splice(unwrap).apply(value))
                }
              }
            }
          case _ => MIO.fail(new Exception(s"Could not inspect value type ${Type[A].prettyPrint}"))
        }
      case Left(_) if isDirectionalRecord[A] || Enum.parse[A].toEither.isRight =>
        implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
        implicit val TryDocumentT: Type[Try[BSONDocument]] = Types.TryCtor[BSONDocument]
        deriveWriterBody[A](writerCtx) >> writerCtx.cache
          .get1Ary[A, Try[BSONDocument]]("cached-writer-body")
          .flatMap {
            case Some(call) => MIO.pure(writerFromDocumentWrite[A](call))
            case None       => MIO.fail(new Exception(s"No cached writer body for ${Type[A].prettyPrint}"))
          }
      case Left(reason) =>
        MIO.fail(BsonDocumentHandlerDerivationError.CannotDeriveField(Type[A].prettyPrint, reason))
    }
  }

  /** Derive a BSON writer for a collection without requiring a reader for its items. */
  private def deriveDirectionalCollectionWriter[A: Type, Item: Type](
      isCollection: IsCollectionOf[A, Item],
      writerCtx: WriterCtx[A]
  ): MIO[Expr[reactivemongo.api.bson.BSONWriter[A]]] = {
    implicit val BsonValueT: Type[reactivemongo.api.bson.BSONValue] = Types.BsonValue
    implicit val TryBsonValueT: Type[Try[reactivemongo.api.bson.BSONValue]] =
      Types.TryCtor[reactivemongo.api.bson.BSONValue]
    resolveDirectionalWriter[Item](writerCtx.nest[Item]).map { itemWriter =>
      val writeCollection = directLambda[A, Try[reactivemongo.api.bson.BSONValue]] { value =>
        Expr.quote {
          scala.util.Try {
            val values = Expr.splice(isCollection.asIterable(value)).asInstanceOf[Iterable[Item]]
            val result = scala.collection.mutable.ListBuffer.empty[reactivemongo.api.bson.BSONValue]
            val iterator = values.iterator
            while (iterator.hasNext)
              result += Expr.splice(itemWriter).writeTry(iterator.next()).get
            reactivemongo.api.bson.BSONArray(result.toList): reactivemongo.api.bson.BSONValue
          }
        }
      }
      Expr.quote {
        new reactivemongo.api.bson.BSONWriter[A] {
          def writeTry(value: A): Try[reactivemongo.api.bson.BSONValue] = Expr.splice(writeCollection).apply(value)
        }
      }
    }
  }

  /** Derive a BSON writer for a map without requiring a reader for its values. */
  private def deriveDirectionalMapWriter[A: Type, Pair: Type](
      isMap: IsMapOf[A, Pair],
      writerCtx: WriterCtx[A]
  ): MIO[Expr[reactivemongo.api.bson.BSONWriter[A]]] = {
    implicit val StringT: Type[String] = Types.String
    implicit val BsonValueT: Type[reactivemongo.api.bson.BSONValue] = Types.BsonValue
    implicit val TryBsonValueT: Type[Try[reactivemongo.api.bson.BSONValue]] =
      Types.TryCtor[reactivemongo.api.bson.BSONValue]
    import isMap.{Key, Value}
    implicit val KeyWriterT: Type[reactivemongo.api.bson.KeyWriter[Key]] = Types.KeyWriter[Key]
    val keyWriter = Type[reactivemongo.api.bson.KeyWriter[Key]]
      .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
      .toOption
    if (!(Type[Key] =:= Type[String]) && keyWriter.isEmpty)
      MIO.fail(
        BsonDocumentHandlerDerivationError.CannotDeriveCollection(
          Type[A].prettyPrint,
          s"Map key ${Type[Key].prettyPrint} requires a KeyWriter"
        )
      )
    else
      resolveDirectionalWriter[Value](writerCtx.nest[Value]).map { valueWriter =>
        val writeMap = directLambda[A, Try[reactivemongo.api.bson.BSONValue]] { value =>
          keyWriter match {
            case Some(writer) =>
              Expr.quote {
                scala.util.Try {
                  val pairs = Expr.splice(isMap.asIterable(value)).asInstanceOf[Iterable[(Key, Value)]]
                  val elements = scala.collection.mutable.ListBuffer.empty[reactivemongo.api.bson.BSONElement]
                  val iterator = pairs.iterator
                  while (iterator.hasNext) {
                    val pair = iterator.next()
                    elements += reactivemongo.api.bson.BSONElement(
                      Expr.splice(writer).writeTry(pair._1).get,
                      Expr.splice(valueWriter).writeTry(pair._2).get
                    )
                  }
                  BSONDocument(elements.toList*): reactivemongo.api.bson.BSONValue
                }
              }
            case None =>
              Expr.quote {
                scala.util.Try {
                  val pairs = Expr.splice(isMap.asIterable(value)).asInstanceOf[Iterable[(Key, Value)]]
                  val elements = scala.collection.mutable.ListBuffer.empty[reactivemongo.api.bson.BSONElement]
                  val iterator = pairs.iterator
                  while (iterator.hasNext) {
                    val pair = iterator.next()
                    elements += reactivemongo.api.bson.BSONElement(
                      pair._1.asInstanceOf[String],
                      Expr.splice(valueWriter).writeTry(pair._2).get
                    )
                  }
                  BSONDocument(elements.toList*): reactivemongo.api.bson.BSONValue
                }
              }
          }
        }
        Expr.quote {
          new reactivemongo.api.bson.BSONWriter[A] {
            def writeTry(value: A): Try[reactivemongo.api.bson.BSONValue] = Expr.splice(writeMap).apply(value)
          }
        }
      }
  }

}
