package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.MacroCommons
import hearth.fp.data.NonEmptyList
import hearth.fp.effect.*
import hearth.fp.syntax.*
import hearth.std.*

import hearth.kindlings.reactivemongobsonderivation.{
  BsonDocumentHandlerConfig,
  KindlingsBsonDocumentHandler,
  TypeNaming
}
import reactivemongo.api.bson.BSONDocument

import scala.util.Try

trait BsonDocumentHandlerMacrosImpl
    extends hearth.kindlings.derivation.compiletime.DerivationTimeout
    with hearth.kindlings.derivation.compiletime.LoadStandardExtensionsOnce
    with hearth.kindlings.derivation.compiletime.MethodFolds {
  this: MacroCommons & StdExtensions & AnnotationSupport =>

  override protected def derivationSettingsNamespace: String = "reactivemongoBsonDerivation"

  // Types

  /** Platform-specific way to get the full class name (e.g. `pkg.Outer.Inner`). Implemented in the Scala 3 companion
    * using `quotes.reflect`.
    */
  protected def fullNameOf[A: Type]: String

  private[compiletime] object Types {
    def BsonDocumentHandler: Type.Ctor1[KindlingsBsonDocumentHandler] = Type.Ctor1.of[KindlingsBsonDocumentHandler]
    def ExternalBsonDocumentHandler: Type.Ctor1[reactivemongo.api.bson.BSONDocumentHandler] =
      Type.Ctor1.of[reactivemongo.api.bson.BSONDocumentHandler]
    val LogDerivation: Type[KindlingsBsonDocumentHandler.LogDerivation] =
      Type.of[KindlingsBsonDocumentHandler.LogDerivation]
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
      }
  }

  // Field name resolution

  /** Build the BSON key expression for a field, applying the `@FieldName` annotation and the config's `fieldNameMapper`
    * (at compile time if available, runtime otherwise).
    */
  private def resolveFieldKeyExpr[A](
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
  private def ensureMapKeyCodecs[A: Type](): Unit = Type[A] match {
    case IsMap(isMap) =>
      import isMap.Underlying as Pair
      ensureMapKeyCodecsOf[A, Pair](isMap.value)
    case _ => ()
  }

  private def ensureMapKeyCodecsOf[A: Type, Pair: Type](isMap: IsMapOf[A, Pair]): Unit = {
    import isMap.Key
    implicit val StringT: Type[String] = Types.String
    implicit val KeyReaderT: Type[reactivemongo.api.bson.KeyReader[Key]] = Types.KeyReader[Key]
    implicit val KeyWriterT: Type[reactivemongo.api.bson.KeyWriter[Key]] = Types.KeyWriter[Key]
    val hasKeyReader = Type[reactivemongo.api.bson.KeyReader[Key]]
      .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
      .toOption
      .nonEmpty
    val hasKeyWriter = Type[reactivemongo.api.bson.KeyWriter[Key]]
      .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
      .toOption
      .nonEmpty
    if (!(Type[Key] =:= Type[String]) && !(hasKeyReader && hasKeyWriter)) {
      Environment.reportErrorAndAbort(
        BsonDocumentHandlerDerivationError
          .CannotDeriveCollection(
            Type[A].prettyPrint,
            s"Map key ${Type[Key].prettyPrint} requires both KeyReader and KeyWriter"
          )
          .message
      )
    }
  }

  def resolveBsonReader[A: Type](fieldCtx: DerivationCtx[A]): MIO[Expr[reactivemongo.api.bson.BSONReader[A]]] = {
    ensureMapKeyCodecs[A]()
    implicit val ReaderA: Type[reactivemongo.api.bson.BSONReader[A]] = Types.BsonReader[A]
    @scala.annotation.nowarn("msg=is never used")
    implicit val TryAT: Type[scala.util.Try[A]] = Types.TryCtor[A]
    @scala.annotation.nowarn("msg=is never used")
    implicit val BsonValueT: Type[reactivemongo.api.bson.BSONValue] = Types.BsonValue
    Type[reactivemongo.api.bson.BSONReader[A]]
      .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
      .toEither match {
      case Right(reader) => MIO.pure(reader)
      case Left(_)       =>
        Type[A] match {
          case IsOption(isOption) =>
            import isOption.Underlying as Inner
            resolveBsonReader[Inner](fieldCtx.nest[Inner]).map { innerReaderExpr =>
              Expr.quote {
                new reactivemongo.api.bson.BSONReader[A] {
                  def readTry(bson: reactivemongo.api.bson.BSONValue): scala.util.Try[A] =
                    bson match {
                      case reactivemongo.api.bson.BSONNull =>
                        scala.util.Success(None.asInstanceOf[A])
                      case other =>
                        Expr.splice(innerReaderExpr).readTry(other).map(Some(_).asInstanceOf[A])
                    }
                }
              }
            }
          case IsCollection(isCollection) =>
            import isCollection.Underlying as Item
            deriveInlineCollectionReader[A, Item](isCollection.value, fieldCtx)
          case IsMap(isMap) =>
            import isMap.Underlying as Pair
            deriveInlineMapReader[A, Pair](isMap.value, fieldCtx)
          case _ if isCaseClassOrEnum[A] =>
            deriveResultRecursively[A](using fieldCtx)
              .map(_.asInstanceOf[Expr[reactivemongo.api.bson.BSONReader[A]]])
          case _ =>
            val err =
              BsonDocumentHandlerDerivationError.CannotDeriveField(Type[A].prettyPrint, "No BSONReader found")
            Log.error(err.message) >> MIO.fail(err)
        }
    }
  }

  private def deriveInlineCollectionReader[A: Type, Item: Type](
      isCollection: IsCollectionOf[A, Item],
      fieldCtx: DerivationCtx[A]
  ): MIO[Expr[reactivemongo.api.bson.BSONReader[A]]] = {
    @scala.annotation.nowarn("msg=is never used")
    implicit val TryAT: Type[scala.util.Try[A]] = Types.TryCtor[A]
    @scala.annotation.nowarn("msg=is never used")
    implicit val BsonArrayT: Type[reactivemongo.api.bson.BSONArray] = Types.BsonArray
    @scala.annotation.nowarn("msg=is never used")
    implicit val BsonValueT: Type[reactivemongo.api.bson.BSONValue] = Types.BsonValue
    val factoryExpr = isCollection.factory
    val buildStep = isCollection.build
    resolveBsonReader[Item](fieldCtx.nest[Item]).flatMap { itemReaderExpr =>
      buildStep match {
        case _: CtorLikeOf.PlainValue[?, ?] =>
          LambdaBuilder
            .of1[reactivemongo.api.bson.BSONArray]("arr")
            .traverse { arrExpr =>
              import isCollection.CtorResult
              val readLoop: Expr[scala.collection.mutable.Builder[Item, CtorResult]] = Expr.quote {
                val itemReader = Expr.splice(itemReaderExpr)
                val collBuilder = Expr.splice(factoryExpr).newBuilder
                val values = Expr.splice(arrExpr).values
                var i = 0
                while (i < values.length) {
                  collBuilder += itemReader.readTry(values(i)).get
                  i += 1
                }
                collBuilder
              }
              val buildResultExpr = buildStep.ctor(readLoop)
              // PlainValue guarantees CtorResult = A, so the result is Expr[A]
              MIO.pure(Expr.quote {
                scala.util.Try(Expr.splice(buildResultExpr.asInstanceOf[Expr[A]]))
              })
            }
            .map(_.build[scala.util.Try[A]])
            .map { readFnExpr =>
              Expr.quote {
                new reactivemongo.api.bson.BSONReader[A] {
                  def readTry(bson: reactivemongo.api.bson.BSONValue): scala.util.Try[A] =
                    bson match {
                      case arr: reactivemongo.api.bson.BSONArray =>
                        Expr.splice(readFnExpr).apply(arr)
                      case _ =>
                        scala.util.Failure(
                          new IllegalArgumentException(
                            s"Expected BSONArray for collection, got ${bson.getClass.getSimpleName}"
                          )
                        )
                    }
                }
              }
            }
        case _ =>
          val err = BsonDocumentHandlerDerivationError.CannotDeriveField(
            Type[A].prettyPrint,
            "Collection build step must be PlainValue for inline reading"
          )
          Log.error(err.message) >> MIO.fail(err)
      }
    }
  }

  private def deriveInlineMapReader[A: Type, Pair: Type](
      isMap: IsMapOf[A, Pair],
      fieldCtx: DerivationCtx[A]
  ): MIO[Expr[reactivemongo.api.bson.BSONReader[A]]] = {
    import isMap.{Key, Value}
    @scala.annotation.nowarn("msg=is never used")
    implicit val BsonValueT: Type[reactivemongo.api.bson.BSONValue] = Types.BsonValue
    resolveBsonReader[Value](fieldCtx.nest[Value]).map { valueReaderExpr =>
      Expr.quote {
        new reactivemongo.api.bson.BSONReader[A] {
          def readTry(bson: reactivemongo.api.bson.BSONValue): scala.util.Try[A] =
            bson match {
              case doc: reactivemongo.api.bson.BSONDocument =>
                scala.util.Try {
                  val builder = scala.collection.mutable.Map.newBuilder[Key, Value]
                  val iter = doc.elements.iterator
                  while (iter.hasNext) {
                    val elem = iter.next()
                    builder += (
                      elem.name.asInstanceOf[Key] ->
                        Expr.splice(valueReaderExpr).readTry(elem.value).get
                    )
                  }
                  builder.result().asInstanceOf[A]
                }
              case _ =>
                scala.util.Failure(
                  new IllegalArgumentException(
                    s"Expected BSONDocument for map, got ${bson.getClass.getSimpleName}"
                  )
                )
            }
        }
      }
    }
  }

  def resolveBsonWriter[A: Type](fieldCtx: DerivationCtx[A]): MIO[Expr[reactivemongo.api.bson.BSONWriter[A]]] = {
    ensureMapKeyCodecs[A]()
    implicit val WriterA: Type[reactivemongo.api.bson.BSONWriter[A]] = Types.BsonWriter[A]
    @scala.annotation.nowarn("msg=is never used")
    implicit val BsonValueT: Type[reactivemongo.api.bson.BSONValue] = Types.BsonValue
    @scala.annotation.nowarn("msg=is never used")
    implicit val TryBsonValueT: Type[scala.util.Try[reactivemongo.api.bson.BSONValue]] =
      Types.TryCtor[reactivemongo.api.bson.BSONValue]
    Type[reactivemongo.api.bson.BSONWriter[A]]
      .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
      .toEither match {
      case Right(writer) => MIO.pure(writer)
      case Left(_)       =>
        Type[A] match {
          case IsOption(isOption) =>
            import isOption.Underlying as Inner
            resolveBsonWriter[Inner](fieldCtx.nest[Inner]).map { innerWriterExpr =>
              Expr.quote {
                new reactivemongo.api.bson.BSONWriter[A] {
                  def writeTry(opt: A): scala.util.Try[reactivemongo.api.bson.BSONValue] =
                    opt.asInstanceOf[Option[Inner]] match {
                      case Some(v) => Expr.splice(innerWriterExpr).writeTry(v)
                      case None    => scala.util.Success(reactivemongo.api.bson.BSONNull)
                    }
                }
              }
            }
          case IsCollection(isCollection) =>
            import isCollection.Underlying as Item
            deriveInlineCollectionWriter[A, Item](isCollection.value, fieldCtx)
          case IsMap(isMap) =>
            import isMap.Underlying as Pair
            deriveInlineMapWriter[A, Pair](isMap.value, fieldCtx)
          case _ if isCaseClassOrEnum[A] =>
            deriveResultRecursively[A](using fieldCtx)
              .map(_.asInstanceOf[Expr[reactivemongo.api.bson.BSONWriter[A]]])
          case _ =>
            val err =
              BsonDocumentHandlerDerivationError.CannotDeriveField(Type[A].prettyPrint, "No BSONWriter found")
            Log.error(err.message) >> MIO.fail(err)
        }
    }
  }

  private def deriveInlineCollectionWriter[A: Type, Item: Type](
      isCollection: IsCollectionOf[A, Item],
      fieldCtx: DerivationCtx[A]
  ): MIO[Expr[reactivemongo.api.bson.BSONWriter[A]]] = {
    @scala.annotation.nowarn("msg=is never used")
    implicit val BsonValueT: Type[reactivemongo.api.bson.BSONValue] = Types.BsonValue
    @scala.annotation.nowarn("msg=is never used")
    implicit val TryBsonValueT: Type[scala.util.Try[reactivemongo.api.bson.BSONValue]] =
      Types.TryCtor[reactivemongo.api.bson.BSONValue]
    resolveBsonWriter[Item](fieldCtx.nest[Item]).flatMap { itemWriterExpr =>
      LambdaBuilder
        .of1[A]("value")
        .traverse { valueExpr =>
          MIO.pure(Expr.quote {
            val iterable = Expr.splice(isCollection.asIterable(valueExpr)).asInstanceOf[Iterable[Item]]
            val items = iterable.iterator
            val builder = scala.collection.mutable.ListBuffer.empty[reactivemongo.api.bson.BSONValue]
            var err: Throwable = null
            while (err == null && items.hasNext)
              try builder += Expr.splice(itemWriterExpr).writeTry(items.next()).get
              catch { case e: Exception => err = e }
            if (err != null) scala.util.Failure(err)
            else
              scala.util.Success(reactivemongo.api.bson.BSONArray(builder.result()): reactivemongo.api.bson.BSONValue)
          })
        }
        .map(_.build[scala.util.Try[reactivemongo.api.bson.BSONValue]])
        .map { writeFnExpr =>
          Expr.quote {
            new reactivemongo.api.bson.BSONWriter[A] {
              def writeTry(value: A): scala.util.Try[reactivemongo.api.bson.BSONValue] =
                Expr.splice(writeFnExpr).apply(value)
            }
          }
        }
    }
  }

  private def deriveInlineMapWriter[A: Type, Pair: Type](
      isMap: IsMapOf[A, Pair],
      fieldCtx: DerivationCtx[A]
  ): MIO[Expr[reactivemongo.api.bson.BSONWriter[A]]] = {
    @scala.annotation.nowarn("msg=is never used")
    implicit val BsonValueT: Type[reactivemongo.api.bson.BSONValue] = Types.BsonValue
    @scala.annotation.nowarn("msg=is never used")
    implicit val TryBsonValueT: Type[scala.util.Try[reactivemongo.api.bson.BSONValue]] =
      Types.TryCtor[reactivemongo.api.bson.BSONValue]
    resolveBsonWriter[Pair](fieldCtx.nest[Pair]).flatMap { pairWriterExpr =>
      LambdaBuilder
        .of1[A]("value")
        .traverse { valueExpr =>
          MIO.pure(Expr.quote {
            val pairs = Expr.splice(isMap.asIterable(valueExpr)).asInstanceOf[Iterable[(String, Pair)]]
            val iter = pairs.iterator
            val elements = scala.collection.mutable.ListBuffer.empty[(String, reactivemongo.api.bson.BSONValue)]
            var err: Throwable = null
            while (err == null && iter.hasNext) {
              val (key, value) = iter.next()
              try elements += (key -> Expr.splice(pairWriterExpr).writeTry(value).get)
              catch { case e: Exception => err = e }
            }
            if (err != null) scala.util.Failure(err)
            else
              scala.util.Success(
                reactivemongo.api.bson.BSONDocument(elements.result()): reactivemongo.api.bson.BSONValue
              )
          })
        }
        .map(_.build[scala.util.Try[reactivemongo.api.bson.BSONValue]])
        .map { writeFnExpr =>
          Expr.quote {
            new reactivemongo.api.bson.BSONWriter[A] {
              def writeTry(value: A): scala.util.Try[reactivemongo.api.bson.BSONValue] =
                Expr.splice(writeFnExpr).apply(value)
            }
          }
        }
    }
  }

  /** Try to extract a @reader-annotated BSONReader for a field. Returns None if no annotation. */
  def annotatedReader[A: Type](param: Parameter): Option[Expr[reactivemongo.api.bson.BSONReader[A]]] = {
    val annTpe = Type.of[hearth.kindlings.reactivemongobsonderivation.annotations.Reader[A]]
    getAnnotationValueUntyped(param)(annTpe) match {
      case Some(untyped) =>
        // The annotation argument is a BSONReader[A]-typed value, but UntypedExpr loses the type.
        // Wrap it in a quote that upcasts to the expected reader type. The `.asInstanceOf` keeps the
        // path through `Expr.splice` stable across Scala 2 and 3; using `asTyped[...]` here instead
        // triggers Hearth's "Nested context should not loop" (-Xcheck-macros) on Scala 3.
        Some(annotateReaderValue[A](untyped))
      case None
          if hasAnnotationTypeConstructor(
            param,
            "hearth.kindlings.reactivemongobsonderivation.annotations.Reader"
          ) =>
        Environment.reportErrorAndAbort(
          s"Invalid @Reader annotation for field ${param.name}: BSONReader[${Type[A].prettyPrint}] expected"
        )
      case None => None
    }
  }

  /** Helper kept outside the splice so the Scala 2 reifier materializes `Type[BSONReader[A]]` as a real type parameter,
    * not a `param.tpe.*` path-dependent reference (see `hearth-cross-compilation` pitfall #3/#23).
    */
  private def annotateReaderValue[A: Type](untyped: UntypedExpr): Expr[reactivemongo.api.bson.BSONReader[A]] = {
    implicit val ReaderT: Type[reactivemongo.api.bson.BSONReader[A]] = Types.BsonReader[A]
    Expr.quote {
      Expr
        .splice(untyped.asTyped[reactivemongo.api.bson.BSONReader[A]])
        .asInstanceOf[reactivemongo.api.bson.BSONReader[A]]
    }
  }

  /** Try to extract a @writer-annotated BSONWriter for a field. Returns None if no annotation. */
  def annotatedWriter[A: Type](param: Parameter): Option[Expr[reactivemongo.api.bson.BSONWriter[A]]] = {
    val annTpe = Type.of[hearth.kindlings.reactivemongobsonderivation.annotations.Writer[A]]
    getAnnotationValueUntyped(param)(annTpe) match {
      case Some(untyped) => Some(annotateWriterValue[A](untyped))
      case None
          if hasAnnotationTypeConstructor(
            param,
            "hearth.kindlings.reactivemongobsonderivation.annotations.Writer"
          ) =>
        Environment.reportErrorAndAbort(
          s"Invalid @Writer annotation for field ${param.name}: BSONWriter[${Type[A].prettyPrint}] expected"
        )
      case None => None
    }
  }

  private def annotateWriterValue[A: Type](untyped: UntypedExpr): Expr[reactivemongo.api.bson.BSONWriter[A]] = {
    implicit val WriterT: Type[reactivemongo.api.bson.BSONWriter[A]] = Types.BsonWriter[A]
    Expr.quote {
      Expr
        .splice(untyped.asTyped[reactivemongo.api.bson.BSONWriter[A]])
        .asInstanceOf[reactivemongo.api.bson.BSONWriter[A]]
    }
  }

  // Context

  final case class DerivationCtx[A](
      tpe: Type[A],
      cache: MLocal[ValDefsCache],
      derivedType: Option[??],
      config: Expr[BsonDocumentHandlerConfig],
      evaluatedConfig: Option[BsonDocumentHandlerConfig],
      flattenStack: List[String]
  ) {

    def nest[B: Type]: DerivationCtx[B] = DerivationCtx(
      tpe = Type[B],
      cache = cache,
      derivedType = derivedType,
      config = config,
      evaluatedConfig = evaluatedConfig,
      flattenStack = flattenStack
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
        evaluatedConfig = evaluatedConfig,
        flattenStack = Nil
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
      @scala.annotation.nowarn("msg=is never used")
      implicit val BsonValueT: Type[reactivemongo.api.bson.BSONValue] = Types.BsonValue
      @scala.annotation.nowarn("msg=is never used")
      implicit val BsonArrayT: Type[reactivemongo.api.bson.BSONArray] = Types.BsonArray
      implicit val BsonDocumentT: Type[BSONDocument] = Types.BsonDocument
      @scala.annotation.nowarn("msg=is never used")
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
      @scala.annotation.nowarn("msg=is never used")
      implicit val BsonValueT: Type[reactivemongo.api.bson.BSONValue] = Types.BsonValue
      implicit val BsonDocumentT: Type[BSONDocument] = Types.BsonDocument
      @scala.annotation.nowarn("msg=is never used")
      implicit val StringT: Type[String] = Types.String
      @scala.annotation.nowarn("msg=is never used")
      implicit val TryAT: Type[scala.util.Try[A]] = Types.TryCtor[A]
      implicit val TryBsonDocumentT: Type[scala.util.Try[BSONDocument]] = Types.TryCtor[BSONDocument]

      import isMap.{Key, Value, CtorResult}
      ensureMapKeyCodecsOf[A, Pair](isMap)
      val factoryExpr = isMap.factory
      val buildStep = isMap.build

      // Summon KeyReader[Key] / KeyWriter[Key] for non-String map keys.
      // For String keys these will resolve to the built-in KeyReader[String] / KeyWriter[String].
      implicit val KeyReaderT: Type[reactivemongo.api.bson.KeyReader[Key]] = Types.KeyReader[Key]
      implicit val KeyWriterT: Type[reactivemongo.api.bson.KeyWriter[Key]] = Types.KeyWriter[Key]
      val keyReaderOpt: Option[Expr[reactivemongo.api.bson.KeyReader[Key]]] =
        Type[reactivemongo.api.bson.KeyReader[Key]]
          .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
          .toOption
      val keyWriterOpt: Option[Expr[reactivemongo.api.bson.KeyWriter[Key]]] =
        Type[reactivemongo.api.bson.KeyWriter[Key]]
          .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
          .toOption

      // Resolve value reader/writer using dual-path approach
      val valueCtx = ctx.nest[Value]
      for {
        valueReaderExpr <- resolveBsonReader[Value](valueCtx)
        valueWriterExpr <- resolveBsonWriter[Value](valueCtx)
        readLambda <- LambdaBuilder
          .of1[BSONDocument]("doc")
          .traverse { docExpr =>
            val readLoop: Expr[scala.collection.mutable.Builder[Pair, CtorResult]] = keyReaderOpt match {
              case Some(keyReaderExpr) =>
                Expr.quote {
                  val valueReader = Expr.splice(valueReaderExpr)
                  val keyReader = Expr.splice(keyReaderExpr)
                  val mapBuilder = Expr.splice(factoryExpr).newBuilder
                  var err: Throwable = null
                  val iter = Expr.splice(docExpr).elements.iterator
                  while (err == null && iter.hasNext) {
                    val el = iter.next()
                    keyReader.readTry(el.name) match {
                      case scala.util.Success(k) =>
                        valueReader.readTry(el.value) match {
                          case scala.util.Success(v) =>
                            mapBuilder += Expr.splice(
                              isMap.pair(Expr.quote(k), Expr.quote(v))
                            )
                          case scala.util.Failure(e) => err = e
                        }
                      case scala.util.Failure(e) => err = e
                    }
                  }
                  if (err != null) throw err
                  mapBuilder
                }
              case None =>
                Expr.quote {
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
            }
            val buildResultExpr = buildStep.ctor(readLoop)
            collectBuildResult[A](buildStep, buildResultExpr.asInstanceOf[Expr[Any]])
          }
          .map(_.build[scala.util.Try[A]])
        writeLambda <- LambdaBuilder
          .of1[A]("value")
          .traverse { valueExpr =>
            keyWriterOpt match {
              case Some(keyWriterExpr) =>
                MIO.pure(Expr.quote {
                  val valueWriter = Expr.splice(valueWriterExpr)
                  val keyWriter = Expr.splice(keyWriterExpr)
                  val iterable =
                    Expr.splice(isMap.asIterable(valueExpr)).asInstanceOf[Iterable[(Key, Value)]]
                  val elements = scala.collection.mutable.ListBuffer
                    .empty[reactivemongo.api.bson.BSONElement]
                  var err: Throwable = null
                  val iter = iterable.iterator
                  while (err == null && iter.hasNext) {
                    val pair = iter.next()
                    keyWriter.writeTry(pair._1) match {
                      case scala.util.Success(keyStr) =>
                        valueWriter.writeTry(pair._2) match {
                          case scala.util.Success(bson) =>
                            elements +=
                              reactivemongo.api.bson.BSONElement(keyStr, bson)
                          case scala.util.Failure(e) => err = e
                        }
                      case scala.util.Failure(e) => err = e
                    }
                  }
                  if (err != null) scala.util.Failure(err)
                  else scala.util.Success(BSONDocument(elements.result()*))
                })
              case None =>
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
                          .BSONElement(
                            pair._1.asInstanceOf[String],
                            bson.asInstanceOf[reactivemongo.api.bson.BSONValue]
                          )
                      case scala.util.Failure(e) => err = e
                    }
                  }
                  if (err != null) scala.util.Failure(err)
                  else scala.util.Success(BSONDocument(elements.result()*))
                })
            }
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

    /** Compute the default value expression for a field, from either the Scala-level default (via `param.hasDefault` /
      * `param.defaultValue`) or the `@DefaultValue` annotation.
      */
    private def computeDefaultExpr[Field: Type](param: Parameter): Option[Expr[Field]] = {
      val fromParamDefault: Option[Expr[Field]] =
        if (param.hasDefault) param.defaultValue.flatMap { method =>
          foldInstanceFree(method, "Default value")(
            onTypes = _ => Map.empty,
            onValues = _ => Map.empty
          ).toOption.map(ee => ee.value.asInstanceOf[Expr[Field]])
        }
        else None

      def fromAnnotation: Option[Expr[Field]] = {
        val annTpe = Type.of[hearth.kindlings.reactivemongobsonderivation.annotations.DefaultValue[Field]]
        getAnnotationValueUntyped(param)(annTpe).map { untyped =>
          // Widen the annotation argument to the field type at runtime.
          // This handles cases like `@DefaultValue(Some(45.6f))` for an `Option[Float]` field,
          // where the argument expression has a more specific type (`Some[Float]`). The
          // `.asInstanceOf` keeps the splice stable across Scala 2 and Scala 3 (see note on
          // `annotateReaderValue`); a plain `asTyped[Field]` triggers Hearth's
          // "Nested context should not loop" (-Xcheck-macros) on Scala 3.
          Expr.quote {
            Expr.splice(untyped.asTyped[Field]).asInstanceOf[Field]
          }
        }
      }

      fromParamDefault.orElse(fromAnnotation)
    }

    /** Per-field typed var + assign-on-success, returned as a `ValDefs[(name, getter, assignExpr)]`. The var is
      * initialized to the type's zero value; the assign expression matches the per-field BSON read `Try[Any]` and
      * writes the typed value into the var on `Success`, propagating the `Failure` as `Try[Unit]` otherwise.
      *
      * Lives outside the per-field `parTraverse`/quote scope as an explicit `[Field: Type]` helper so that the Scala 2
      * reifier captures `Field` as a real type parameter (with a concrete `WeakTypeTag`) rather than a path-dependent
      * `param.tpe.Underlying` reference leaking the macro-only `param` into generated code (see
      * `hearth-cross-compilation` pitfall #3/#23).
      */
    private def buildFieldVarDef[Field: Type](
        docExpr: Expr[BSONDocument],
        fName: String,
        param: Parameter,
        fieldCtx: DerivationCtx[Field]
    ): MIO[ValDefs[(String, Expr_??, Expr[scala.util.Try[Unit]])]] =
      buildFieldReadExpr[Field](docExpr, fName, param, fieldCtx).map { readTryExpr =>
        val defaultExpr: Expr[Field] = deriveZeroValue[Field]
        val fieldVar = ValDefs.createVar[Field](defaultExpr, s"_$fName")
        fieldVar.map { case (getter, setter) =>
          // Match on the runtime Try[Any] result of the field read, casting the success value to Field and
          // writing it through the typed var's setter; Failure propagates short-circuited through the outer
          // .flatMap tail of `sequencedAssigns` in `deriveCaseClass`.
          val assignExpr: Expr[scala.util.Try[Unit]] = Expr.quote {
            Expr.splice(readTryExpr) match {
              case scala.util.Success(v) =>
                Expr.splice(setter(Expr.quote(v.asInstanceOf[Field])))
                scala.util.Success(())
              case f: scala.util.Failure[?] => f.asInstanceOf[scala.util.Try[Unit]]
            }
          }
          (fName, getter.as_??, assignExpr)
        }
      }

    private def buildFieldReadExpr[Field: Type](
        docExpr: Expr[BSONDocument],
        fName: String,
        param: Parameter,
        fieldCtx: DerivationCtx[Field]
    ): MIO[Expr[scala.util.Try[Any]]] = {
      implicit val ignoreAnnT: Type[hearth.kindlings.reactivemongobsonderivation.annotations.Ignore] = Types.ignoreAnn
      if (hasAnnotationType[hearth.kindlings.reactivemongobsonderivation.annotations.Ignore](param)) {
        // Match ReactiveMongo: an ignored field must be reconstructible. Falling back to null would
        // produce invalid values for primitives and makes a malformed document appear to decode.
        computeDefaultExpr[Field](param) match {
          case Some(defaultExpr) =>
            MIO.pure(Expr.quote(scala.util.Success(Expr.splice(defaultExpr)).asInstanceOf[scala.util.Try[Any]]))
          case None =>
            val err = BsonDocumentHandlerDerivationError.CannotIgnoreFieldWithoutDefault(
              fName,
              Type[Field].prettyPrint
            )
            Log.error(err.message) >> MIO.fail(err)
        }
      } else if (isFlattened(param)) {
        // A flattened custom reader receives the whole containing document, just like a derived
        // BSONDocumentHandler would. This matches ReactiveMongo's `@Flatten @Reader(...)` behavior.
        annotatedReader[Field](param) match {
          case Some(readerExpr) =>
            MIO.pure(Expr.quote {
              Expr.splice(readerExpr).readTry(Expr.splice(docExpr)).asInstanceOf[scala.util.Try[Any]]
            })
          case None =>
            buildFlattenedFieldReadExpr[Field](docExpr, fName, fieldCtx)
        }
      } else {
        val fNameExpr: Expr[String] = resolveFieldKeyExpr(fName, param, fieldCtx)

        // @reader annotation: use the provided reader directly
        annotatedReader[Field](param) match {
          case Some(readerExpr) =>
            val defaultExprOpt: Option[Expr[Field]] = computeDefaultExpr[Field](param)
            buildReadWithReader[Field](docExpr, fNameExpr, readerExpr, defaultExprOpt)
          case None =>
            buildFieldReadExprWithoutAnnotation[Field](docExpr, fNameExpr, param, fieldCtx)
        }
      }
    }

    /** Resolve a document handler for a flattened field. Prefer a user-provided standard `BSONDocumentHandler` before
      * deriving one, matching ReactiveMongo's flatten behavior for externally-defined field types.
      */
    private def resolveFlattenedHandler[Field: Type](
        fName: String,
        fieldCtx: DerivationCtx[Field]
    ): MIO[Expr[reactivemongo.api.bson.BSONDocumentHandler[Field]]] = {
      implicit val HandlerT: Type[reactivemongo.api.bson.BSONDocumentHandler[Field]] =
        Types.ExternalBsonDocumentHandler[Field]
      Type[reactivemongo.api.bson.BSONDocumentHandler[Field]]
        .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
        .toEither match {
        case Right(handler)                      => MIO.pure(handler)
        case Left(_) if isCaseClassOrEnum[Field] =>
          deriveResultRecursively[Field](fieldCtx)
            .map(_.asInstanceOf[Expr[reactivemongo.api.bson.BSONDocumentHandler[Field]]])
        case Left(_) =>
          val err = BsonDocumentHandlerDerivationError.CannotFlattenNonDocumentField(fName, Type[Field].prettyPrint)
          Log.error(err.message) >> MIO.fail(err)
      }
    }

    private def buildFlattenedFieldReadExpr[Field: Type](
        docExpr: Expr[BSONDocument],
        fName: String,
        fieldCtx: DerivationCtx[Field]
    ): MIO[Expr[scala.util.Try[Any]]] =
      resolveFlattenedHandler[Field](fName, fieldCtx).map { handlerExpr =>
        Expr.quote {
          Expr.splice(handlerExpr).readTry(Expr.splice(docExpr)).asInstanceOf[scala.util.Try[Any]]
        }
      }

    private def buildReadWithReader[Field: Type](
        docExpr: Expr[BSONDocument],
        fNameExpr: Expr[String],
        readerExpr: Expr[reactivemongo.api.bson.BSONReader[Field]],
        defaultExprOpt: Option[Expr[Field]]
    ): MIO[Expr[scala.util.Try[Any]]] = {
      val readCode = Expr.quote {
        Expr.splice(docExpr).get(Expr.splice(fNameExpr)) match {
          case Some(v) if !v.isInstanceOf[reactivemongo.api.bson.BSONNull] =>
            Expr.splice(readerExpr).readTry(v).asInstanceOf[scala.util.Try[Any]]
          case _ =>
            scala.util
              .Failure(new NoSuchElementException("Field not found"))
              .asInstanceOf[scala.util.Try[Any]]
        }
      }
      defaultExprOpt match {
        case Some(defaultExpr) =>
          MIO.pure(
            Expr.quote {
              Expr.splice(docExpr).get(Expr.splice(fNameExpr)) match {
                case None => scala.util.Success(Expr.splice(defaultExpr)).asInstanceOf[scala.util.Try[Any]]
                case _    => Expr.splice(readCode)
              }
            }
          )
        case None => MIO.pure(readCode)
      }
    }

    private def buildFieldReadExprWithoutAnnotation[Field: Type](
        docExpr: Expr[BSONDocument],
        fNameExpr: Expr[String],
        param: Parameter,
        fieldCtx: DerivationCtx[Field]
    ): MIO[Expr[scala.util.Try[Any]]] =

      Type[Field] match {
        case IsOption(isOption) =>
          import isOption.Underlying as Inner
          val innerCtx = fieldCtx.copy(tpe = Type[Inner])
          resolveFieldReader[Inner](innerCtx).map { innerReaderExpr =>
            val defaultExprOpt: Option[Expr[Field]] = computeDefaultExpr[Field](param)
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
        case IsValueType(isValueType) if !Type[Field].isNamedTuple =>
          import isValueType.Underlying as Inner
          val innerCtx = fieldCtx.copy(tpe = Type[Inner])
          val wrapLambdaMIO = isValueType.value.wrap match {
            case _: CtorLikeOf.PlainValue[?, ?] =>
              LambdaBuilder
                .of1[Inner]("inner")
                .traverse(innerExpr => MIO.pure(isValueType.value.wrap.apply(innerExpr).asInstanceOf[Expr[Field]]))
                .map(_.build[Field])
            case _: CtorLikeOf.EitherStringOrValue[?, ?] =>
              LambdaBuilder
                .of1[Inner]("inner")
                .traverse { innerExpr =>
                  val wrapResult = isValueType.value.wrap.apply(innerExpr).asInstanceOf[Expr[Either[String, Field]]]
                  MIO.pure(Expr.quote {
                    Expr.splice(wrapResult) match {
                      case scala.Right(v)  => v
                      case scala.Left(msg) => throw new IllegalArgumentException(msg)
                    }
                  })
                }
                .map(_.build[Field])
            case _ =>
              LambdaBuilder
                .of1[Inner]("inner")
                .traverse(innerExpr => MIO.pure(isValueType.value.wrap.apply(innerExpr).asInstanceOf[Expr[Field]]))
                .map(_.build[Field])
          }
          wrapLambdaMIO.flatMap { wrapLambda =>
            resolveFieldReader[Inner](innerCtx).map { readerExpr =>
              val defaultExprOpt: Option[Expr[Field]] = computeDefaultExpr[Field](param)
              val readCode = Expr.quote {
                Expr.splice(docExpr).get(Expr.splice(fNameExpr)) match {
                  case Some(v) if !v.isInstanceOf[reactivemongo.api.bson.BSONNull] =>
                    Expr
                      .splice(readerExpr)
                      .readTry(v)
                      .map(Expr.splice(wrapLambda).apply(_))
                      .asInstanceOf[scala.util.Try[Any]]
                  case _ =>
                    scala.util.Failure(new NoSuchElementException("Field not found")).asInstanceOf[scala.util.Try[Any]]
                }
              }
              defaultExprOpt match {
                case Some(defaultExpr) =>
                  Expr.quote {
                    Expr.splice(docExpr).get(Expr.splice(fNameExpr)) match {
                      case None => scala.util.Success(Expr.splice(defaultExpr)).asInstanceOf[scala.util.Try[Any]]
                      case _    => Expr.splice(readCode)
                    }
                  }
                case None => readCode
              }
            }
          }
        case _ =>
          resolveFieldReader[Field](fieldCtx).map { readerExpr =>
            val defaultExprOpt: Option[Expr[Field]] = computeDefaultExpr[Field](param)
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

    private def isFlattened(param: Parameter): Boolean = {
      implicit val fat: Type[hearth.kindlings.reactivemongobsonderivation.annotations.Flatten] = Types.flattenAnn
      hasAnnotationType[hearth.kindlings.reactivemongobsonderivation.annotations.Flatten](param)
    }

    private def buildFieldWriteExpr[Field: Type](
        fName: String,
        param: Parameter,
        fieldValue: Expr[Field],
        fieldCtx: DerivationCtx[Field]
    ): MIO[Expr[scala.util.Try[List[Option[reactivemongo.api.bson.BSONElement]]]]] = {
      implicit val ignoreAnnT: Type[hearth.kindlings.reactivemongobsonderivation.annotations.Ignore] = Types.ignoreAnn
      if (hasAnnotationType[hearth.kindlings.reactivemongobsonderivation.annotations.Ignore](param)) {
        // @ignore: field is not serialized. Produce no BSON elements.
        MIO.pure(Expr.quote(scala.util.Success(Nil): scala.util.Try[List[Option[reactivemongo.api.bson.BSONElement]]]))
      } else {
        val fNameExpr: Expr[String] = resolveFieldKeyExpr(fName, param, fieldCtx)

        if (isFlattened(param)) {
          // A flattened custom writer must produce a BSONDocument whose elements can be merged into
          // the containing document. Reporting a Failure here is clearer than silently dropping or
          // nesting a non-document BSON value.
          annotatedWriter[Field](param) match {
            case Some(writerExpr) =>
              MIO.pure(Expr.quote {
                Expr.splice(writerExpr).writeTry(Expr.splice(fieldValue)).flatMap {
                  case document: BSONDocument =>
                    scala.util.Success(document.elements.toList.map(Some(_)))
                  case value =>
                    scala.util.Failure(
                      new IllegalArgumentException(s"@Flatten @Writer must produce BSONDocument, got $value")
                    )
                }
              })
            case None =>
              buildFlattenedFieldWriteExpr[Field](fName, fieldValue, fieldCtx)
          }
        } else {
          // @writer annotation: use the provided writer directly
          annotatedWriter[Field](param) match {
            case Some(writerExpr) =>
              MIO.pure(
                Expr.quote {
                  Expr.splice(writerExpr).writeTry(Expr.splice(fieldValue)).map { bsv =>
                    List(Some(reactivemongo.api.bson.BSONElement(Expr.splice(fNameExpr), bsv)))
                  }
                }
              )
            case None =>
              buildFieldWriteExprWithoutAnnotation[Field](fNameExpr, param, fieldValue, fieldCtx)
          }
        }
      }
    }

    private def buildFlattenedFieldWriteExpr[Field: Type](
        fName: String,
        fieldValue: Expr[Field],
        fieldCtx: DerivationCtx[Field]
    ): MIO[Expr[scala.util.Try[List[Option[reactivemongo.api.bson.BSONElement]]]]] =
      resolveFlattenedHandler[Field](fName, fieldCtx).map { handlerExpr =>
        Expr.quote {
          Expr.splice(handlerExpr).writeTry(Expr.splice(fieldValue)).map { innerDoc =>
            innerDoc.elements.map(e => Some(reactivemongo.api.bson.BSONElement(e.name, e.value))).toList
          }
        }
      }

    private def buildFieldWriteExprWithoutAnnotation[Field: Type](
        fNameExpr: Expr[String],
        param: Parameter,
        fieldValue: Expr[Field],
        fieldCtx: DerivationCtx[Field]
    ): MIO[Expr[scala.util.Try[List[Option[reactivemongo.api.bson.BSONElement]]]]] =

      Type[Field] match {
        case IsOption(isOption) =>
          import isOption.Underlying as Inner
          val innerCtx = fieldCtx.copy(tpe = Type[Inner])
          implicit val nat: Type[hearth.kindlings.reactivemongobsonderivation.annotations.NoneAsNull] =
            Types.noneAsNullAnn
          val writeAsNull =
            hasAnnotationType[hearth.kindlings.reactivemongobsonderivation.annotations.NoneAsNull](param)
          resolveFieldWriter[Inner](innerCtx).map { innerWriterExpr =>
            if (writeAsNull)
              Expr.quote {
                Expr.splice(fieldValue) match {
                  case Some(v) =>
                    Expr.splice(innerWriterExpr).writeTry(v.asInstanceOf[Inner]).map { bsv =>
                      List(Some(reactivemongo.api.bson.BSONElement(Expr.splice(fNameExpr), bsv)))
                    }
                  case None =>
                    scala.util.Success(
                      List(
                        Some(
                          reactivemongo.api.bson
                            .BSONElement(Expr.splice(fNameExpr), reactivemongo.api.bson.BSONNull)
                        )
                      )
                    )
                }
              }
            else
              Expr.quote {
                Expr.splice(fieldValue) match {
                  case Some(v) =>
                    Expr.splice(innerWriterExpr).writeTry(v.asInstanceOf[Inner]).map { bsv =>
                      List(Some(reactivemongo.api.bson.BSONElement(Expr.splice(fNameExpr), bsv)))
                    }
                  case None => scala.util.Success(List.empty)
                }
              }
          }
        case IsValueType(isValueType) if !Type[Field].isNamedTuple =>
          import isValueType.Underlying as Inner
          val innerCtx = fieldCtx.copy(tpe = Type[Inner])
          LambdaBuilder
            .of1[Field]("value")
            .traverse(valueExpr => MIO.pure(isValueType.value.unwrap(valueExpr)))
            .map(_.build[Inner])
            .flatMap { unwrapLambda =>
              resolveFieldWriter[Inner](innerCtx).map { writerExpr =>
                Expr.quote {
                  Expr.splice(writerExpr).writeTry(Expr.splice(unwrapLambda).apply(Expr.splice(fieldValue))).map { bsv =>
                    List(Some(reactivemongo.api.bson.BSONElement(Expr.splice(fNameExpr), bsv)))
                  }
                }
              }
            }
        case _ =>
          resolveFieldWriter[Field](fieldCtx).map { writerExpr =>
            Expr.quote {
              Expr.splice(writerExpr).writeTry(Expr.splice(fieldValue)).map { bsv =>
                List(Some(reactivemongo.api.bson.BSONElement(Expr.splice(fNameExpr), bsv)))
              }
            }
          }
      }

    private def deriveCaseClass[A: DerivationCtx](
        caseClass: CaseClass[A]
    ): MIO[Expr[KindlingsBsonDocumentHandler[A]]] = {
      implicit val BsonDocumentT: Type[BSONDocument] = Types.BsonDocument
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
              @scala.annotation.nowarn("msg=is never used")
              implicit val StringT: Type[String] = Types.String
              val knownKeyExprs: List[Expr[String]] = fieldsList.flatMap { case (fName, param) =>
                if (isFlattened(param)) None
                else Some(resolveFieldKeyExpr(fName, param, ctx))
              }
              val unexpectedCheckExpr: Expr[scala.util.Try[Unit]] =
                buildUnexpectedFieldsCheck(docExpr, knownKeyExprs, ctx)
              for {
                // Build one typed local var per field (see kindlings-runtime-perf technique #2). Each var holds the
                // field's typed value directly, eliminating the Array[Any]+sequenceTries boxing path (every primitive
                // field would otherwise round-trip through java.lang.Integer/Boolean via Array[Any]).
                fieldVarDefsNel <- fieldsNel.parTraverse { case (fName, param) =>
                  import param.tpe.Underlying as Field
                  if (
                    isFlattened(param) && (Type[Field] =:= Type[A] || ctx.flattenStack.contains(
                      Type[Field].prettyPrint
                    ))
                  ) {
                    val err = BsonDocumentHandlerDerivationError.CannotFlattenRecursiveField(
                      fName,
                      Type[A].prettyPrint
                    )
                    Log.error(err.message) >> MIO.fail(err)
                  } else {
                    val fieldCtx = if (isFlattened(param)) ctx.nestFlattened[Field] else ctx.nest[Field]
                    buildFieldVarDef[Field](docExpr, fName, param, fieldCtx)
                  }
                }
              } yield {
                val combinedVars = fieldVarDefsNel.toList.foldLeft(
                  ValDefsTraverse.pure(List.empty): ValDefs[List[(String, Expr_??, Expr[scala.util.Try[Unit]])]]
                )((acc, vd) => acc.map2(vd) { case (l, e) => l :+ e })
                combinedVars.use { fieldInfos =>
                  // fieldInfo: (name, getter.as_??, assignExpr). Construct takes getters as field values.
                  val fieldMap: Map[String, Expr_??] = fieldsList
                    .zip(fieldInfos)
                    .map { case ((name, _), (_, getter, _)) =>
                      (name, getter)
                    }
                    .toMap
                  val constructExpr: Expr[A] = foldInstanceFree(caseClass.primaryConstructor, "Constructor")(
                    onTypes = _ => Map.empty,
                    onValues = _ => fieldMap
                  ) match {
                    case Right(expr) => expr.value.asInstanceOf[Expr[A]]
                    case Left(error) =>
                      Environment.reportErrorAndAbort(
                        BsonDocumentHandlerDerivationError
                          .CannotConstructType(Type[A].prettyPrint, Some(error))
                          .message
                      )
                  }
                  // Sequence per-field assign-with-failure-propagation: short-circuit on the first read failure.
                  val sequencedAssigns: Expr[scala.util.Try[Unit]] = fieldInfos
                    .map(_._3)
                    .foldRight(
                      Expr.quote(scala.util.Success(()): scala.util.Try[Unit])
                    ) { (next, acc) =>
                      Expr.quote(Expr.splice(acc).flatMap(_ => Expr.splice(next)))
                    }
                  Expr.quote {
                    Expr
                      .splice(sequencedAssigns)
                      .flatMap(_ => Expr.splice(unexpectedCheckExpr))
                      .map(_ => Expr.splice(constructExpr))
                  }
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
                  val fieldCtx = if (isFlattened(param)) ctx.nestFlattened[Field] else ctx.nest[Field]
                  buildFieldWriteExpr[Field](fName, param, fieldValue.value.asInstanceOf[Expr[Field]], fieldCtx)
                }
                .map { etries =>
                  val listTryExpr = etries.toList.foldRight(
                    Expr.quote(
                      scala.util.Success(List.empty[Option[reactivemongo.api.bson.BSONElement]]): Try[
                        List[Option[reactivemongo.api.bson.BSONElement]]
                      ]
                    )
                  ) { case (et, acc) =>
                    Expr.quote(for { tail <- Expr.splice(acc); head <- Expr.splice(et) } yield tail ++ head)
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
                                val childDoc = Expr.splice(childHandler).writeTry(Expr.splice(enumCaseValue)).get
                                scala.util.Success(
                                  childDoc ++
                                    BSONDocument(
                                      Expr.splice(discriminatorFieldExpr) -> Expr.splice(discriminatorNameExpr)
                                    )
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
                    Expr.splice(childHandler).readDocument(Expr.splice(docExpr)).map(_.asInstanceOf[A])
                  case _ => Expr.splice(elseExpr)
                }
              }
          }
      }
  }
}
