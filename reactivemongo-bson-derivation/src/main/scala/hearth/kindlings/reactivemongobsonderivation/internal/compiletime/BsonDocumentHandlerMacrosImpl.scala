package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.MacroCommons
import hearth.fp.data.NonEmptyList
import hearth.fp.effect.*
import hearth.fp.syntax.*
import hearth.std.*

import hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler
import reactivemongo.api.bson.BSONDocument

import scala.util.Try

trait BsonDocumentHandlerMacrosImpl
    extends hearth.kindlings.derivation.compiletime.DerivationTimeout
    with hearth.kindlings.derivation.compiletime.LoadStandardExtensionsOnce { this: MacroCommons & StdExtensions =>

  override protected def derivationSettingsNamespace: String = "reactivemongoBsonDerivation"

  // Types

  private[compiletime] object Types {
    def BsonDocumentHandler: Type.Ctor1[KindlingsBsonDocumentHandler] = Type.Ctor1.of[KindlingsBsonDocumentHandler]
    val LogDerivation: Type[KindlingsBsonDocumentHandler.LogDerivation] =
      Type.of[KindlingsBsonDocumentHandler.LogDerivation]
    val BsonDocument: Type[BSONDocument] = Type.of[BSONDocument]
    val String: Type[String] = Type.of[String]
    val BsonValue: Type[reactivemongo.api.bson.BSONValue] = Type.of[reactivemongo.api.bson.BSONValue]
    val BsonElement: Type[reactivemongo.api.bson.BSONElement] = Type.of[reactivemongo.api.bson.BSONElement]
    val ArrayAny: Type[Array[Any]] = Type.of[Array[Any]]
    val Any: Type[Any] = Type.of[Any]
    val TryBsonDocument: Type[Try[BSONDocument]] = Type.of[Try[BSONDocument]]
    val BsonReader: Type.Ctor1[reactivemongo.api.bson.BSONReader] = Type.Ctor1.of[reactivemongo.api.bson.BSONReader]
    val BsonWriter: Type.Ctor1[reactivemongo.api.bson.BSONWriter] = Type.Ctor1.of[reactivemongo.api.bson.BSONWriter]
    val TryCtor: Type.Ctor1[Try] = Type.Ctor1.of[Try]

    lazy val ignoredAutoDerivationMethods: Seq[UntypedMethod] =
      Type.of[KindlingsBsonDocumentHandler.type].methods.collect {
        case method if method.value.isImplicit => method.value.asUntyped
      }
  }

  // Entrypoints

  def deriveTypeClass[A: Type]: Expr[KindlingsBsonDocumentHandler[A]] = {
    val selfType: Option[??] = Some(Type[A].as_??)

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

          val ctx = DerivationCtx.from[A](derivedType = selfType)
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

  // Context

  final case class DerivationCtx[A](
      tpe: Type[A],
      cache: MLocal[ValDefsCache],
      derivedType: Option[??]
  ) {

    def nest[B: Type]: DerivationCtx[B] = copy(tpe = Type[B])

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
        built <- defBuilder.traverse { _ => helper }
        currentCache <- cache.get
        _ <- cache.set(built.buildCached(currentCache, "cached-handler-method"))
        _ <- Log.info(s"Defined BSONDocumentHandler helper for ${Type[B].prettyPrint}")
      } yield ()
    }

    override def toString: String = s"BSONDocumentHandler[${tpe.prettyPrint}]"
  }

  object DerivationCtx {
    def from[A: Type](derivedType: Option[??]): DerivationCtx[A] =
      DerivationCtx(tpe = Type[A], cache = ValDefsCache.mlocal, derivedType = derivedType)
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
        Log.info(s"Using cached helper for ${Type[A].prettyPrint}") >>
          MIO.pure(helperCall)
      case None =>
        ctx.getInstance[A].flatMap {
          case Some(instance) =>
            Log.info(s"Using cached instance for ${Type[A].prettyPrint}") >>
              MIO.pure(instance)
          case None =>
            tryInlineLeafType[A].flatMap {
              case Some(result) => MIO.pure(result)
              case None =>
                ctx.setHelper[A](deriveResultRecursivelyViaRules[A]) >>
                  ctx.getHelper[A].flatMap {
                    case Some(helperCall) => MIO.pure(helperCall)
                    case None =>
                      MIO.fail(new Exception(s"Failed to build helper for ${Type[A].prettyPrint}"))
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
          ctx.setInstance[A](instance) >>
            MIO.pure(Some(instance))
        case Left(_) =>
          MIO.pure(None)
      }
  }

  private def deriveResultRecursivelyViaRules[A: DerivationCtx]: MIO[Expr[KindlingsBsonDocumentHandler[A]]] =
    Log
      .namedScope(s"Deriving BSONDocumentHandler for type ${Type[A].prettyPrint}") {
        Rules(
          UseImplicitWhenAvailableRule,
          HandleAsValueTypeRule,
          HandleAsOptionRule,
          HandleAsCaseClassRule,
          HandleAsEnumRule
        )(_[A]).flatMap {
          case Right(result) =>
            Log.info(s"Derived BSONDocumentHandler for ${Type[A].prettyPrint}: ${result.prettyPrint}") >>
              MIO.pure(result)
          case Left(reasons) =>
            val reasonsStrings = reasons.toListMap
              .view.map { case (rule, reasons) =>
                if (reasons.isEmpty) s"The rule ${rule.name} was not applicable"
                else
                  s" - The rule ${rule.name} was not applicable, for the following reasons: ${reasons.mkString(", ")}"
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
        Type[KindlingsBsonDocumentHandler[A]].summonExprIgnoring(Types.ignoredAutoDerivationMethods*).toEither match {
          case Right(instance) =>
            Log.info(s"Found implicit BSONDocumentHandler[${Type[A].prettyPrint}]") >>
              ctx.setInstance[A](instance) >>
              MIO.pure(Rule.matched(instance))
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
      else Type[A] match {
        case IsValueType(_) =>
          MIO.pure(Rule.yielded(s"${Type[A].prettyPrint} is a value type: derive via explicit implicit or use Macros.valueHandler from reactivemongo-bson-api"))
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
            deriveResultRecursively[Inner](using innerCtx).flatMap { innerHandlerExpr =>
              val handlerExpr = Expr.quote {
                new hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler[A] {
                  def readDocument(doc: reactivemongo.api.bson.BSONDocument): scala.util.Try[A] =
                    doc.get("value") match {
                      case None | Some(reactivemongo.api.bson.BSONNull) =>
                        scala.util.Success(None.asInstanceOf[A])
                      case Some(v) =>
                        Expr.splice(innerHandlerExpr).asInstanceOf[reactivemongo.api.bson.BSONReader[Inner]].readTry(v).map(_.asInstanceOf[A])
                    }
                  def writeTry(value: A): scala.util.Try[reactivemongo.api.bson.BSONDocument] =
                    value match {
                      case Some(v) =>
                        Expr.splice(innerHandlerExpr).asInstanceOf[reactivemongo.api.bson.BSONWriter[Inner]].writeTry(v.asInstanceOf[Inner]).map { bsonValue =>
                          reactivemongo.api.bson.BSONDocument("value" -> bsonValue)
                        }
                      case None =>
                        scala.util.Success(reactivemongo.api.bson.BSONDocument.empty)
                    }
                }
              }
              MIO.pure(Rule.matched(handlerExpr))
            }
          case _ =>
            MIO.pure(Rule.yielded(s"${Type[A].prettyPrint} is not an Option"))
        }
      }
  }

  object HandleAsCaseClassRule extends DerivationRule("handle as case class") {
    def apply[A: DerivationCtx]: MIO[Rule.Applicability[Expr[KindlingsBsonDocumentHandler[A]]]] =
      Log.info(s"Attempting to handle ${Type[A].prettyPrint} as a case class") >> {
        CaseClass.parse[A].toEither match {
          case Right(caseClass) =>
            deriveCaseClass[A](caseClass).map(Rule.matched)
          case Left(reason) =>
            MIO.pure(Rule.yielded(reason))
        }
      }

    private def isCaseClassOrEnum[A: Type]: Boolean =
      CaseClass.parse[A].toEither.isRight

    private def resolveFieldReader[F: Type](fieldCtx: DerivationCtx[F]): MIO[Expr[reactivemongo.api.bson.BSONReader[F]]] =
      if (isCaseClassOrEnum[F]) {
        deriveResultRecursively[F](using fieldCtx).map { handlerExpr =>
          handlerExpr.asInstanceOf[Expr[reactivemongo.api.bson.BSONReader[F]]]
        }
      } else {
        implicit val ReaderF: Type[reactivemongo.api.bson.BSONReader[F]] = Types.BsonReader[F]
        Type[reactivemongo.api.bson.BSONReader[F]].summonExprIgnoring().toEither match {
          case Right(reader) => MIO.pure(reader)
          case Left(_) =>
            val err = BsonDocumentHandlerDerivationError.CannotDeriveField(Type[F].prettyPrint, s"No BSONReader found")
            Log.error(err.message) >> MIO.fail(err)
        }
      }

    private def resolveFieldWriter[F: Type](fieldCtx: DerivationCtx[F]): MIO[Expr[reactivemongo.api.bson.BSONWriter[F]]] =
      if (isCaseClassOrEnum[F]) {
        deriveResultRecursively[F](using fieldCtx).map { handlerExpr =>
          handlerExpr.asInstanceOf[Expr[reactivemongo.api.bson.BSONWriter[F]]]
        }
      } else {
        implicit val WriterF: Type[reactivemongo.api.bson.BSONWriter[F]] = Types.BsonWriter[F]
        Type[reactivemongo.api.bson.BSONWriter[F]].summonExprIgnoring().toEither match {
          case Right(writer) => MIO.pure(writer)
          case Left(_) =>
            val err = BsonDocumentHandlerDerivationError.CannotDeriveField(Type[F].prettyPrint, s"No BSONWriter found")
            Log.error(err.message) >> MIO.fail(err)
        }
      }

    /** Build a field read expression, handling Option and default values. */
    private def buildFieldReadExpr[
        Field: Type
    ](
        docExpr: Expr[BSONDocument],
        fName: String,
        param: Parameter,
        fieldCtx: DerivationCtx[Field]
    ): MIO[Expr[scala.util.Try[Any]]] = {
      val fNameExpr: Expr[String] = Expr(fName)

      Type[Field] match {
        case IsOption(isOption) =>
          // Option[Inner]: missing -> use default if available, else None
          //                 BSONNull -> None
          //                 present -> innerReader.readTry(v).map(Some(_))
          import isOption.Underlying as Inner
          val innerCtx = fieldCtx.copy(tpe = Type[Inner])
          resolveFieldReader[Inner](innerCtx).map { innerReaderExpr =>
            // Check for default value on the Option field
            val defaultExprOpt: Option[Expr[Field]] =
              if (param.hasDefault)
                param.defaultValue.flatMap { existentialOuter =>
                  val methodOf = existentialOuter.value
                  methodOf.value match {
                    case noInstance: Method.NoInstance[?] =>
                      import noInstance.Returned
                      noInstance(Map.empty).toOption.map(_.asInstanceOf[Expr[Field]])
                    case _ => None
                  }
                }
              else None

            defaultExprOpt match {
              case Some(defaultExpr) =>
                Expr.quote {
                  Expr.splice(docExpr).get(Expr.splice(fNameExpr)) match {
                    case Some(_: reactivemongo.api.bson.BSONNull.type) =>
                      scala.util.Success(None).asInstanceOf[scala.util.Try[Any]]
                    case Some(v) =>
                      Expr.splice(innerReaderExpr).readTry(v.asInstanceOf[reactivemongo.api.bson.BSONValue]).map(Some(_)).asInstanceOf[scala.util.Try[Any]]
                    case None =>
                      scala.util.Success(Expr.splice(defaultExpr)).asInstanceOf[scala.util.Try[Any]]
                  }
                }
              case None =>
                Expr.quote {
                  Expr.splice(docExpr).get(Expr.splice(fNameExpr)) match {
                    case None | Some(_: reactivemongo.api.bson.BSONNull.type) =>
                      scala.util.Success(None).asInstanceOf[scala.util.Try[Any]]
                    case Some(v) =>
                      Expr.splice(innerReaderExpr).readTry(v.asInstanceOf[reactivemongo.api.bson.BSONValue]).map(Some(_)).asInstanceOf[scala.util.Try[Any]]
                  }
                }
            }
          }
        case _ =>
          // Non-optional field
          resolveFieldReader[Field](fieldCtx).map { readerExpr =>
            // Check for default value
            val defaultExprOpt: Option[Expr[Field]] =
              if (param.hasDefault)
                param.defaultValue.flatMap { existentialOuter =>
                  val methodOf = existentialOuter.value
                  methodOf.value match {
                    case noInstance: Method.NoInstance[?] =>
                      import noInstance.Returned
                      noInstance(Map.empty).toOption.map(_.asInstanceOf[Expr[Field]])
                    case _ => None
                  }
                }
              else None

            defaultExprOpt match {
              case Some(defaultExpr) =>
                // Has default: missing -> Success(default), null -> Failure, present -> read
                Expr.quote {
                  Expr.splice(docExpr).get(Expr.splice(fNameExpr)) match {
                    case Some(_: reactivemongo.api.bson.BSONNull.type) =>
                      scala.util.Failure(new NoSuchElementException("Field is null")).asInstanceOf[scala.util.Try[Any]]
                    case Some(v) =>
                      Expr.splice(readerExpr).readTry(v).asInstanceOf[scala.util.Try[Any]]
                    case None =>
                      scala.util.Success(Expr.splice(defaultExpr)).asInstanceOf[scala.util.Try[Any]]
                  }
                }
              case None =>
                // No default: missing/null -> Failure
                Expr.quote {
                  Expr.splice(docExpr).get(Expr.splice(fNameExpr)) match {
                    case Some(_: reactivemongo.api.bson.BSONNull.type) =>
                      scala.util.Failure(new NoSuchElementException("Field is null")).asInstanceOf[scala.util.Try[Any]]
                    case Some(v) =>
                      Expr.splice(readerExpr).readTry(v).asInstanceOf[scala.util.Try[Any]]
                    case None =>
                      scala.util.Failure(new NoSuchElementException("Field not found")).asInstanceOf[scala.util.Try[Any]]
                  }
                }
            }
          }
      }
    }

    /** Build a field write expression, handling Option fields by omitting None values. */
    private def buildFieldWriteExpr[
        Field: Type
    ](
        fName: String,
        fieldValue: Expr[Field],
        fieldCtx: DerivationCtx[Field]
    ): MIO[Expr[scala.util.Try[Option[reactivemongo.api.bson.BSONElement]]]] = {
      val fNameExpr: Expr[String] = Expr(fName)

      Type[Field] match {
        case IsOption(isOption) =>
          // Option[Inner]: None -> Success(None), Some(v) -> innerWriter.writeTry(v).map(el => Some(el))
          import isOption.Underlying as Inner
          val innerCtx = fieldCtx.copy(tpe = Type[Inner])
          resolveFieldWriter[Inner](innerCtx).map { innerWriterExpr =>
            Expr.quote {
              Expr.splice(fieldValue) match {
                case Some(v) =>
                  Expr.splice(innerWriterExpr).writeTry(v.asInstanceOf[Inner]).map { bsonValue =>
                    Some(reactivemongo.api.bson.BSONElement(Expr.splice(fNameExpr), bsonValue))
                  }
                case None =>
                  scala.util.Success(None)
              }
            }
          }
        case _ =>
          resolveFieldWriter[Field](fieldCtx).map { writerExpr =>
            Expr.quote {
              Expr.splice(writerExpr).writeTry(Expr.splice(fieldValue)).map { bsonValue =>
                Some(reactivemongo.api.bson.BSONElement(Expr.splice(fNameExpr), bsonValue))
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
        // Zero-field case class
        caseClass.construct[MIO](new CaseClass.ConstructField[MIO] {
          def apply(field: Parameter): MIO[Expr[field.tpe.Underlying]] = {
            val err = BsonDocumentHandlerDerivationError.CannotConstructType(
              Type[A].prettyPrint,
              Some("Unexpected parameter in zero-argument case class")
            )
            Log.error(err.message) >> MIO.fail(err)
          }
        }).flatMap {
          case Some(constructExpr) =>
            val handlerExpr = Expr.quote {
              hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories.handlerInstance[A](
                readFn = { _ =>
                  scala.util.Success(Expr.splice(constructExpr))
                },
                writeFn = { _ =>
                  scala.util.Success(reactivemongo.api.bson.BSONDocument.empty)
                }
              )
            }
            MIO.pure(handlerExpr)
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
                  val fieldCtx = ctx.nest[Field]
                  buildFieldReadExpr[Field](docExpr, fName, param, fieldCtx)
                }
                constructLambda <- LambdaBuilder
                  .of1[Array[Any]]("arr")
                  .traverse { arrExpr =>
                    val fieldMap: Map[String, Expr_??] = fieldsList.zipWithIndex.map { case ((name, param), idx) =>
                      import param.tpe.Underlying as Field
                      val fieldExpr: Expr[Field] = Expr.quote {
                        Expr.splice(arrExpr)(Expr.splice(Expr(idx))).asInstanceOf[Field]
                      }
                      (name, fieldExpr.as_??)
                    }.toMap
                    caseClass.primaryConstructor(fieldMap) match {
                      case Right(constructExpr) => MIO.pure(constructExpr.asInstanceOf[Expr[A]])
                      case Left(error) =>
                        val err = BsonDocumentHandlerDerivationError.CannotConstructType(Type[A].prettyPrint, Some(error))
                        Log.error(err.message) >> MIO.fail(err)
                    }
                  }
                  .map(_.build[A])
              } yield {
                val listExpr = fieldReads.toList.foldRight(Expr.quote(List.empty[Try[Any]])) { case (read, acc) =>
                  Expr.quote(Expr.splice(read) :: Expr.splice(acc))
                }
                Expr.quote {
                  hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories.sequenceTries[A](
                    Expr.splice(listExpr),
                    Expr.splice(constructLambda)
                  )
                }
              }
            }
            .map(_.build[Try[A]])

          writeLambda <- LambdaBuilder
            .of1[A]("value")
            .traverse { valueExpr =>
              val fieldValues = caseClass.caseFieldValuesAt(valueExpr).toList
              val fieldValuesNel = NonEmptyList(fieldValues.head, fieldValues.tail)
              fieldValuesNel.parTraverse { case (fName, fieldValue) =>
                import fieldValue.Underlying as Field
                val fieldCtx = ctx.nest[Field]
                buildFieldWriteExpr[Field](fName, fieldValue.value.asInstanceOf[Expr[Field]], fieldCtx)
              }.map { etries =>
                val listTryExpr: Expr[Try[List[Option[reactivemongo.api.bson.BSONElement]]]] = etries.toList.foldRight(Expr.quote(scala.util.Success(List.empty[Option[reactivemongo.api.bson.BSONElement]]): Try[List[Option[reactivemongo.api.bson.BSONElement]]])) {
                  case (et, acc) =>
                    Expr.quote {
                      for {
                        tail <- Expr.splice(acc)
                        head <- Expr.splice(et)
                      } yield head :: tail
                    }
                }
                Expr.quote {
                  Expr.splice(listTryExpr).map { options =>
                    reactivemongo.api.bson.BSONDocument(options.flatten: _*)
                  }
                }
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
          case Right(_) =>
            MIO.pure(Rule.yielded(s"${Type[A].prettyPrint} is an enum: enum support not yet implemented in this version"))
          case Left(reason) =>
            MIO.pure(Rule.yielded(reason))
        }
      }
  }
}
