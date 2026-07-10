package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.MacroCommons
import hearth.fp.data.NonEmptyList
import hearth.fp.effect.*
import hearth.fp.syntax.*
import hearth.std.*
import hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler
import reactivemongo.api.bson.BSONDocument
import scala.util.Try

/** Derivation rule and field planning for case classes and record-like named tuples. */
trait BsonCaseClassDerivation {
  this: BsonDocumentHandlerMacrosImpl & MacroCommons & StdExtensions & AnnotationSupport =>

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
      BsonCaseClassDerivation.this.resolveBsonReader[F](fieldCtx)

    private def resolveFieldWriter[F: Type](
        fieldCtx: DerivationCtx[F]
    ): MIO[Expr[reactivemongo.api.bson.BSONWriter[F]]] =
      BsonCaseClassDerivation.this.resolveBsonWriter[F](fieldCtx)

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

    /** Resolve a document reader/writer for a flattened field. Prefer user-provided standard document type classes
      * before deriving one, matching ReactiveMongo's flatten behavior for externally-defined field types.
      */
    private def resolveFlattenedReader[Field: Type](
        fName: String,
        fieldCtx: DerivationCtx[Field]
    ): MIO[Expr[reactivemongo.api.bson.BSONDocumentReader[Field]]] = {
      implicit val ReaderT: Type[reactivemongo.api.bson.BSONDocumentReader[Field]] =
        Types.ExternalBsonDocumentReader[Field]
      Type[reactivemongo.api.bson.BSONDocumentReader[Field]]
        .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
        .toEither match {
        case Right(reader)                       => MIO.pure(reader)
        case Left(_) if isCaseClassOrEnum[Field] =>
          deriveResultRecursively[Field](fieldCtx)
            .map(_.asInstanceOf[Expr[reactivemongo.api.bson.BSONDocumentReader[Field]]])
        case Left(_) =>
          val err = BsonDocumentHandlerDerivationError.CannotFlattenNonDocumentField(fName, Type[Field].prettyPrint)
          Log.error(err.message) >> MIO.fail(err)
      }
    }

