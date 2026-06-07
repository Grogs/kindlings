package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.MacroCommons
import hearth.fp.data.NonEmptyList
import hearth.fp.effect.*
import hearth.fp.syntax.*
import hearth.std.*

import hearth.kindlings.reactivemongobsonderivation.{BsonDocumentHandlerConfig, KindlingsBsonDocumentHandler}
import reactivemongo.api.bson.BSONDocument

import scala.util.Try

trait BsonDocumentHandlerMacrosImpl
    extends hearth.kindlings.derivation.compiletime.DerivationTimeout
    with hearth.kindlings.derivation.compiletime.LoadStandardExtensionsOnce {
  this: MacroCommons & StdExtensions & AnnotationSupport =>

  override protected def derivationSettingsNamespace: String = "reactivemongoBsonDerivation"

  // Types

  private[compiletime] object Types {
    def BsonDocumentHandler: Type.Ctor1[KindlingsBsonDocumentHandler] = Type.Ctor1.of[KindlingsBsonDocumentHandler]
    val LogDerivation: Type[KindlingsBsonDocumentHandler.LogDerivation] =
      Type.of[KindlingsBsonDocumentHandler.LogDerivation]
    val BsonDocument: Type[BSONDocument] = Type.of[BSONDocument]
    val String: Type[String] = Type.of[String]
    val BsonValue: Type[reactivemongo.api.bson.BSONValue] = Type.of[reactivemongo.api.bson.BSONValue]
    val BsonArray: Type[reactivemongo.api.bson.BSONArray] = Type.of[reactivemongo.api.bson.BSONArray]
    val BsonElement: Type[reactivemongo.api.bson.BSONElement] = Type.of[reactivemongo.api.bson.BSONElement]
    val ArrayAny: Type[Array[Any]] = Type.of[Array[Any]]
    val Any: Type[Any] = Type.of[Any]
    val TryBsonDocument: Type[Try[BSONDocument]] = Type.of[Try[BSONDocument]]
    val BsonReader: Type.Ctor1[reactivemongo.api.bson.BSONReader] = Type.Ctor1.of[reactivemongo.api.bson.BSONReader]
    val BsonWriter: Type.Ctor1[reactivemongo.api.bson.BSONWriter] = Type.Ctor1.of[reactivemongo.api.bson.BSONWriter]
    val TryCtor: Type.Ctor1[Try] = Type.Ctor1.of[Try]
    val fieldNameAnn: Type[hearth.kindlings.reactivemongobsonderivation.annotations.fieldName] =
      Type.of[hearth.kindlings.reactivemongobsonderivation.annotations.fieldName]

    lazy val ignoredAutoDerivationMethods: Seq[UntypedMethod] =
      Type.of[KindlingsBsonDocumentHandler.type].methods.collect {
        case method if method.value.isImplicit => method.value.asUntyped
      }
  }

  // Field name resolution

  /** Build the BSON key expression for a field, applying the `@fieldName` annotation and the config's `fieldNameMapper`
    * (at compile time if available, runtime otherwise).
    */
  private def resolveFieldKeyExpr[A](
      fieldName: String,
      param: Parameter,
      ctx: DerivationCtx[A]
  ): Expr[String] = {
    implicit val fnt: Type[hearth.kindlings.reactivemongobsonderivation.annotations.fieldName] = Types.fieldNameAnn
    val annotationOverride: Option[String] =
      getAnnotationStringArg[hearth.kindlings.reactivemongobsonderivation.annotations.fieldName](param)

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

  /** Try to extract the underlying String from an Expr[String] if it's a literal. */
  private def extractStringLiteral(expr: Expr[String]): Option[String] = expr.value

  /** Build an expression that checks for unexpected fields in the BSON document.
    *
    * If all known keys are compile-time string literals and `skipUnexpectedFields=false`, we pre-compute the known set
    * at compile time. Otherwise, we fall back to a no-op (skipUnexpectedFields=true is the safe default).
    */
  private def buildUnexpectedFieldsCheck[A](
      docExpr: Expr[reactivemongo.api.bson.BSONDocument],
      knownKeyExprs: List[Expr[String]],
      ctx: DerivationCtx[A]
  )(implicit StringT: Type[String]): Expr[scala.util.Try[Unit]] = {
    val allLiteralKeys: Option[Set[String]] =
      knownKeyExprs.foldLeft(Option(Set.empty[String])) { (acc, expr) =>
        acc.flatMap(s => extractStringLiteral(expr).map(s + _))
      }
    ctx.evaluatedConfig match {
      case Some(evalCfg) if evalCfg.skipUnexpectedFields =>
        // Config fully evaluated at compile time and skipping is enabled - no-op
        Expr.quote(scala.util.Success(()): scala.util.Try[Unit])
      case _ if allLiteralKeys.isDefined =>
        // All known keys are compile-time literals - pre-compute the set
        val knownKeys = allLiteralKeys.get
        val knownKeysExpr: Expr[scala.collection.immutable.Set[String]] = Expr(knownKeys)
        // Check the config at runtime to decide whether to skip
        Expr.quote {
          if (Expr.splice(ctx.config).skipUnexpectedFields)
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
          if (Expr.splice(ctx.config).skipUnexpectedFields)
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

  // Entrypoints

  def deriveTypeClass[A: Type](
      configExpr: Expr[BsonDocumentHandlerConfig]
  ): Expr[KindlingsBsonDocumentHandler[A]] = {
    val selfType: Option[??] = Some(Type[A].as_??)
    // semiEval fails on configs with function fields, so don't use it for now
    // TODO: Re-enable once we figure out how to handle function fields in semiEval
    val evaluatedConfig: Option[BsonDocumentHandlerConfig] = None

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

  def resolveBsonReader[A: Type](fieldCtx: DerivationCtx[A]): MIO[Expr[reactivemongo.api.bson.BSONReader[A]]] =
    if (isCaseClassOrEnum[A])
      deriveResultRecursively[A](using fieldCtx)
        .map(_.asInstanceOf[Expr[reactivemongo.api.bson.BSONReader[A]]])
    else {
      implicit val ReaderA: Type[reactivemongo.api.bson.BSONReader[A]] = Types.BsonReader[A]
      Type[reactivemongo.api.bson.BSONReader[A]].summonExprIgnoring().toEither match {
        case Right(reader) => MIO.pure(reader)
        case Left(_)       =>
          val err =
            BsonDocumentHandlerDerivationError.CannotDeriveField(Type[A].prettyPrint, "No BSONReader found")
          Log.error(err.message) >> MIO.fail(err)
      }
    }

  def resolveBsonWriter[A: Type](fieldCtx: DerivationCtx[A]): MIO[Expr[reactivemongo.api.bson.BSONWriter[A]]] =
    if (isCaseClassOrEnum[A])
      deriveResultRecursively[A](using fieldCtx)
        .map(_.asInstanceOf[Expr[reactivemongo.api.bson.BSONWriter[A]]])
    else {
      implicit val WriterA: Type[reactivemongo.api.bson.BSONWriter[A]] = Types.BsonWriter[A]
      Type[reactivemongo.api.bson.BSONWriter[A]].summonExprIgnoring().toEither match {
        case Right(writer) => MIO.pure(writer)
        case Left(_)       =>
          val err =
            BsonDocumentHandlerDerivationError.CannotDeriveField(Type[A].prettyPrint, "No BSONWriter found")
          Log.error(err.message) >> MIO.fail(err)
      }
    }

  // Context

  final case class DerivationCtx[A](
      tpe: Type[A],
      cache: MLocal[ValDefsCache],
      derivedType: Option[??],
      config: Expr[BsonDocumentHandlerConfig],
      evaluatedConfig: Option[BsonDocumentHandlerConfig]
  ) {

    def nest[B: Type]: DerivationCtx[B] = DerivationCtx(
      tpe = Type[B],
      cache = cache,
      derivedType = derivedType,
      config = config,
      evaluatedConfig = evaluatedConfig
    )

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

    def getHelper[B: Type]: MIO[Option[Expr[KindlingsBsonDocumentHandler[B]]]] = {
      implicit val HandlerB: Type[KindlingsBsonDocumentHandler[B]] = Types.BsonDocumentHandler[B]
      cache.get0Ary[KindlingsBsonDocumentHandler[B]]("cached-handler-method")
    }
    def setHelper[B: Type](helper: MIO[Expr[KindlingsBsonDocumentHandler[B]]]): MIO[Unit] = {
      implicit val HandlerB: Type[KindlingsBsonDocumentHandler[B]] = Types.BsonDocumentHandler[B]
      val defBuilder = ValDefBuilder.ofLazy[KindlingsBsonDocumentHandler[B]](s"handler_${Type[B].shortName}")
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

    override def toString: String = s"BSONDocumentHandler[${tpe.prettyPrint}]"
  }

  object DerivationCtx {
    def from[A: Type](
        derivedType: Option[??],
        config: Expr[BsonDocumentHandlerConfig],
        evaluatedConfig: Option[BsonDocumentHandlerConfig]
    ): DerivationCtx[A] =
      DerivationCtx(
        tpe = Type[A],
        cache = ValDefsCache.mlocal,
        derivedType = derivedType,
        config = config,
        evaluatedConfig = evaluatedConfig
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
        Log.info(s"Using cached helper for ${Type[A].prettyPrint}") >> MIO.pure(helperCall)
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
                    case Some(helperCall) => MIO.pure(helperCall)
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

  private def deriveResultRecursivelyViaRules[A: DerivationCtx]: MIO[Expr[KindlingsBsonDocumentHandler[A]]] =
    Log.namedScope(s"Deriving BSONDocumentHandler for type ${Type[A].prettyPrint}") {
      Rules(
        UseImplicitWhenAvailableRule,
        HandleAsValueTypeRule,
        HandleAsCollectionRule,
        HandleAsOptionRule,
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
      Log.info(s"Attempting to summon implicit BSONDocumentHandler[${Type[A].prettyPrint}]") >> {
        Type[KindlingsBsonDocumentHandler[A]]
          .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
          .toEither match {
          case Right(instance) =>
            Log.info(s"Found implicit BSONDocumentHandler[${Type[A].prettyPrint}]") >>
              ctx.setInstance[A](instance) >> MIO.pure(Rule.matched(instance))
          case Left(reason) =>
            MIO.pure(Rule.yielded(s"No implicit BSONDocumentHandler found: $reason"))
        }
      }
    }
  }

  object HandleAsValueTypeRule extends DerivationRule("handle as value type") {
    def apply[A: DerivationCtx]: MIO[Rule.Applicability[Expr[KindlingsBsonDocumentHandler[A]]]] =
      if (Type[A].isNamedTuple)
        MIO.pure(Rule.yielded(s"${Type[A].prettyPrint} is a named tuple"))
      else
        Type[A] match {
          case IsValueType(isValueType) =>
            import isValueType.Underlying as Inner
            Log.info(s"Handling ${Type[A].prettyPrint} as value type wrapping ${Type[Inner].prettyPrint}") >>
              LambdaBuilder
                .of1[Inner]("inner")
                .traverse { innerExpr =>
                  isValueType.value.wrap match {
                    case _: CtorLikeOf.PlainValue[?, ?] =>
                      MIO.pure(isValueType.value.wrap.apply(innerExpr).asInstanceOf[Expr[A]])
                    case _: CtorLikeOf.EitherStringOrValue[?, ?] =>
                      val wrapResult = isValueType.value.wrap.apply(innerExpr).asInstanceOf[Expr[Either[String, A]]]
                      MIO.pure(Expr.quote {
                        Expr.splice(wrapResult) match {
                          case scala.Right(v)  => v
                          case scala.Left(msg) => throw new IllegalArgumentException(msg)
                        }
                      })
                    case _ =>
                      MIO.pure(isValueType.value.wrap.apply(innerExpr).asInstanceOf[Expr[A]])
                  }
                }
                .flatMap { wrapBuilder =>
                  val wrapLambda = wrapBuilder.build[A]
                  // Build an unwrap lambda at compile time (same pattern as wrap lambda)
                  // This avoids any runtime asInstanceOf - the lambda accesses .value directly
                  val unwrapFn: Expr[A] => Expr[Inner] = isValueType.value.unwrap
                  LambdaBuilder
                    .of1[A]("value")
                    .traverse(valueExpr => MIO.pure(unwrapFn(valueExpr)))
                    .map(_.build[Inner])
                    .flatMap { unwrapLambda =>
                      // Try to summon BSONReader/BSONWriter for the inner type first
                      val readerWriterMIO = {
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
                      readerWriterMIO.map { case (innerReaderExpr, innerWriterExpr) =>
                        Expr.quote {
                          hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories
                            .handlerInstance[A](
                              (doc: BSONDocument) =>
                                Expr
                                  .splice(innerReaderExpr)
                                  .readTry(doc.get("value").getOrElse(reactivemongo.api.bson.BSONNull))
                                  .map(innerValue => Expr.splice(wrapLambda).apply(innerValue)),
                              (value: A) =>
                                Expr
                                  .splice(innerWriterExpr)
                                  .writeTry(Expr.splice(unwrapLambda)(value))
                                  .map(bsonValue => BSONDocument("value" -> bsonValue))
                            )
                        }
                      }
                    }
                }
                .map(Rule.matched)
          case _ =>
            MIO.pure(Rule.yielded(s"${Type[A].prettyPrint} is not a value type"))
        }
  }

  object HandleAsCollectionRule extends DerivationRule("handle as collection") {
    def apply[A: DerivationCtx]: MIO[Rule.Applicability[Expr[KindlingsBsonDocumentHandler[A]]]] =
      Log.info(s"Attempting to handle ${Type[A].prettyPrint} as a collection") >> {
        Type[A] match {
          case IsMap(isMap) =>
            import isMap.Underlying as Pair
            Log.info(s"Handling ${Type[A].prettyPrint} as a map") >> deriveMapHandler[A, Pair](isMap.value)
          case IsCollection(isCollection) =>
            import isCollection.Underlying as Item
            Log.info(s"Handling ${Type[A].prettyPrint} as a collection") >> deriveCollectionHandler[A, Item](
              isCollection.value
            )
          case _ =>
            MIO.pure(Rule.yielded(s"${Type[A].prettyPrint} is not a collection or map"))
        }
      }

    private def deriveCollectionHandler[A: DerivationCtx, Item: Type](
        isCollection: IsCollectionOf[A, Item]
    ): MIO[Rule.Applicability[Expr[KindlingsBsonDocumentHandler[A]]]] = {
      implicit val BsonValueT: Type[reactivemongo.api.bson.BSONValue] = Types.BsonValue
      implicit val BsonArrayT: Type[reactivemongo.api.bson.BSONArray] = Types.BsonArray
      implicit val BsonDocumentT: Type[BSONDocument] = Types.BsonDocument
      implicit val TryAT: Type[scala.util.Try[A]] = Types.TryCtor[A]
      implicit val TryBsonDocumentT: Type[scala.util.Try[BSONDocument]] = Types.TryCtor[BSONDocument]

      import isCollection.CtorResult
      val factoryExpr = isCollection.factory
      val buildStep = isCollection.build

      // Resolve item reader/writer using dual-path approach:
      // case class/enum -> derive recursively (cast to reader/writer)
      // other          -> summon BSONReader/BSONWriter directly
      val itemCtx = ctx.nest[Item]
      for {
        itemReaderExpr <- resolveBsonReader[Item](itemCtx)
        itemWriterExpr <- resolveBsonWriter[Item](itemCtx)
        readItemFn <- LambdaBuilder
          .of1[reactivemongo.api.bson.BSONValue]("bsonValue")
          .traverse { bsonValueExpr =>
            MIO.pure(Expr.quote {
              Expr.splice(itemReaderExpr).readTry(Expr.splice(bsonValueExpr)).get
            })
          }
          .map(_.build[Item])
        writeItemFn <- LambdaBuilder
          .of1[Item]("item")
          .traverse { itemExpr =>
            MIO.pure(Expr.quote {
              Expr
                .splice(itemWriterExpr)
                .writeTry(Expr.splice(itemExpr))
                .get
            })
          }
          .map(_.build[reactivemongo.api.bson.BSONValue])
        readLambda <- LambdaBuilder
          .of1[BSONDocument]("doc")
          .traverse { docExpr =>
            val readLoop: Expr[scala.collection.mutable.Builder[Item, CtorResult]] = Expr.quote {
              val readItem = Expr.splice(readItemFn)
              val collBuilder = Expr.splice(factoryExpr).newBuilder
              Expr.splice(docExpr).get("values") match {
                case Some(arr: reactivemongo.api.bson.BSONArray) =>
                  var err: Throwable = null
                  var i = 0
                  val values = arr.values
                  while (err == null && i < values.length) {
                    try collBuilder += readItem(values(i))
                    catch { case e: Exception => err = e }
                    i += 1
                  }
                  if (err != null) throw err
                case _ =>
                  throw new IllegalArgumentException("Expected BSONArray in 'values' field")
              }
              collBuilder
            }
            val buildResultExpr = buildStep.ctor(readLoop)
            collectBuildResult[A](buildStep, buildResultExpr.asInstanceOf[Expr[Any]])
          }
          .map(_.build[scala.util.Try[A]])
        writeLambda <- LambdaBuilder
          .of1[A]("value")
          .traverse { valueExpr =>
            MIO.pure(Expr.quote {
              val writeItem = Expr.splice(writeItemFn)
              val iterable = Expr
                .splice(isCollection.asIterable(valueExpr))
                .asInstanceOf[Iterable[Item]]
              val builder = scala.collection.mutable.ListBuffer.empty[reactivemongo.api.bson.BSONValue]
              var err: Throwable = null
              val iter = iterable.iterator
              while (err == null && iter.hasNext)
                try builder += writeItem(iter.next())
                catch { case e: Exception => err = e }
              if (err != null) scala.util.Failure(err)
              else
                scala.util.Success(BSONDocument("values" -> reactivemongo.api.bson.BSONArray(builder.result())))
            })
          }
          .map(_.build[scala.util.Try[BSONDocument]])
      } yield Rule.matched(Expr.quote {
        hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories
          .handlerInstance[A](
            readFn = Expr.splice(readLambda),
            writeFn = Expr.splice(writeLambda)
          )
      })
    }

    private def deriveMapHandler[A: DerivationCtx, Pair: Type](
        isMap: IsMapOf[A, Pair]
    ): MIO[Rule.Applicability[Expr[KindlingsBsonDocumentHandler[A]]]] = {
      implicit val BsonValueT: Type[reactivemongo.api.bson.BSONValue] = Types.BsonValue
      implicit val BsonDocumentT: Type[BSONDocument] = Types.BsonDocument
      implicit val StringT: Type[String] = Types.String
      implicit val TryAT: Type[scala.util.Try[A]] = Types.TryCtor[A]
      implicit val TryBsonDocumentT: Type[scala.util.Try[BSONDocument]] = Types.TryCtor[BSONDocument]

      import isMap.{Key, Value, CtorResult}
      val factoryExpr = isMap.factory
      val buildStep = isMap.build

      // Resolve value reader/writer using dual-path approach
      val valueCtx = ctx.nest[Value]
      for {
        valueReaderExpr <- resolveBsonReader[Value](valueCtx)
        valueWriterExpr <- resolveBsonWriter[Value](valueCtx)
        readLambda <- LambdaBuilder
          .of1[BSONDocument]("doc")
          .traverse { docExpr =>
            val readLoop: Expr[scala.collection.mutable.Builder[Pair, CtorResult]] = Expr.quote {
              val valueReader = Expr.splice(valueReaderExpr)
              val mapBuilder = Expr.splice(factoryExpr).newBuilder
              var err: Throwable = null
              val iter = Expr.splice(docExpr).elements.iterator
              while (err == null && iter.hasNext) {
                val el = iter.next()
                valueReader.readTry(el.value) match {
                  case scala.util.Success(v) =>
                    mapBuilder += Expr.splice(
                      isMap.pair(
                        Expr.quote(el.name.asInstanceOf[Key]),
                        Expr.quote(v)
                      )
                    )
                  case scala.util.Failure(e) => err = e
                }
              }
              if (err != null) throw err
              mapBuilder
            }
            val buildResultExpr = buildStep.ctor(readLoop)
            collectBuildResult[A](buildStep, buildResultExpr.asInstanceOf[Expr[Any]])
          }
          .map(_.build[scala.util.Try[A]])
        writeLambda <- LambdaBuilder
          .of1[A]("value")
          .traverse { valueExpr =>
            MIO.pure(Expr.quote {
              val valueWriter = Expr.splice(valueWriterExpr)
              val iterable =
                Expr.splice(isMap.asIterable(valueExpr)).asInstanceOf[Iterable[(Key, Value)]]
              val elements = scala.collection.mutable.ListBuffer
                .empty[reactivemongo.api.bson.BSONElement]
              var err: Throwable = null
              val iter = iterable.iterator
              while (err == null && iter.hasNext) {
                val pair = iter.next()
                valueWriter.writeTry(pair._2) match {
                  case scala.util.Success(bson) =>
                    elements += reactivemongo.api.bson
                      .BSONElement(pair._1.asInstanceOf[String], bson.asInstanceOf[reactivemongo.api.bson.BSONValue])
                  case scala.util.Failure(e) => err = e
                }
              }
              if (err != null) scala.util.Failure(err)
              else scala.util.Success(BSONDocument(elements.result()*))
            })
          }
          .map(_.build[scala.util.Try[BSONDocument]])
      } yield Rule.matched(Expr.quote {
        hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories
          .handlerInstance[A](
            readFn = Expr.splice(readLambda),
            writeFn = Expr.splice(writeLambda)
          )
      })
    }

    /** Converts a CtorResult expression to a Try[A] expression based on the build step variant. */
    private def collectBuildResult[A: Type](
        buildStep: CtorLikeOf[?, A],
        buildResultExpr: Expr[Any]
    )(implicit TryAT: Type[scala.util.Try[A]]): MIO[Expr[scala.util.Try[A]]] =
      MIO.pure(buildStep match {
        case _: CtorLikeOf.PlainValue[?, ?] =>
          Expr.quote {
            try scala.util.Success(Expr.splice(buildResultExpr).asInstanceOf[A])
            catch { case e: Throwable => scala.util.Failure(e) }
          }
        case _: CtorLikeOf.EitherStringOrValue[?, ?] =>
          val eitherExpr = buildResultExpr.asInstanceOf[Expr[Either[String, A]]]
          Expr.quote {
            try
              Expr.splice(eitherExpr) match {
                case Right(v)     => scala.util.Success(v)
                case Left(errMsg) => scala.util.Failure(new IllegalArgumentException(errMsg))
              }
            catch { case e: Throwable => scala.util.Failure(e) }
          }
        case _: CtorLikeOf.EitherThrowableOrValue[?, ?] =>
          val eitherExpr = buildResultExpr.asInstanceOf[Expr[Either[Throwable, A]]]
          Expr.quote {
            try
              Expr.splice(eitherExpr) match {
                case Right(v)  => scala.util.Success(v)
                case Left(err) => scala.util.Failure(err)
              }
            catch { case e: Throwable => scala.util.Failure(e) }
          }
        case _: CtorLikeOf.EitherIterableStringOrValue[?, ?] =>
          val eitherExpr = buildResultExpr.asInstanceOf[Expr[Either[Iterable[String], A]]]
          Expr.quote {
            try
              Expr.splice(eitherExpr) match {
                case Right(v)   => scala.util.Success(v)
                case Left(errs) => scala.util.Failure(new IllegalArgumentException(errs.mkString(", ")))
              }
            catch { case e: Throwable => scala.util.Failure(e) }
          }
        case _: CtorLikeOf.EitherIterableThrowableOrValue[?, ?] =>
          val eitherExpr = buildResultExpr.asInstanceOf[Expr[Either[Iterable[Throwable], A]]]
          Expr.quote {
            try
              Expr.splice(eitherExpr) match {
                case Right(v)   => scala.util.Success(v)
                case Left(errs) => scala.util.Failure(errs.headOption.getOrElse(new RuntimeException("unknown")))
              }
            catch { case e: Throwable => scala.util.Failure(e) }
          }
      })
  }

  object HandleAsOptionRule extends DerivationRule("handle as Option") {
    def apply[A: DerivationCtx]: MIO[Rule.Applicability[Expr[KindlingsBsonDocumentHandler[A]]]] =
      Log.info(s"Attempting to handle ${Type[A].prettyPrint} as Option") >> {
        Type[A] match {
          case IsOption(isOption) =>
            import isOption.Underlying as Inner
            val innerCtx = ctx.nest[Inner]
            deriveResultRecursively[Inner](using innerCtx).flatMap { innerHandlerExpr =>
              val handlerExpr = Expr.quote {
                hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories
                  .handlerInstance[A](
                    (doc: BSONDocument) =>
                      doc.get("value") match {
                        case None | Some(reactivemongo.api.bson.BSONNull) =>
                          scala.util.Success(None.asInstanceOf[A])
                        case Some(v) =>
                          Expr
                            .splice(innerHandlerExpr)
                            .asInstanceOf[reactivemongo.api.bson.BSONReader[Inner]]
                            .readTry(v)
                            .map(_.asInstanceOf[A])
                      },
                    (value: A) =>
                      value match {
                        case Some(v) =>
                          Expr
                            .splice(innerHandlerExpr)
                            .asInstanceOf[reactivemongo.api.bson.BSONWriter[Inner]]
                            .writeTry(v.asInstanceOf[Inner])
                            .map { bsv =>
                              BSONDocument("value" -> bsv)
                            }
                        case None => scala.util.Success(BSONDocument.empty)
                      }
                  )
              }
              MIO.pure(Rule.matched(handlerExpr))
            }
          case _ => MIO.pure(Rule.yielded(s"${Type[A].prettyPrint} is not an Option"))
        }
      }
  }

  object HandleAsCaseClassRule extends DerivationRule("handle as case class") {
    def apply[A: DerivationCtx]: MIO[Rule.Applicability[Expr[KindlingsBsonDocumentHandler[A]]]] =
      Log.info(s"Attempting to handle ${Type[A].prettyPrint} as a case class") >> {
        CaseClass.parse[A].toEither match {
          case Right(caseClass) => deriveCaseClass[A](caseClass).map(Rule.matched)
          case Left(reason)     => MIO.pure(Rule.yielded(reason))
        }
      }

    private def resolveFieldReader[F: Type](
        fieldCtx: DerivationCtx[F]
    ): MIO[Expr[reactivemongo.api.bson.BSONReader[F]]] =
      BsonDocumentHandlerMacrosImpl.this.resolveBsonReader[F](fieldCtx)

    private def resolveFieldWriter[F: Type](
        fieldCtx: DerivationCtx[F]
    ): MIO[Expr[reactivemongo.api.bson.BSONWriter[F]]] =
      BsonDocumentHandlerMacrosImpl.this.resolveBsonWriter[F](fieldCtx)

    private def buildFieldReadExpr[Field: Type](
        docExpr: Expr[BSONDocument],
        fName: String,
        param: Parameter,
        fieldCtx: DerivationCtx[Field]
    ): MIO[Expr[scala.util.Try[Any]]] = {
      val fNameExpr: Expr[String] = resolveFieldKeyExpr(fName, param, fieldCtx)

      Type[Field] match {
        case IsOption(isOption) =>
          import isOption.Underlying as Inner
          val innerCtx = fieldCtx.copy(tpe = Type[Inner])
          resolveFieldReader[Inner](innerCtx).map { innerReaderExpr =>
            val defaultExprOpt: Option[Expr[Field]] =
              if (param.hasDefault) param.defaultValue.flatMap { existentialOuter =>
                val methodOf = existentialOuter.value
                methodOf.value match {
                  case noInstance: Method.NoInstance[?] =>
                    import noInstance.Returned; noInstance(Map.empty).toOption.map(_.asInstanceOf[Expr[Field]])
                  case _ => None
                }
              }
              else None
            val readCode = Expr.quote {
              Expr.splice(docExpr).get(Expr.splice(fNameExpr)) match {
                case Some(v) if !v.isInstanceOf[reactivemongo.api.bson.BSONNull] =>
                  Expr.splice(innerReaderExpr).readTry(v).map(Some(_))
                case _ => scala.util.Success(None)
              }
            }
            defaultExprOpt match {
              case Some(defaultExpr) =>
                Expr
                  .quote {
                    Expr.splice(docExpr).get(Expr.splice(fNameExpr)) match {
                      case None => scala.util.Success(Expr.splice(defaultExpr))
                      case _    => Expr.splice(readCode)
                    }
                  }
                  .asInstanceOf[Expr[scala.util.Try[Any]]]
              case None => readCode.asInstanceOf[Expr[scala.util.Try[Any]]]
            }
          }
        case _ =>
          resolveFieldReader[Field](fieldCtx).map { readerExpr =>
            val defaultExprOpt: Option[Expr[Field]] =
              if (param.hasDefault) param.defaultValue.flatMap { existentialOuter =>
                val methodOf = existentialOuter.value
                methodOf.value match {
                  case noInstance: Method.NoInstance[?] =>
                    import noInstance.Returned; noInstance(Map.empty).toOption.map(_.asInstanceOf[Expr[Field]])
                  case _ => None
                }
              }
              else None
            defaultExprOpt match {
              case Some(defaultExpr) =>
                Expr.quote {
                  Expr.splice(docExpr).get(Expr.splice(fNameExpr)) match {
                    case Some(v) if !v.isInstanceOf[reactivemongo.api.bson.BSONNull] =>
                      Expr.splice(readerExpr).readTry(v).asInstanceOf[scala.util.Try[Any]]
                    case None => scala.util.Success(Expr.splice(defaultExpr)).asInstanceOf[scala.util.Try[Any]]
                    case _    =>
                      scala.util.Failure(new NoSuchElementException("Field is null")).asInstanceOf[scala.util.Try[Any]]
                  }
                }
              case None =>
                Expr.quote {
                  Expr.splice(docExpr).get(Expr.splice(fNameExpr)) match {
                    case Some(v) if !v.isInstanceOf[reactivemongo.api.bson.BSONNull] =>
                      Expr.splice(readerExpr).readTry(v).asInstanceOf[scala.util.Try[Any]]
                    case _ =>
                      scala.util
                        .Failure(new NoSuchElementException("Field not found"))
                        .asInstanceOf[scala.util.Try[Any]]
                  }
                }
            }
          }
      }
    }

    private def buildFieldWriteExpr[Field: Type](
        fName: String,
        param: Parameter,
        fieldValue: Expr[Field],
        fieldCtx: DerivationCtx[Field]
    ): MIO[Expr[scala.util.Try[Option[reactivemongo.api.bson.BSONElement]]]] = {
      val fNameExpr: Expr[String] = resolveFieldKeyExpr(fName, param, fieldCtx)

      Type[Field] match {
        case IsOption(isOption) =>
          import isOption.Underlying as Inner
          val innerCtx = fieldCtx.copy(tpe = Type[Inner])
          resolveFieldWriter[Inner](innerCtx).map { innerWriterExpr =>
            Expr.quote {
              Expr.splice(fieldValue) match {
                case Some(v) =>
                  Expr.splice(innerWriterExpr).writeTry(v.asInstanceOf[Inner]).map { bsv =>
                    Some(reactivemongo.api.bson.BSONElement(Expr.splice(fNameExpr), bsv))
                  }
                case None => scala.util.Success(None)
              }
            }
          }
        case _ =>
          resolveFieldWriter[Field](fieldCtx).map { writerExpr =>
            Expr.quote {
              Expr.splice(writerExpr).writeTry(Expr.splice(fieldValue)).map { bsv =>
                Some(reactivemongo.api.bson.BSONElement(Expr.splice(fNameExpr), bsv))
              }
            }
          }
      }
    }

    private def deriveCaseClass[A: DerivationCtx](
        caseClass: CaseClass[A]
    ): MIO[Expr[KindlingsBsonDocumentHandler[A]]] = {
      implicit val BsonDocumentT: Type[BSONDocument] = Types.BsonDocument
      implicit val ArrayAnyT: Type[Array[Any]] = Types.ArrayAny
      implicit val TryAT: Type[Try[A]] = Types.TryCtor[A]
      implicit val TryBsonDocumentT: Type[Try[BSONDocument]] = Types.TryCtor[BSONDocument]

      val constructor = caseClass.primaryConstructor
      val fieldsList = constructor.parameters.flatten.toList

      if (fieldsList.isEmpty) {
        caseClass
          .construct[MIO](new CaseClass.ConstructField[MIO] {
            def apply(field: Parameter): MIO[Expr[field.tpe.Underlying]] = {
              val err = BsonDocumentHandlerDerivationError
                .CannotConstructType(Type[A].prettyPrint, Some("Unexpected parameter"))
              Log.error(err.message) >> MIO.fail(err)
            }
          })
          .flatMap {
            case Some(constructExpr) =>
              MIO.pure(Expr.quote {
                hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories
                  .handlerInstance[A](
                    readFn = { _ => scala.util.Success(Expr.splice(constructExpr)) },
                    writeFn = { _ => scala.util.Success(BSONDocument.empty) }
                  )
              })
            case None =>
              val err = BsonDocumentHandlerDerivationError.CannotConstructType(Type[A].prettyPrint, None)
              Log.error(err.message) >> MIO.fail(err)
          }
      } else {
        val fieldsNel = NonEmptyList(fieldsList.head, fieldsList.tail)
        for {
          readLambda <- LambdaBuilder
            .of1[BSONDocument]("doc")
            .traverse { docExpr =>
              for {
                fieldReads <- fieldsNel.parTraverse { case (fName, param) =>
                  import param.tpe.Underlying as Field
                  buildFieldReadExpr[Field](docExpr, fName, param, ctx.nest[Field])
                }
                constructLambda <- LambdaBuilder
                  .of1[Array[Any]]("arr")
                  .traverse { arrExpr =>
                    val fieldMap: Map[String, Expr_??] = fieldsList.zipWithIndex.map { case ((name, param), idx) =>
                      import param.tpe.Underlying as Field
                      val fieldExpr: Expr[Field] =
                        Expr.quote(Expr.splice(arrExpr)(Expr.splice(Expr(idx))).asInstanceOf[Field])
                      (name, fieldExpr.as_??)
                    }.toMap
                    caseClass.primaryConstructor(fieldMap) match {
                      case Right(ce)   => MIO.pure(ce.asInstanceOf[Expr[A]])
                      case Left(error) =>
                        val err =
                          BsonDocumentHandlerDerivationError.CannotConstructType(Type[A].prettyPrint, Some(error))
                        Log.error(err.message) >> MIO.fail(err)
                    }
                  }
                  .map(_.build[A])
              } yield {
                implicit val StringT: Type[String] = Types.String
                val listExpr = fieldReads.toList.foldRight(Expr.quote(List.empty[Try[Any]])) { case (read, acc) =>
                  Expr.quote(Expr.splice(read) :: Expr.splice(acc))
                }
                // Build the unexpected fields check (no-op when skipUnexpectedFields=true or all keys are literals)
                val knownKeyExprs: List[Expr[String]] = fieldsList.map { case (fName, param) =>
                  resolveFieldKeyExpr(fName, param, ctx)
                }
                val unexpectedCheckExpr: Expr[scala.util.Try[Unit]] = buildUnexpectedFieldsCheck(
                  docExpr,
                  knownKeyExprs,
                  ctx
                )
                Expr.quote {
                  hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories
                    .sequenceTries[A](
                      Expr.splice(listExpr),
                      Expr.splice(constructLambda)
                    )
                    .flatMap(a => Expr.splice(unexpectedCheckExpr).map(_ => a))
                }
              }
            }
            .map(_.build[Try[A]])

          writeLambda <- LambdaBuilder
            .of1[A]("value")
            .traverse { valueExpr =>
              val fieldValues = caseClass.caseFieldValuesAt(valueExpr).toList
              val fieldValuesNel = NonEmptyList(fieldValues.head, fieldValues.tail)
              fieldValuesNel
                .parTraverse { case (fName, fieldValue) =>
                  import fieldValue.Underlying as Field
                  val param = fieldsList.find(_._1 == fName).get._2
                  buildFieldWriteExpr[Field](fName, param, fieldValue.value.asInstanceOf[Expr[Field]], ctx.nest[Field])
                }
                .map { etries =>
                  val listTryExpr = etries.toList.foldRight(
                    Expr.quote(
                      scala.util.Success(List.empty[Option[reactivemongo.api.bson.BSONElement]]): Try[
                        List[Option[reactivemongo.api.bson.BSONElement]]
                      ]
                    )
                  ) { case (et, acc) =>
                    Expr.quote(for { tail <- Expr.splice(acc); head <- Expr.splice(et) } yield head :: tail)
                  }
                  Expr.quote(Expr.splice(listTryExpr).map(options => BSONDocument(options.flatten*)))
                }
            }
            .map(_.build[Try[BSONDocument]])
        } yield Expr.quote {
          hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories.handlerInstance[A](
            readFn = Expr.splice(readLambda),
            writeFn = Expr.splice(writeLambda)
          )
        }
      }
    }
  }

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
      implicit val StringT: Type[String] = Types.String
      implicit val BsonDocumentT: Type[BSONDocument] = Types.BsonDocument
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

        case Some(childrenNel) =>
          // Extract discriminator field name at compile time if possible, otherwise use default
          // We don't try to splice the config at runtime because it contains function fields
          // that can't be properly serialized in the generated code
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
          val knownNames: String = childrenList.map(_._1).mkString(", ")
          val knownNamesExpr = Expr(knownNames)

          // Derive typed read-dispatch functions for each child
          childrenNel
            .parTraverse { case (caseName, child) =>
              import child.Underlying as ChildType
              deriveChildReadDispatch[A, ChildType](caseName, discriminatorFieldExpr)
            }
            .flatMap { readDispatchersNel =>
              val readDispatchers = readDispatchersNel.toList

              // Build read lambda - fold dispatch chain (reverse so earlier children match first)
              val readLambdaIO =
                LambdaBuilder
                  .of1[BSONDocument]("doc")
                  .traverse { docExpr =>
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
                  .map(_.build[scala.util.Try[A]])

              // Build write lambda via Enum.parMatchOn
              val writeLambdaIO =
                LambdaBuilder
                  .of1[A]("value")
                  .traverse { valueExpr =>
                    enumm
                      .parMatchOn[MIO, scala.util.Try[BSONDocument]](valueExpr) { matched =>
                        import matched.{value as enumCaseValue, Underlying as ChildType}
                        // Find case name by type comparison
                        val caseName: String = childrenList
                          .find { case (_, child) =>
                            import child.Underlying as CT
                            Type[ChildType] =:= Type[CT]
                          }
                          .map(_._1)
                          .getOrElse(Type[ChildType].shortName)
                        Expr.singletonOf[ChildType] match {
                          case Some(_) =>
                            MIO.pure(Expr.quote {
                              scala.util.Success(
                                BSONDocument(Expr.splice(discriminatorFieldExpr) -> Expr.splice(Expr(caseName)))
                              )
                            })
                          case None =>
                            deriveResultRecursively[ChildType](using ctx.nest[ChildType]).map { childHandler =>
                              Expr.quote {
                                val childDoc = Expr.splice(childHandler).writeTry(Expr.splice(enumCaseValue)).get
                                scala.util.Success(
                                  childDoc ++
                                    BSONDocument(Expr.splice(discriminatorFieldExpr) -> Expr.splice(Expr(caseName)))
                                )
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
                  .map(_.build[scala.util.Try[BSONDocument]])

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
        caseName: String,
        discriminatorFieldExpr: Expr[String]
    ): MIO[(Expr[BSONDocument], Expr[scala.util.Try[A]]) => Expr[scala.util.Try[A]]] = {
      val caseNameExpr = Expr(caseName)

      // Check if child is a singleton - return it directly without deriving a handler
      Expr.singletonOf[ChildType] match {
        case Some(singleton) =>
          MIO.pure { (docExpr: Expr[BSONDocument], elseExpr: Expr[scala.util.Try[A]]) =>
            Expr.quote {
              Expr.splice(docExpr).get(Expr.splice(discriminatorFieldExpr)) match {
                case Some(bsv) if bsv == reactivemongo.api.bson.BSONString(Expr.splice(caseNameExpr)) =>
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
                  case Some(bsv) if bsv == reactivemongo.api.bson.BSONString(Expr.splice(caseNameExpr)) =>
                    Expr.splice(childHandler).readDocument(Expr.splice(docExpr)).map(_.asInstanceOf[A])
                  case _ => Expr.splice(elseExpr)
                }
              }
          }
      }
    }
  }
}
