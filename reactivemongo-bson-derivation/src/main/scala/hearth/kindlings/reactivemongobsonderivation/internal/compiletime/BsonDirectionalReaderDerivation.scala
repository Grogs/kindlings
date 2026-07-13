package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.*
import hearth.fp.data.NonEmptyList
import hearth.fp.effect.*
import hearth.fp.syntax.*
import hearth.kindlings.reactivemongobsonderivation.{BsonDocumentHandlerConfig, KindlingsBsonDocumentReader}
import hearth.std.*
import reactivemongo.api.bson.BSONDocument

import scala.util.Try

private[compiletime] trait BsonDirectionalReaderDerivation {
  this: BsonDocumentHandlerMacrosImpl & MacroCommons & StdExtensions & AnnotationSupport =>

  // Entrypoints

  /** Independent reader tracer bullet. Its context and cache contain no writer state. */
  def deriveReaderTypeClass[A: Type](
      configExpr: Expr[BsonDocumentHandlerConfig]
  ): Expr[KindlingsBsonDocumentReader[A]] = {
    implicit val ReaderA: Type[KindlingsBsonDocumentReader[A]] =
      Type.Ctor1.of[KindlingsBsonDocumentReader][A]
    implicit val ParentReaderA: Type[reactivemongo.api.bson.BSONDocumentReader[A]] =
      Types.ExternalBsonDocumentReader[A]
    val rootHasKindlingsReader = Type[KindlingsBsonDocumentReader[A]]
      .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
      .toOption
      .nonEmpty
    if (rootHasKindlingsReader) deriveReaderStructurally[A](configExpr)
    else
      Type[reactivemongo.api.bson.BSONDocumentReader[A]]
        .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
        .toEither match {
        case Right(parent) =>
          Expr.quote {
            hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories
              .readerInstance[A]((document: BSONDocument) => Expr.splice(parent).readDocument(document))
          }
        case Left(_) => deriveReaderStructurally[A](configExpr)
      }
  }

  private def deriveReaderStructurally[A: Type](
      configExpr: Expr[BsonDocumentHandlerConfig]
  ): Expr[KindlingsBsonDocumentReader[A]] = {
    implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
    implicit val TryAT: Type[Try[A]] = Types.TryCtor[A]
    Log
      .namedScope(s"Deriving BSONDocumentReader for ${Type[A].prettyPrint}") {
        MIO.scoped { runSafe =>
          val readerCtx = ReaderCtx.from[A](configExpr, configExpr.semiEval.toOption)
          runSafe {
            for {
              _ <- ensureStandardExtensionsLoaded()
              _ <- checkDerivationPolicyOncePerExpansion(Type[A].prettyPrint)
              _ <- deriveReaderBody[A](readerCtx)
              caller <- readerCtx.cache.get1Ary[BSONDocument, Try[A]]("cached-reader-body")
              cache <- readerCtx.cache.get
            } yield cache.toValDefs.use { _ =>
              val call = caller.getOrElse(
                Environment.reportErrorAndAbort(s"No reader body generated for ${Type[A].prettyPrint}")
              )
              Expr.quote {
                hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories
                  .readerInstance[A]((document: BSONDocument) => Expr.splice(call(Expr.quote(document))))
              }
            }
          }
        }
      }
      .runToExprOrFail("KindlingsBsonDocumentReader.derived", timeout = derivationTimeout) { (_, errors) =>
        s"Cannot derive BSON document reader for ${Type[A].prettyPrint}: ${errors.map(_.getMessage).mkString(", ")}"
      }
  }

  protected def deriveRecordReaderBody[A: Type](readerCtx: ReaderCtx[A]): MIO[Unit] = {
    implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
    implicit val StringT: Type[String] = Types.String
    implicit val TryAT: Type[Try[A]] = Types.TryCtor[A]
    val key = "cached-reader-body"
    val builder = ValDefBuilder.ofDef1[BSONDocument, Try[A]](s"read_${Type[A].shortName}", "document")
    for {
      state <- readerCtx.cache.get
      _ <-
        if (builder.isBuilt(state, key)) MIO.pure(())
        else
          directionalRecordPlan[A] match {
            case Left(reason) => MIO.fail(new Exception(reason))
            case Right(plan)  =>
              val constructor = plan.constructor
              val fields = plan.fields
              val knownKeys = fields.flatMap { case (name, parameter) =>
                implicit val IgnoreT: Type[hearth.kindlings.reactivemongobsonderivation.annotations.Ignore] =
                  Types.ignoreAnn
                if (
                  hasAnnotationType[hearth.kindlings.reactivemongobsonderivation.annotations.Ignore](parameter) ||
                  isDirectionalFlattened(parameter)
                ) None
                else
                  Some(
                    resolveDirectionalFieldKey(name, parameter, readerCtx.config, readerCtx.evaluatedConfig)
                  )
              }
              val hasFlattenedField = fields.exists { case (_, parameter) => isDirectionalFlattened(parameter) }
              readerCtx.cache.forwardDeclare(key, builder) >> MIO.scoped { runSafe =>
                runSafe(readerCtx.cache.buildCachedWith(key, builder) { case (_, document) =>
                  runSafe {
                    val deriveField = { (entry: (String, Parameter)) =>
                      val (name, parameter) = entry
                      import parameter.tpe.Underlying as Field
                      implicit val IgnoreT: Type[hearth.kindlings.reactivemongobsonderivation.annotations.Ignore] =
                        Types.ignoreAnn
                      if (hasAnnotationType[hearth.kindlings.reactivemongobsonderivation.annotations.Ignore](parameter))
                        directionalDefaultExpr[Field](parameter) match {
                          case Some(default) => MIO.pure(name -> default.as_??)
                          case None          =>
                            MIO.fail(
                              BsonDocumentHandlerDerivationError.CannotIgnoreFieldWithoutDefault(
                                name,
                                Type[Field].prettyPrint
                              )
                            )
                        }
                      else if (isDirectionalFlattened(parameter)) {
                        if (readerCtx.flattenStack.contains(Type[Field].prettyPrint) || Type[Field] =:= Type[A])
                          MIO.fail(
                            BsonDocumentHandlerDerivationError.CannotFlattenRecursiveField(name, Type[A].prettyPrint)
                          )
                        else
                          annotatedReader[Field](parameter) match {
                            case Some(fieldReader) =>
                              MIO.pure(name -> Expr.quote {
                                Expr.splice(fieldReader).readTry(Expr.splice(document)).get
                              }.as_??)
                            case None =>
                              resolveDirectionalFlattenedReader[Field](name, readerCtx.nestFlattened[Field]).map {
                                fieldReader =>
                                  name -> Expr.quote {
                                    Expr.splice(fieldReader).readDocument(Expr.splice(document)).get
                                  }.as_??
                              }
                          }
                      } else {
                        deriveDirectionalRecordField[Field](name, parameter, document, readerCtx.nest[Field])
                      }
                    }
                    val derivedFields = fields match {
                      case head :: tail => NonEmptyList(head, tail).parTraverse(deriveField).map(_.toList)
                      case Nil          => MIO.pure(List.empty[(String, Expr_??)])
                    }
                    derivedFields.map { values =>
                      val construct = foldInstanceFree(constructor, "Constructor")(
                        onTypes = _ => Map.empty,
                        onValues = _ => values.toList.toMap
                      ) match {
                        case Right(expr) => expr.value.asInstanceOf[Expr[A]]
                        case Left(error) => Environment.reportErrorAndAbort(error)
                      }
                      val unexpectedFieldsCheck =
                        if (hasFlattenedField) Expr.quote(scala.util.Success(()): scala.util.Try[Unit])
                        else buildUnexpectedFieldsCheck(document, knownKeys, readerCtx)
                      Expr.quote {
                        Expr.splice(unexpectedFieldsCheck).flatMap { _ =>
                          scala.util.Try(Expr.splice(construct))
                        }
                      }
                    }
                  }
                })
              }
          }
    } yield ()
  }

  /** Keep `Field` as a real method type parameter. On Scala 2, constructing this expression in the local
    * `parameter.tpe.Underlying` scope can leak the macro-only `parameter` path into generated code.
    */
  private def deriveDirectionalRecordField[Field: Type](
      name: String,
      parameter: Parameter,
      document: Expr[BSONDocument],
      readerCtx: ReaderCtx[Field]
  ): MIO[(String, Expr_??)] = {
    val key = resolveDirectionalFieldKey(name, parameter, readerCtx.config, readerCtx.evaluatedConfig)
    annotatedReader[Field](parameter)
      .fold(resolveDirectionalReader[Field](readerCtx))(MIO.pure)
      .map { fieldReader =>
        val defaultValue = directionalDefaultExpr[Field](parameter)
        val readValue = defaultValue match {
          case Some(defaultExpr) =>
            Expr.quote {
              Expr.splice(document).get(Expr.splice(key)) match {
                case Some(value) => Expr.splice(fieldReader).readTry(value).get
                case None        => Expr.splice(defaultExpr)
              }
            }
          case None if isDirectionalOption[Field] =>
            Expr.quote {
              Expr.splice(document).get(Expr.splice(key)) match {
                case Some(value) => Expr.splice(fieldReader).readTry(value).get
                case None        => None.asInstanceOf[Field]
              }
            }
          case None =>
            Expr.quote {
              Expr.splice(document).get(Expr.splice(key)) match {
                case Some(value) => Expr.splice(fieldReader).readTry(value).get
                case None        => throw new NoSuchElementException("Field not found")
              }
            }
        }
        name -> readValue.as_??
      }
  }

  protected def deriveEnumReaderBody[A: Type](enumm: Enum[A], readerCtx: ReaderCtx[A]): MIO[Unit] = {
    val children = enumm.exhaustiveChildren.fold(enumm.directChildren.toList)(_.toList)
    if (children.isEmpty)
      MIO.fail(BsonDocumentHandlerDerivationError.NoChildrenInSealedTrait(Type[A].prettyPrint))
    else {
      implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
      implicit val TryAT: Type[Try[A]] = Types.TryCtor[A]
      val key = "cached-reader-body"
      val builder = ValDefBuilder.ofDef1[BSONDocument, Try[A]](s"read_${Type[A].shortName}", "document")
      val metadata = directionalEnumMetadata(enumm, readerCtx.config, readerCtx.evaluatedConfig)
      for {
        state <- readerCtx.cache.get
        _ <-
          if (builder.isBuilt(state, key)) MIO.pure(())
          else
            readerCtx.cache.forwardDeclare(key, builder) >> NonEmptyList(children.head, children.tail)
              .parTraverse { case (_, child) =>
                import child.Underlying as Child
                implicit val TryChildT: Type[Try[Child]] = Types.TryCtor[Child]
                val discriminator = directionalDiscriminator[Child](readerCtx.config, readerCtx.evaluatedConfig)
                Expr.singletonOf[Child] match {
                  case Some(singleton) =>
                    MIO.pure { (document: Expr[BSONDocument], fallback: Expr[Try[A]]) =>
                      Expr.quote {
                        Expr.splice(document).get(Expr.splice(metadata.discriminatorField)) match {
                          case Some(reactivemongo.api.bson.BSONString(value)) if value == Expr.splice(discriminator) =>
                            scala.util.Success(Expr.splice(singleton).asInstanceOf[A])
                          case _ => Expr.splice(fallback)
                        }
                      }
                    }
                  case None =>
                    deriveReaderBody[Child](readerCtx.nest[Child]) >> readerCtx.cache
                      .get1Ary[BSONDocument, Try[Child]](key)
                      .flatMap {
                        case Some(call) =>
                          MIO.pure { (document: Expr[BSONDocument], fallback: Expr[Try[A]]) =>
                            Expr.quote {
                              Expr.splice(document).get(Expr.splice(metadata.discriminatorField)) match {
                                case Some(reactivemongo.api.bson.BSONString(value))
                                    if value == Expr.splice(discriminator) =>
                                  Expr
                                    .splice(
                                      call(
                                        Expr.quote(Expr.splice(document) -- Expr.splice(metadata.discriminatorField))
                                      )
                                    )
                                    .map(_.asInstanceOf[A])
                                case _ => Expr.splice(fallback)
                              }
                            }
                          }
                        case None => MIO.fail(new Exception(s"No cached reader body for ${Type[Child].prettyPrint}"))
                      }
                }
              }
              .flatMap { dispatchers =>
                readerCtx.cache.buildCachedWith(key, builder) { case (_, document) =>
                  val failure = Expr.quote {
                    scala.util.Failure(
                      new IllegalArgumentException(
                        "Unknown type discriminator: " +
                          Expr.splice(document).get(Expr.splice(metadata.discriminatorField)).getOrElse("<none>") +
                          ". Expected one of: " + Expr.splice(metadata.knownNames)
                      )
                    ): Try[A]
                  }
                  dispatchers.toList.foldRight(failure)((dispatch, fallback) => dispatch(document, fallback))
                }
              }
      } yield ()
    }
  }

  private def resolveDirectionalFlattenedReader[A: Type](
      fieldName: String,
      readerCtx: ReaderCtx[A]
  ): MIO[Expr[reactivemongo.api.bson.BSONDocumentReader[A]]] = {
    implicit val ReaderT: Type[reactivemongo.api.bson.BSONDocumentReader[A]] = Types.ExternalBsonDocumentReader[A]
    implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
    implicit val TryAT: Type[Try[A]] = Types.TryCtor[A]
    Type[reactivemongo.api.bson.BSONDocumentReader[A]]
      .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
      .toEither match {
      case Right(reader)                                                                        => MIO.pure(reader)
      case Left(_) if directionalRecordConstructor[A].isRight || Enum.parse[A].toEither.isRight =>
        deriveReaderBody[A](readerCtx) >> readerCtx.cache.get1Ary[BSONDocument, Try[A]]("cached-reader-body").flatMap {
          case Some(call) =>
            MIO.pure(Expr.quote {
              reactivemongo.api.bson.BSONDocumentReader.from[A](document => Expr.splice(call(Expr.quote(document))))
            })
          case None => MIO.fail(new Exception(s"No cached reader body for ${Type[A].prettyPrint}"))
        }
      case Left(_) =>
        MIO.fail(BsonDocumentHandlerDerivationError.CannotFlattenNonDocumentField(fieldName, Type[A].prettyPrint))
    }
  }

  private def resolveDirectionalReader[A: Type](
      readerCtx: ReaderCtx[A]
  ): MIO[Expr[reactivemongo.api.bson.BSONReader[A]]] = {
    implicit val ReaderA: Type[reactivemongo.api.bson.BSONReader[A]] = Types.BsonReader[A]
    implicit val TryAT: Type[Try[A]] = Types.TryCtor[A]
    Type[reactivemongo.api.bson.BSONReader[A]]
      .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
      .toEither match {
      case Right(reader)                  => MIO.pure(reader)
      case Left(_) if isDirectionalMap[A] =>
        Type[A] match {
          case IsMap(isMap) =>
            import isMap.Underlying as Pair
            deriveDirectionalMapReader[A, Pair](isMap.value, readerCtx)
          case _ => MIO.fail(new Exception(s"Could not inspect map ${Type[A].prettyPrint}"))
        }
      case Left(_) if isDirectionalOption[A] =>
        Type[A] match {
          case IsOption(option) =>
            import option.Underlying as Inner
            resolveDirectionalReader[Inner](readerCtx.nest[Inner]).map { innerReader =>
              Expr.quote {
                new reactivemongo.api.bson.BSONReader[A] {
                  def readTry(value: reactivemongo.api.bson.BSONValue): Try[A] = value match {
                    case reactivemongo.api.bson.BSONNull => scala.util.Success(None.asInstanceOf[A])
                    case other => Expr.splice(innerReader).readTry(other).map(v => Some(v).asInstanceOf[A])
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
            deriveDirectionalCollectionReader[A, Item](isCollection.value, readerCtx)
          case _ => MIO.fail(new Exception(s"Could not inspect collection ${Type[A].prettyPrint}"))
        }
      case Left(_) if isDirectionalValueType[A] =>
        Type[A] match {
          case IsValueType(valueType) =>
            import valueType.Underlying as Inner
            resolveDirectionalReader[Inner](readerCtx.nest[Inner]).map { innerReader =>
              val wrap = directLambda[Inner, A] { inner =>
                valueType.value.wrap match {
                  case _: CtorLikeOf.EitherStringOrValue[?, ?] =>
                    val result = valueType.value.wrap.apply(inner).asInstanceOf[Expr[Either[String, A]]]
                    Expr.quote(
                      Expr.splice(result).fold(message => throw new IllegalArgumentException(message), identity)
                    )
                  case _ => valueType.value.wrap.apply(inner).asInstanceOf[Expr[A]]
                }
              }
              Expr.quote {
                new reactivemongo.api.bson.BSONReader[A] {
                  def readTry(value: reactivemongo.api.bson.BSONValue): Try[A] =
                    Expr.splice(innerReader).readTry(value).map(Expr.splice(wrap).apply)
                }
              }
            }
          case _ => MIO.fail(new Exception(s"Could not inspect value type ${Type[A].prettyPrint}"))
        }
      case Left(_) if isDirectionalRecord[A] || Enum.parse[A].toEither.isRight =>
        implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
        deriveReaderBody[A](readerCtx) >> readerCtx.cache
          .get1Ary[BSONDocument, Try[A]]("cached-reader-body")
          .flatMap {
            case Some(call) =>
              MIO.pure(Expr.quote {
                new reactivemongo.api.bson.BSONReader[A] {
                  def readTry(value: reactivemongo.api.bson.BSONValue): Try[A] = value match {
                    case document: BSONDocument => Expr.splice(call(Expr.quote(document)))
                    case other                  =>
                      scala.util.Failure(new IllegalArgumentException("Expected BSONDocument, got " + other))
                  }
                }
              })
            case None => MIO.fail(new Exception(s"No cached reader body for ${Type[A].prettyPrint}"))
          }
      case Left(reason) =>
        MIO.fail(BsonDocumentHandlerDerivationError.CannotDeriveField(Type[A].prettyPrint, reason))
    }
  }

  /** Derive a BSON reader for a collection without requiring a writer for its items. */
  private def deriveDirectionalCollectionReader[A: Type, Item: Type](
      isCollection: IsCollectionOf[A, Item],
      readerCtx: ReaderCtx[A]
  ): MIO[Expr[reactivemongo.api.bson.BSONReader[A]]] = {
    implicit val TryAT: Type[Try[A]] = Types.TryCtor[A]
    implicit val BsonArrayT: Type[reactivemongo.api.bson.BSONArray] = Types.BsonArray
    import isCollection.CtorResult
    val factory = isCollection.factory
    val build = isCollection.build
    resolveDirectionalReader[Item](readerCtx.nest[Item]).map { itemReader =>
      val readArray = directLambda[reactivemongo.api.bson.BSONArray, Try[A]] { array =>
        val builder: Expr[scala.collection.mutable.Builder[Item, CtorResult]] = Expr.quote {
          val result = Expr.splice(factory).newBuilder
          val values = Expr.splice(array).values
          var i = 0
          while (i < values.length) {
            result += Expr.splice(itemReader).readTry(values(i)).get
            i += 1
          }
          result
        }
        directionalCollectionBuildResult[A](build, build.ctor(builder).asInstanceOf[Expr[Any]])
      }
      Expr.quote {
        new reactivemongo.api.bson.BSONReader[A] {
          def readTry(value: reactivemongo.api.bson.BSONValue): Try[A] = value match {
            case array: reactivemongo.api.bson.BSONArray => Expr.splice(readArray).apply(array)
            case other => scala.util.Failure(new IllegalArgumentException("Expected BSONArray, got " + other))
          }
        }
      }
    }
  }

  private def directionalCollectionBuildResult[A: Type](
      build: CtorLikeOf[?, A],
      result: Expr[Any]
  )(implicit TryAT: Type[Try[A]]): Expr[Try[A]] = build match {
    case _: CtorLikeOf.PlainValue[?, ?] =>
      Expr.quote(scala.util.Try(Expr.splice(result).asInstanceOf[A]))
    case _: CtorLikeOf.EitherStringOrValue[?, ?] =>
      val either = result.asInstanceOf[Expr[Either[String, A]]]
      Expr.quote {
        Expr.splice(either) match {
          case Right(value)  => scala.util.Success(value)
          case Left(message) => scala.util.Failure(new IllegalArgumentException(message))
        }
      }
    case _: CtorLikeOf.EitherThrowableOrValue[?, ?] =>
      val either = result.asInstanceOf[Expr[Either[Throwable, A]]]
      Expr.quote(Expr.splice(either).fold(scala.util.Failure(_), scala.util.Success(_)))
    case _: CtorLikeOf.EitherIterableStringOrValue[?, ?] =>
      val either = result.asInstanceOf[Expr[Either[Iterable[String], A]]]
      Expr.quote {
        Expr
          .splice(either)
          .fold(
            messages => scala.util.Failure(new IllegalArgumentException(messages.mkString(", "))),
            scala.util.Success(_)
          )
      }
    case _: CtorLikeOf.EitherIterableThrowableOrValue[?, ?] =>
      val either = result.asInstanceOf[Expr[Either[Iterable[Throwable], A]]]
      Expr.quote {
        Expr
          .splice(either)
          .fold(
            errors => scala.util.Failure(errors.headOption.getOrElse(new RuntimeException("unknown"))),
            scala.util.Success(_)
          )
      }
  }

  /** Derive a BSON reader for a map without requiring a writer for its values. */
  private def deriveDirectionalMapReader[A: Type, Pair: Type](
      isMap: IsMapOf[A, Pair],
      readerCtx: ReaderCtx[A]
  ): MIO[Expr[reactivemongo.api.bson.BSONReader[A]]] = {
    implicit val TryAT: Type[Try[A]] = Types.TryCtor[A]
    implicit val StringT: Type[String] = Types.String
    implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
    import isMap.{CtorResult, Key, Value}
    implicit val KeyReaderT: Type[reactivemongo.api.bson.KeyReader[Key]] = Types.KeyReader[Key]
    val keyReader = Type[reactivemongo.api.bson.KeyReader[Key]]
      .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
      .toOption
    if (!(Type[Key] =:= Type[String]) && keyReader.isEmpty)
      MIO.fail(
        BsonDocumentHandlerDerivationError.CannotDeriveCollection(
          Type[A].prettyPrint,
          s"Map key ${Type[Key].prettyPrint} requires a KeyReader"
        )
      )
    else {
      val factory = isMap.factory
      val build = isMap.build
      resolveDirectionalReader[Value](readerCtx.nest[Value]).map { valueReader =>
        val readDocument = directLambda[BSONDocument, Try[A]] { document =>
          val builder: Expr[scala.collection.mutable.Builder[Pair, CtorResult]] = keyReader match {
            case Some(reader) =>
              Expr.quote {
                val result = Expr.splice(factory).newBuilder
                val elements = Expr.splice(document).elements.iterator
                while (elements.hasNext) {
                  val element = elements.next()
                  result += Expr.splice(
                    isMap.pair(
                      Expr.quote(Expr.splice(reader).readTry(element.name).get),
                      Expr.quote(Expr.splice(valueReader).readTry(element.value).get)
                    )
                  )
                }
                result
              }
            case None =>
              Expr.quote {
                val result = Expr.splice(factory).newBuilder
                val elements = Expr.splice(document).elements.iterator
                while (elements.hasNext) {
                  val element = elements.next()
                  result += Expr.splice(
                    isMap.pair(
                      Expr.quote(element.name.asInstanceOf[Key]),
                      Expr.quote(Expr.splice(valueReader).readTry(element.value).get)
                    )
                  )
                }
                result
              }
          }
          directionalCollectionBuildResult[A](build, build.ctor(builder).asInstanceOf[Expr[Any]])
        }
        Expr.quote {
          new reactivemongo.api.bson.BSONReader[A] {
            def readTry(value: reactivemongo.api.bson.BSONValue): Try[A] = value match {
              case document: BSONDocument => Expr.splice(readDocument).apply(document)
              case other => scala.util.Failure(new IllegalArgumentException("Expected BSONDocument, got " + other))
            }
          }
        }
      }
    }
  }

}
