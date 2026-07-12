package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.MacroCommons
import hearth.fp.data.NonEmptyList
import hearth.fp.effect.*
import hearth.fp.syntax.*
import hearth.std.*

import hearth.kindlings.reactivemongobsonderivation.{
  BsonDocumentHandlerConfig,
  KindlingsBsonDocumentHandler,
  KindlingsBsonDocumentReader,
  KindlingsBsonDocumentWriter,
  TypeNaming
}
import reactivemongo.api.bson.BSONDocument

import scala.util.Try

trait BsonDocumentHandlerMacrosImpl
    extends hearth.kindlings.derivation.compiletime.DerivationTimeout
    with hearth.kindlings.derivation.compiletime.DerivationPolicy
    with hearth.kindlings.derivation.compiletime.LoadStandardExtensionsOnce
    with hearth.kindlings.derivation.compiletime.MethodFolds
    with BsonCodecResolution
    with BsonCaseClassDerivation
    with BsonEnumDerivation
    with BsonCollectionDerivation {
  this: MacroCommons & StdExtensions & AnnotationSupport =>

  override protected def derivationSettingsNamespace: String = "reactivemongoBsonDerivation"

  override protected def derivationPolicyTypeClassName: String = "KindlingsBsonDocumentHandler"
  override protected def derivationOptInImportHint: String =
    "import hearth.kindlings.reactivemongobsonderivation.policy.allowDerivationForReactiveMongoBson"
  override protected def isDerivationOptInMarkerInScope: Boolean = {
    implicit val AllowDerivation: Type[KindlingsBsonDocumentHandler.AllowDerivation] = Types.AllowDerivation
    Expr.summonImplicit[KindlingsBsonDocumentHandler.AllowDerivation].isDefined
  }

  // Types

  /** Platform-specific way to get the full class name (e.g. `pkg.Outer.Inner`). Implemented in the Scala 3 companion
    * using `quotes.reflect`.
    */
  protected def fullNameOf[A: Type]: String

  private[compiletime] object Types {
    def BsonDocumentHandler: Type.Ctor1[KindlingsBsonDocumentHandler] = Type.Ctor1.of[KindlingsBsonDocumentHandler]
    def ExternalBsonDocumentHandler: Type.Ctor1[reactivemongo.api.bson.BSONDocumentHandler] =
      Type.Ctor1.of[reactivemongo.api.bson.BSONDocumentHandler]
    def ExternalBsonDocumentReader: Type.Ctor1[reactivemongo.api.bson.BSONDocumentReader] =
      Type.Ctor1.of[reactivemongo.api.bson.BSONDocumentReader]
    def ExternalBsonDocumentWriter: Type.Ctor1[reactivemongo.api.bson.BSONDocumentWriter] =
      Type.Ctor1.of[reactivemongo.api.bson.BSONDocumentWriter]
    val LogDerivation: Type[KindlingsBsonDocumentHandler.LogDerivation] =
      Type.of[KindlingsBsonDocumentHandler.LogDerivation]
    val AllowDerivation: Type[KindlingsBsonDocumentHandler.AllowDerivation] =
      Type.of[KindlingsBsonDocumentHandler.AllowDerivation]
    val BsonDocument: Type[BSONDocument] = Type.of[BSONDocument]
    val String: Type[String] = Type.of[String]
    val BsonValue: Type[reactivemongo.api.bson.BSONValue] = Type.of[reactivemongo.api.bson.BSONValue]
    val BsonArray: Type[reactivemongo.api.bson.BSONArray] = Type.of[reactivemongo.api.bson.BSONArray]
    val BsonElement: Type[reactivemongo.api.bson.BSONElement] = Type.of[reactivemongo.api.bson.BSONElement]
    val Any: Type[Any] = Type.of[Any]
    val TryBsonDocument: Type[Try[BSONDocument]] = Type.of[Try[BSONDocument]]
    val BsonReader: Type.Ctor1[reactivemongo.api.bson.BSONReader] = Type.Ctor1.of[reactivemongo.api.bson.BSONReader]
    val BsonWriter: Type.Ctor1[reactivemongo.api.bson.BSONWriter] = Type.Ctor1.of[reactivemongo.api.bson.BSONWriter]
    val KeyReader: Type.Ctor1[reactivemongo.api.bson.KeyReader] = Type.Ctor1.of[reactivemongo.api.bson.KeyReader]
    val KeyWriter: Type.Ctor1[reactivemongo.api.bson.KeyWriter] = Type.Ctor1.of[reactivemongo.api.bson.KeyWriter]
    val TryCtor: Type.Ctor1[Try] = Type.Ctor1.of[Try]
    val fieldNameAnn: Type[hearth.kindlings.reactivemongobsonderivation.annotations.FieldName] =
      Type.of[hearth.kindlings.reactivemongobsonderivation.annotations.FieldName]
    val noneAsNullAnn: Type[hearth.kindlings.reactivemongobsonderivation.annotations.NoneAsNull] =
      Type.of[hearth.kindlings.reactivemongobsonderivation.annotations.NoneAsNull]
    val flattenAnn: Type[hearth.kindlings.reactivemongobsonderivation.annotations.Flatten] =
      Type.of[hearth.kindlings.reactivemongobsonderivation.annotations.Flatten]
    val ignoreAnn: Type[hearth.kindlings.reactivemongobsonderivation.annotations.Ignore] =
      Type.of[hearth.kindlings.reactivemongobsonderivation.annotations.Ignore]

    lazy val ignoredAutoDerivationMethods: Seq[UntypedMethod] =
      Type.of[KindlingsBsonDocumentHandler.type].methods.collect {
        case method if method.isImplicit => method.asUntyped
      } ++ Type.of[KindlingsBsonDocumentReader.type].methods.collect {
        case method if method.isImplicit => method.asUntyped
      } ++ Type.of[KindlingsBsonDocumentWriter.type].methods.collect {
        case method if method.isImplicit => method.asUntyped
      }
  }

  // Field name resolution

  /** Build the BSON key expression for a field, applying the `@FieldName` annotation and the config's `fieldNameMapper`
    * (at compile time if available, runtime otherwise).
    */
  protected def resolveFieldKeyExpr[A](
      fieldName: String,
      param: Parameter,
      ctx: DerivationCtx[A]
  ): Expr[String] = {
    implicit val fnt: Type[hearth.kindlings.reactivemongobsonderivation.annotations.FieldName] = Types.fieldNameAnn
    val annotationOverride: Option[String] =
      getAnnotationStringArg[hearth.kindlings.reactivemongobsonderivation.annotations.FieldName](param)

    annotationOverride match {
      case Some(name) =>
        // @fieldName annotation takes precedence - use as-is
        Expr(name)
      case None =>
        // Apply config's fieldNameMapper
        ctx.evaluatedConfig match {
          case Some(evalCfg) =>
            // semiEval succeeded - apply mapper at compile time
            Expr(evalCfg.fieldNameMapper(fieldName))
          case None =>
            // semiEval failed - splice config at runtime
            Expr.quote {
              Expr.splice(ctx.config).fieldNameMapper(Expr.splice(Expr(fieldName)))
            }
        }
    }
  }

  private def resolveDirectionalFieldKey(
      fieldName: String,
      param: Parameter,
      config: Expr[BsonDocumentHandlerConfig],
      evaluatedConfig: Option[BsonDocumentHandlerConfig]
  ): Expr[String] = {
    implicit val FieldNameT: Type[hearth.kindlings.reactivemongobsonderivation.annotations.FieldName] =
      Types.fieldNameAnn
    getAnnotationStringArg[hearth.kindlings.reactivemongobsonderivation.annotations.FieldName](param) match {
      case Some(name) => Expr(name)
      case None       =>
        evaluatedConfig match {
          case Some(value) => Expr(value.fieldNameMapper(fieldName))
          case None        =>
            val fieldNameExpr = Expr(fieldName)
            Expr.quote(Expr.splice(config).fieldNameMapper(Expr.splice(fieldNameExpr)))
        }
    }
  }

  private def directionalDefaultExpr[A: Type](param: Parameter): Option[Expr[A]] = {
    val scalaDefault =
      if (param.hasDefault)
        param.defaultValue.flatMap { method =>
          foldInstanceFree(method, "Default value")(
            onTypes = _ => Map.empty,
            onValues = _ => Map.empty
          ).toOption.map(_.value.asInstanceOf[Expr[A]])
        }
      else None
    val annotationDefault = {
      val annotationType = Type.of[hearth.kindlings.reactivemongobsonderivation.annotations.DefaultValue[A]]
      if (
        hasAnnotationType[hearth.kindlings.reactivemongobsonderivation.annotations.DefaultValue[A]](param)(
          annotationType
        )
      )
        getAnnotationValueUntyped(param)(annotationType).map { value =>
          Expr.quote(Expr.splice(value.asTyped[A]).asInstanceOf[A])
        }
      else None
    }
    scalaDefault.orElse(annotationDefault)
  }

  /** Try to extract the underlying String from an Expr[String] if it's a literal. */
  protected def extractStringLiteral(expr: Expr[String]): Option[String] = expr.value

  /** Builds a regular quoted lambda. LambdaBuilder is deliberately reserved for collection iteration helpers. */
  protected def directLambda[A: Type, B: Type](body: Expr[A] => Expr[B]): Expr[A => B] =
    Expr.quote((value: A) => Expr.splice(body(Expr.quote(value))))

  /** Build an expression that checks for unexpected fields in the BSON document.
    *
    * If all known keys are compile-time string literals and `skipUnexpectedFields=false`, we pre-compute the known set
    * at compile time. Otherwise, we fall back to a no-op (skipUnexpectedFields=true is the safe default).
    */
  protected def buildUnexpectedFieldsCheck[A](
      docExpr: Expr[reactivemongo.api.bson.BSONDocument],
      knownKeyExprs: List[Expr[String]],
      ctx: DerivationCtx[A]
  )(implicit StringT: Type[String]): Expr[scala.util.Try[Unit]] =
    buildUnexpectedFieldsCheck(docExpr, knownKeyExprs, ctx.config, ctx.evaluatedConfig)

  private def buildUnexpectedFieldsCheck(
      docExpr: Expr[reactivemongo.api.bson.BSONDocument],
      knownKeyExprs: List[Expr[String]],
      config: Expr[BsonDocumentHandlerConfig],
      evaluatedConfig: Option[BsonDocumentHandlerConfig]
  )(implicit StringT: Type[String]): Expr[scala.util.Try[Unit]] = {
    val allLiteralKeys: Option[Set[String]] =
      knownKeyExprs.foldLeft(Option(Set.empty[String])) { (acc, expr) =>
        acc.flatMap(s => extractStringLiteral(expr).map(s + _))
      }
    evaluatedConfig match {
      case Some(evalCfg) if evalCfg.skipUnexpectedFields =>
        // Config fully evaluated at compile time and skipping is enabled - no-op
        Expr.quote(scala.util.Success(()): scala.util.Try[Unit])
      case _ if allLiteralKeys.isDefined =>
        // All known keys are compile-time literals - pre-compute the set
        val knownKeys = allLiteralKeys.get
        val knownKeysExpr: Expr[scala.collection.immutable.Set[String]] = Expr(knownKeys)
        // Check the config at runtime to decide whether to skip
        Expr.quote {
          if (Expr.splice(config).skipUnexpectedFields)
            scala.util.Success(()): scala.util.Try[Unit]
          else {
            val unexpected = Expr.splice(docExpr).elements.map(_.name).toSet.diff(Expr.splice(knownKeysExpr))
            if (unexpected.nonEmpty)
              scala.util.Failure(
                new IllegalArgumentException("Unexpected field(s): " + unexpected.mkString(", "))
              ): scala.util.Try[Unit]
            else
              scala.util.Success(()): scala.util.Try[Unit]
          }
        }
      case _ =>
        // Some keys are runtime expressions - build the set at the macro level
        // First, build the known keys set by folding Expr.quote expressions
        val emptySetExpr: Expr[scala.collection.immutable.Set[String]] =
          Expr(scala.collection.immutable.Set.empty[String])
        val knownKeysSetExpr: Expr[scala.collection.immutable.Set[String]] =
          if (knownKeyExprs.isEmpty) emptySetExpr
          else
            knownKeyExprs.foldLeft(emptySetExpr) { (acc, keyExpr) =>
              Expr.quote(Expr.splice(acc) + Expr.splice(keyExpr))
            }
        // Check the config at runtime to decide whether to skip
        Expr.quote {
          if (Expr.splice(config).skipUnexpectedFields)
            scala.util.Success(()): scala.util.Try[Unit]
          else {
            val known = Expr.splice(knownKeysSetExpr)
            val unexpected = Expr.splice(docExpr).elements.map(_.name).toSet.diff(known)
            if (unexpected.nonEmpty)
              scala.util.Failure(
                new IllegalArgumentException("Unexpected field(s): " + unexpected.mkString(", "))
              ): scala.util.Try[Unit]
            else
              scala.util.Success(()): scala.util.Try[Unit]
          }
        }
    }
  }

  private def buildUnexpectedFieldsCheck[A](
      docExpr: Expr[reactivemongo.api.bson.BSONDocument],
      knownKeyExprs: List[Expr[String]],
      ctx: ReaderCtx[A]
  )(implicit StringT: Type[String]): Expr[scala.util.Try[Unit]] =
    buildUnexpectedFieldsCheck(docExpr, knownKeyExprs, ctx.config, ctx.evaluatedConfig)

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

  private def deriveReaderBody[A: Type](readerCtx: ReaderCtx[A]): MIO[Unit] = {
    implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
    implicit val TryAT: Type[Try[A]] = Types.TryCtor[A]
    readerCtx.cache.get1Ary[BSONDocument, Try[A]]("cached-reader-body").flatMap {
      case Some(_) => MIO.pure(())
      case None    =>
        Enum.parse[A].toEither match {
          case Right(enumm) => deriveEnumReaderBody[A](enumm, readerCtx)
          case Left(_)      => deriveRecordReaderBody[A](readerCtx)
        }
    }
  }

  private def deriveRecordReaderBody[A: Type](readerCtx: ReaderCtx[A]): MIO[Unit] = {
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
          directionalRecordConstructor[A] match {
            case Left(reason)       => MIO.fail(new Exception(reason))
            case Right(constructor) =>
              val fields = constructor.parameters.flatten.toList
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

  private def deriveEnumReaderBody[A: Type](enumm: Enum[A], readerCtx: ReaderCtx[A]): MIO[Unit] = {
    val children = enumm.exhaustiveChildren.fold(enumm.directChildren.toList)(_.toList)
    if (children.isEmpty)
      MIO.fail(BsonDocumentHandlerDerivationError.NoChildrenInSealedTrait(Type[A].prettyPrint))
    else {
      implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
      implicit val TryAT: Type[Try[A]] = Types.TryCtor[A]
      val key = "cached-reader-body"
      val builder = ValDefBuilder.ofDef1[BSONDocument, Try[A]](s"read_${Type[A].shortName}", "document")
      val discriminatorField = directionalDiscriminatorField(readerCtx.config, readerCtx.evaluatedConfig)
      val knownNames = Expr(
        children
          .map { case (_, child) =>
            import child.Underlying as Child
            Type[Child].shortName
          }
          .mkString(", ")
      )
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
                        Expr.splice(document).get(Expr.splice(discriminatorField)) match {
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
                              Expr.splice(document).get(Expr.splice(discriminatorField)) match {
                                case Some(reactivemongo.api.bson.BSONString(value))
                                    if value == Expr.splice(discriminator) =>
                                  Expr
                                    .splice(call(Expr.quote(Expr.splice(document) -- Expr.splice(discriminatorField))))
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
                          Expr.splice(document).get(Expr.splice(discriminatorField)).getOrElse("<none>") +
                          ". Expected one of: " + Expr.splice(knownNames)
                      )
                    ): Try[A]
                  }
                  dispatchers.toList.foldRight(failure)((dispatch, fallback) => dispatch(document, fallback))
                }
              }
      } yield ()
    }
  }

  private def directionalDiscriminatorField(
      config: Expr[BsonDocumentHandlerConfig],
      evaluatedConfig: Option[BsonDocumentHandlerConfig]
  ): Expr[String] = evaluatedConfig
    .flatMap(_.discriminatorFieldName)
    .fold {
      Expr.quote(Expr.splice(config).discriminatorFieldName.getOrElse("className"))
    }(Expr(_))

  private def directionalDiscriminator[A: Type](
      config: Expr[BsonDocumentHandlerConfig],
      evaluatedConfig: Option[BsonDocumentHandlerConfig]
  ): Expr[String] = evaluatedConfig.map(_.typeNaming) match {
    case Some(TypeNaming.SimpleName) => Expr(Type[A].shortName)
    case Some(TypeNaming.FullName)   => Expr(fullNameOf[A])
    case _                           =>
      val simple = Expr(Type[A].shortName)
      val full = Expr(fullNameOf[A])
      Expr.quote(Expr.splice(config).typeNaming(Expr.splice(simple), Expr.splice(full)))
  }

  private def directionalRecordConstructor[A: Type]: Either[String, Method] =
    CaseClass.parse[A].toEither match {
      case Right(caseClass)      => Right(caseClass.primaryConstructor)
      case Left(caseClassReason) =>
        NamedTuple.parse[A].toEither match {
          case Right(namedTuple)      => Right(namedTuple.primaryConstructor)
          case Left(namedTupleReason) =>
            Left(
              s"${Type[A].prettyPrint} is neither a case class nor a named tuple: $caseClassReason; $namedTupleReason"
            )
        }
    }

  private def isDirectionalFlattened(parameter: Parameter): Boolean = {
    implicit val FlattenT: Type[hearth.kindlings.reactivemongobsonderivation.annotations.Flatten] = Types.flattenAnn
    hasAnnotationType[hearth.kindlings.reactivemongobsonderivation.annotations.Flatten](parameter)
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

  private def isDirectionalRecord[A: Type]: Boolean = directionalRecordConstructor[A].isRight

  final case class ReaderCtx[A](
      tpe: Type[A],
      cache: MLocal[ValDefsCache],
      config: Expr[BsonDocumentHandlerConfig],
      evaluatedConfig: Option[BsonDocumentHandlerConfig],
      flattenStack: List[String]
  ) {
    def nest[B: Type]: ReaderCtx[B] = ReaderCtx(Type[B], cache, config, evaluatedConfig, flattenStack)
    def nestFlattened[B: Type]: ReaderCtx[B] =
      ReaderCtx(Type[B], cache, config, evaluatedConfig, tpe.prettyPrint :: flattenStack)
  }

  object ReaderCtx {
    def from[A: Type](
        config: Expr[BsonDocumentHandlerConfig],
        evaluatedConfig: Option[BsonDocumentHandlerConfig]
    ): ReaderCtx[A] = ReaderCtx(Type[A], ValDefsCache.mlocal, config, evaluatedConfig, Nil)
  }

  private def resolveDirectionalReader[A: Type](
      readerCtx: ReaderCtx[A]
  ): MIO[Expr[reactivemongo.api.bson.BSONReader[A]]] = {
    implicit val ReaderA: Type[reactivemongo.api.bson.BSONReader[A]] = Types.BsonReader[A]
    implicit val TryAT: Type[Try[A]] = Types.TryCtor[A]
    Type[reactivemongo.api.bson.BSONReader[A]]
      .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
      .toEither match {
      case Right(reader)                     => MIO.pure(reader)
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

  private def isDirectionalValueType[A: Type]: Boolean =
    !Type[A].isNamedTuple && (Type[A] match {
      case IsValueType(_) => true
      case _              => false
    })

  private def isDirectionalOption[A: Type]: Boolean = Type[A] match {
    case IsOption(_) => true
    case _           => false
  }

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

  private def deriveWriterBody[A: Type](writerCtx: WriterCtx[A]): MIO[Unit] = {
    implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
    implicit val TryDocumentT: Type[Try[BSONDocument]] = Types.TryCtor[BSONDocument]
    writerCtx.cache.get1Ary[A, Try[BSONDocument]]("cached-writer-body").flatMap {
      case Some(_) => MIO.pure(())
      case None    =>
        Enum.parse[A].toEither match {
          case Right(enumm) => deriveEnumWriterBody[A](enumm, writerCtx)
          case Left(_)      => deriveRecordWriterBody[A](writerCtx)
        }
    }
  }

  private def deriveRecordWriterBody[A: Type](writerCtx: WriterCtx[A]): MIO[Unit] = {
    implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
    implicit val TryDocumentT: Type[Try[BSONDocument]] = Types.TryCtor[BSONDocument]
    val key = "cached-writer-body"
    val builder = ValDefBuilder.ofDef1[A, Try[BSONDocument]](s"write_${Type[A].shortName}", "value")
    for {
      state <- writerCtx.cache.get
      _ <-
        if (builder.isBuilt(state, key)) MIO.pure(())
        else
          directionalRecordConstructor[A] match {
            case Left(reason)       => MIO.fail(new Exception(reason))
            case Right(constructor) =>
              writerCtx.cache.forwardDeclare(key, builder) >> MIO.scoped { runSafe =>
                runSafe(writerCtx.cache.buildCachedWith(key, builder) { case (_, value) =>
                  runSafe {
                    implicit val IgnoreT: Type[hearth.kindlings.reactivemongobsonderivation.annotations.Ignore] =
                      Types.ignoreAnn
                    val parameters = constructor.parameters.flatten.toList
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

  private def deriveEnumWriterBody[A: Type](enumm: Enum[A], writerCtx: WriterCtx[A]): MIO[Unit] = {
    val children = enumm.exhaustiveChildren.fold(enumm.directChildren.toList)(_.toList)
    if (children.isEmpty)
      MIO.fail(BsonDocumentHandlerDerivationError.NoChildrenInSealedTrait(Type[A].prettyPrint))
    else {
      implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
      implicit val TryDocumentT: Type[Try[BSONDocument]] = Types.TryCtor[BSONDocument]
      val key = "cached-writer-body"
      val builder = ValDefBuilder.ofDef1[A, Try[BSONDocument]](s"write_${Type[A].shortName}", "value")
      val discriminatorField = directionalDiscriminatorField(writerCtx.config, writerCtx.evaluatedConfig)
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
                              BSONDocument(Expr.splice(discriminatorField) -> Expr.splice(discriminator))
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
                                      Expr.splice(discriminatorField) -> Expr.splice(discriminator)
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

  final case class WriterCtx[A](
      tpe: Type[A],
      cache: MLocal[ValDefsCache],
      config: Expr[BsonDocumentHandlerConfig],
      evaluatedConfig: Option[BsonDocumentHandlerConfig],
      flattenStack: List[String]
  ) {
    def nest[B: Type]: WriterCtx[B] = WriterCtx(Type[B], cache, config, evaluatedConfig, flattenStack)
    def nestFlattened[B: Type]: WriterCtx[B] =
      WriterCtx(Type[B], cache, config, evaluatedConfig, tpe.prettyPrint :: flattenStack)
  }

  object WriterCtx {
    def from[A: Type](
        config: Expr[BsonDocumentHandlerConfig],
        evaluatedConfig: Option[BsonDocumentHandlerConfig]
    ): WriterCtx[A] = WriterCtx(Type[A], ValDefsCache.mlocal, config, evaluatedConfig, Nil)
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
      case Right(writer)                     => MIO.pure(writer)
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

  /** Inline write entry point. Structural handlers expose their write body as a cached named def, so this expansion
    * calls that def directly rather than allocating a `KindlingsBsonDocumentHandler`.
    */
  def deriveInline[A: Type](
      valueExpr: Expr[A],
      configExpr: Expr[BsonDocumentHandlerConfig]
  ): Expr[Try[BSONDocument]] = {
    implicit val TryBsonDocument: Type[Try[BSONDocument]] = Types.TryBsonDocument
    implicit val ParentHandlerA: Type[reactivemongo.api.bson.BSONDocumentHandler[A]] =
      Types.ExternalBsonDocumentHandler[A]

    Type[reactivemongo.api.bson.BSONDocumentHandler[A]]
      .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
      .toEither match {
      case Right(parent) => Expr.quote(Expr.splice(parent).writeTry(Expr.splice(valueExpr)))
      case Left(_)       =>
        val evaluatedConfig = configExpr.semiEval.toOption
        Log
          .namedScope(s"Deriving inline BSON writer for ${Type[A].prettyPrint}") {
            MIO.scoped { runSafe =>
              val derivationCtx = DerivationCtx.from[A](
                derivedType = None,
                config = configExpr,
                evaluatedConfig = evaluatedConfig,
                writeOnly = true
              )
              runSafe {
                for {
                  _ <- ensureStandardExtensionsLoaded()
                  // Derive the root rule body directly: `deriveResultRecursively` would additionally emit the
                  // handler helper used by `derived`, which is deliberately absent from an inline expansion.
                  _ <- deriveResultRecursivelyViaRules[A](using derivationCtx)
                  writeCaller <- derivationCtx.cache.get1Ary[A, Try[BSONDocument]]("cached-write-body")
                  cache <- derivationCtx.cache.get
                } yield writeCaller match {
                  case Some(call) => cache.toValDefs.use(_ => call(valueExpr))
                  case None       =>
                    Environment.reportErrorAndAbort(
                      s"Inline BSON writing is not available for ${Type[A].prettyPrint}; derive a handler instead"
                    )
                }
              }
            }
          }
          .runToExprOrFail(
            "KindlingsBsonDocumentHandler.write",
            infoRendering = if (shouldWeLogDerivation) RenderFrom(Log.Level.Info) else DontRender,
            errorRendering = if (shouldWeLogDerivation) RenderFrom(Log.Level.Info) else DontRender,
            timeout = derivationTimeout
          ) { (_, errors) =>
            s"Cannot derive inline BSON writer for ${Type[A].prettyPrint}: ${errors.map(_.getMessage).mkString(", ")}"
          }
    }
  }

  def deriveTypeClass[A: Type](
      configExpr: Expr[BsonDocumentHandlerConfig]
  ): Expr[KindlingsBsonDocumentHandler[A]] = {
    val selfType: Option[??] = Some(Type[A].as_??)
    // semiEval now works for common configs because fieldNaming/typeNaming are sealed traits.
    // It falls back to None when the config contains custom function variants (FieldNaming.Custom / TypeNaming.Custom).
    val evaluatedConfig: Option[BsonDocumentHandlerConfig] = configExpr.semiEval.toOption

    if (Type[A] =:= Type.of[Nothing].asInstanceOf[Type[A]] || Type[A] =:= Type.of[Any].asInstanceOf[Type[A]])
      Environment.reportErrorAndAbort(
        s"KindlingsBsonDocumentHandler.derived: type parameter was inferred as ${Type[A].prettyPrint}, which is likely unintended.\n" +
          "Provide an explicit type parameter, e.g.: KindlingsBsonDocumentHandler.derived[MyType]\n" +
          "or add a type ascription to the result variable."
      )

    Log
      .namedScope(
        s"Deriving BSONDocumentHandler for ${Type[A].prettyPrint} at: ${Environment.currentPosition.prettyPrint}"
      ) {
        MIO.scoped { runSafe =>
          val fromCtx: (DerivationCtx[A] => Expr[KindlingsBsonDocumentHandler[A]]) = (ctx: DerivationCtx[A]) =>
            runSafe {
              for {
                _ <- ensureStandardExtensionsLoaded()
                result <- deriveResultRecursively[A](using ctx)
                cache <- ctx.cache.get
              } yield cache.toValDefs.use(_ => result)
            }

          val ctx = DerivationCtx.from[A](
            derivedType = selfType,
            config = configExpr,
            evaluatedConfig = evaluatedConfig
          )
          fromCtx(ctx)
        }
      }
      .flatTap { result =>
        Log.info(s"Derived final result for: ${result.prettyPrint}")
      }
      .runToExprOrFail(
        "KindlingsBsonDocumentHandler.derived",
        infoRendering = if (shouldWeLogDerivation) RenderFrom(Log.Level.Info) else DontRender,
        errorRendering = if (shouldWeLogDerivation) RenderFrom(Log.Level.Info) else DontRender,
        timeout = derivationTimeout
      ) { (errorLogs, errors) =>
        val errorsRendered = errors
          .map { e =>
            e.getMessage.split("\n").toList match {
              case head :: tail => (("  - " + head) :: tail.map("    " + _)).mkString("\n")
              case _            => "  - " + e.getMessage
            }
          }
          .mkString("\n")
        val hint =
          "Enable debug logging with: import hearth.kindlings.reactivemongobsonderivation.debug.logDerivationForBsonDocumentHandler or scalac option -Xmacro-settings:reactivemongoBsonDerivation.logDerivation=true"
        if (errorLogs.nonEmpty)
          s"""Macro derivation failed with the following errors:
             |$errorsRendered
             |and the following logs:
             |$errorLogs
             |$hint""".stripMargin
        else
          s"""Macro derivation failed with the following errors:
             |$errorsRendered
             |$hint""".stripMargin
      }
  }

  def shouldWeLogDerivation: Boolean = {
    implicit val LogDerivation: Type[KindlingsBsonDocumentHandler.LogDerivation] = Types.LogDerivation
    def logDerivationImported = Expr.summonImplicit[KindlingsBsonDocumentHandler.LogDerivation].isDefined

    def logDerivationSetGlobally = (for {
      data <- Environment.typedSettings.toOption
      ns <- data.get("reactivemongoBsonDerivation")
      shouldLog <- ns.get("logDerivation").flatMap(_.asBoolean)
    } yield shouldLog).getOrElse(false)

    logDerivationImported || logDerivationSetGlobally
  }

  // Shared helpers

  def isCaseClassOrEnum[A: Type]: Boolean =
    CaseClass.parse[A].toEither.isRight || Enum.parse[A].toEither.isRight

  /** Reject non-String map keys unless both conversion directions are explicitly available. This must run before
    * summoning a collection reader/writer: ReactiveMongo's broad collection implicits can otherwise defer the missing
    * codec failure until generated code executes.
    */
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
