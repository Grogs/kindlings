package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.MacroCommons
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
    with BsonDirectionalCodecSupport
    with BsonDirectionalInfrastructure
    with BsonDirectionalBodyBuilders
    with BsonDirectionalReaderDerivation
    with BsonDirectionalWriterDerivation
    with BsonCombinedHandlerComposition {
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

  protected def resolveDirectionalFieldKey(
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

  protected def directionalDefaultExpr[A: Type](param: Parameter): Option[Expr[A]] = {
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

  protected def buildUnexpectedFieldsCheck[A](
      docExpr: Expr[reactivemongo.api.bson.BSONDocument],
      knownKeyExprs: List[Expr[String]],
      ctx: ReaderCtx[A]
  )(implicit StringT: Type[String]): Expr[scala.util.Try[Unit]] =
    buildUnexpectedFieldsCheck(docExpr, knownKeyExprs, ctx.config, ctx.evaluatedConfig)

  protected def directionalDiscriminatorField(
      config: Expr[BsonDocumentHandlerConfig],
      evaluatedConfig: Option[BsonDocumentHandlerConfig]
  ): Expr[String] = evaluatedConfig
    .flatMap(_.discriminatorFieldName)
    .fold {
      Expr.quote(Expr.splice(config).discriminatorFieldName.getOrElse("className"))
    }(Expr(_))

  protected def directionalDiscriminator[A: Type](
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

  protected def directionalRecordConstructor[A: Type]: Either[String, Method] =
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

  protected def directionalRecordPlan[A: Type]: Either[String, DirectionalRecordPlan] =
    directionalRecordConstructor[A].map(constructor =>
      DirectionalRecordPlan(constructor, constructor.parameters.flatten.toList)
    )

  protected def directionalEnumMetadata[A: Type](
      enumm: Enum[A],
      config: Expr[BsonDocumentHandlerConfig],
      evaluatedConfig: Option[BsonDocumentHandlerConfig]
  ): DirectionalEnumMetadata = {
    val children = enumm.exhaustiveChildren.fold(enumm.directChildren.toList)(_.toList)
    DirectionalEnumMetadata(
      directionalDiscriminatorField(config, evaluatedConfig),
      Expr(
        children
          .map { case (_, child) =>
            import child.Underlying as Child
            Type[Child].shortName
          }
          .mkString(", ")
      )
    )
  }

  protected def isDirectionalFlattened(parameter: Parameter): Boolean = {
    implicit val FlattenT: Type[hearth.kindlings.reactivemongobsonderivation.annotations.Flatten] = Types.flattenAnn
    hasAnnotationType[hearth.kindlings.reactivemongobsonderivation.annotations.Flatten](parameter)
  }

  protected def isDirectionalRecord[A: Type]: Boolean = directionalRecordConstructor[A].isRight
  protected def isDirectionalMap[A: Type]: Boolean = Type[A] match {
    case IsMap(_) => true
    case _        => false
  }

  protected def isDirectionalCollection[A: Type]: Boolean = Type[A] match {
    case IsCollection(_) => true
    case _               => false
  }

  protected def isDirectionalValueType[A: Type]: Boolean =
    !Type[A].isNamedTuple && (Type[A] match {
      case IsValueType(_) => true
      case _              => false
    })

  protected def isDirectionalOption[A: Type]: Boolean = Type[A] match {
    case IsOption(_) => true
    case _           => false
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
              val writerCtx = WriterCtx.from[A](configExpr, evaluatedConfig)
              runSafe {
                for {
                  _ <- ensureStandardExtensionsLoaded()
                  _ <- checkDerivationPolicyOncePerExpansion(Type[A].prettyPrint)
                  _ <- deriveWriterBody[A](writerCtx)
                  writeCaller <- writerCtx.cache.get1Ary[A, Try[BSONDocument]]("cached-writer-body")
                  cache <- writerCtx.cache.get
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
    val evaluatedConfig = configExpr.semiEval.toOption
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
          runSafe {
            for {
              // The standard extensions classify Option, Map, collections, and value types. Do not inspect
              // CaseClass/Enum before this point: Scala's Option can otherwise look like a structural enum.
              _ <- ensureStandardExtensionsLoaded()
              result <- deriveCombinedHandler[A](selfType, configExpr, evaluatedConfig)
            } yield result
          }
        }
      }
      .flatTap(result => Log.info(s"Derived final result for: ${result.prettyPrint}"))
      .runToExprOrFail(
        "KindlingsBsonDocumentHandler.derived",
        infoRendering = if (shouldWeLogDerivation) RenderFrom(Log.Level.Info) else DontRender,
        errorRendering = if (shouldWeLogDerivation) RenderFrom(Log.Level.Info) else DontRender,
        timeout = derivationTimeout
      ) { (errorLogs, errors) =>
        val errorsRendered = errors
          .map { error =>
            error.getMessage.split("\n").toList match {
              case head :: tail => (("  - " + head) :: tail.map("    " + _)).mkString("\n")
              case _            => "  - " + error.getMessage
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

}