    private def resolveFlattenedWriter[Field: Type](
        fName: String,
        fieldCtx: DerivationCtx[Field]
    ): MIO[Expr[reactivemongo.api.bson.BSONDocumentWriter[Field]]] = {
      implicit val WriterT: Type[reactivemongo.api.bson.BSONDocumentWriter[Field]] =
        Types.ExternalBsonDocumentWriter[Field]
      Type[reactivemongo.api.bson.BSONDocumentWriter[Field]]
        .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
        .toEither match {
        case Right(writer)                       => MIO.pure(writer)
        case Left(_) if isCaseClassOrEnum[Field] =>
          deriveResultRecursively[Field](fieldCtx)
            .map(_.asInstanceOf[Expr[reactivemongo.api.bson.BSONDocumentWriter[Field]]])
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
      resolveFlattenedReader[Field](fName, fieldCtx).map { readerExpr =>
        Expr.quote {
          Expr.splice(readerExpr).readTry(Expr.splice(docExpr)).asInstanceOf[scala.util.Try[Any]]
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
          val wrapLambda = directLambda[Inner, Field] { innerExpr =>
            isValueType.value.wrap match {
              case _: CtorLikeOf.EitherStringOrValue[?, ?] =>
                val wrapped = isValueType.value.wrap.apply(innerExpr).asInstanceOf[Expr[Either[String, Field]]]
                Expr.quote {
                  Expr.splice(wrapped) match {
                    case scala.Right(value)  => value
                    case scala.Left(message) => throw new IllegalArgumentException(message)
                  }
                }
              case _ => isValueType.value.wrap.apply(innerExpr).asInstanceOf[Expr[Field]]
            }
          }
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
      resolveFlattenedWriter[Field](fName, fieldCtx).map { writerExpr =>
        Expr.quote {
          Expr.splice(writerExpr).writeTry(Expr.splice(fieldValue)).map { innerDoc =>
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
          val unwrapLambda = directLambda[Field, Inner](isValueType.value.unwrap)
          resolveFieldWriter[Inner](innerCtx).map { writerExpr =>
            Expr.quote {
              Expr.splice(writerExpr).writeTry(Expr.splice(unwrapLambda).apply(Expr.splice(fieldValue))).map { bsv =>
                List(Some(reactivemongo.api.bson.BSONElement(Expr.splice(fNameExpr), bsv)))
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
    ): MIO[Expr[KindlingsBsonDocumentHandler[A]]] =
      deriveRecord[A](
        caseClass.primaryConstructor,
        value => caseClass.caseFieldValuesAt(value).toList,
        () =>
          caseClass.construct[MIO](new CaseClass.ConstructField[MIO] {
            def apply(field: Parameter): MIO[Expr[field.tpe.Underlying]] = {
              val err = BsonDocumentHandlerDerivationError
                .CannotConstructType(Type[A].prettyPrint, Some("Unexpected parameter"))
              Log.error(err.message) >> MIO.fail(err)
            }
          })
      )

    /** Named tuples have record constructors but no case-class accessors. Their values are Products, so use the
      * constructor parameter index to read each element. Keeping this on the common record path also ensures their
      * decoding follows the same field-name, default, and unexpected-field semantics as case classes.
      */
    def deriveNamedTuple[A: DerivationCtx](namedTuple: NamedTuple[A]): MIO[Expr[KindlingsBsonDocumentHandler[A]]] = {
      val constructor = namedTuple.primaryConstructor
      deriveRecord[A](
        constructor,
        value =>
          constructor.parameters.flatten.toList.map { case (name, parameter) =>
            import parameter.tpe.Underlying as Field
            val index = Expr(parameter.index)
            name -> Expr.quote {
              Expr.splice(value).asInstanceOf[Product].productElement(Expr.splice(index)).asInstanceOf[Field]
            }.as_??
          },
        () => {
          val construct = foldInstanceFree(constructor, "Constructor")(
            onTypes = _ => Map.empty,
            onValues = _ => Map.empty
          ) match {
            case Right(expr) => Some(expr.value.asInstanceOf[Expr[A]])
            case Left(_)     => None
          }
          MIO.pure(construct)
        }
      )
    }

    private def deriveRecord[A: DerivationCtx](
        constructor: Method,
        fieldValuesAt: Expr[A] => List[(String, Expr_??)],
        constructEmpty: () => MIO[Option[Expr[A]]]
    ): MIO[Expr[KindlingsBsonDocumentHandler[A]]] = {
      implicit val BsonDocumentT: Type[BSONDocument] = Types.BsonDocument
      implicit val TryAT: Type[Try[A]] = Types.TryCtor[A]
      implicit val TryBsonDocumentT: Type[Try[BSONDocument]] = Types.TryCtor[BSONDocument]

      val fieldsList = constructor.parameters.flatten.toList

      if (fieldsList.isEmpty) {
        constructEmpty().flatMap {
          case Some(constructExpr) =>
            for {
              readBody <- ctx.cacheReadBody[A] { _ =>
                MIO.pure(Expr.quote(scala.util.Success(Expr.splice(constructExpr)): Try[A]))
              }
              writeBody <- ctx.cacheWriteBody[A] { _ =>
                MIO.pure(Expr.quote(scala.util.Success(BSONDocument.empty): Try[BSONDocument]))
              }
            } yield Expr.quote {
              hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories
                .handlerInstance[A](readFn = Expr.splice(readBody), writeFn = Expr.splice(writeBody))
            }
          case None =>
            val err = BsonDocumentHandlerDerivationError.CannotConstructType(Type[A].prettyPrint, None)
            Log.error(err.message) >> MIO.fail(err)
        }
      } else {
        val fieldsNel = NonEmptyList(fieldsList.head, fieldsList.tail)
        for {
          readLambda <- ctx.cacheReadBody[A] { docExpr =>
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
                val constructExpr: Expr[A] = foldInstanceFree(constructor, "Constructor")(
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

          writeLambda <- ctx.cacheWriteBody[A] { valueExpr =>
            val fieldValues = fieldValuesAt(valueExpr)
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
        } yield Expr.quote {
          hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories.handlerInstance[A](
            readFn = Expr.splice(readLambda),
            writeFn = Expr.splice(writeLambda)
          )
        }
      }
    }
  }

}
