package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.MacroCommons
import hearth.fp.effect.*
import hearth.std.*
import reactivemongo.api.bson.BSONDocument
import scala.util.Try

/** Resolves existing ReactiveMongo codecs and structurally derives codecs for nested fields. */
trait BsonCodecResolution {
  this: BsonDocumentHandlerMacrosImpl & MacroCommons & StdExtensions & AnnotationSupport =>

  protected def ensureMapKeyCodecs[A: Type](): Unit = Type[A] match {
    case IsMap(isMap) =>
      import isMap.Underlying as Pair
      ensureMapKeyCodecsOf[A, Pair](isMap.value)
    case _ => ()
  }

  protected def ensureMapKeyCodecsOf[A: Type, Pair: Type](isMap: IsMapOf[A, Pair]): Unit = {
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

  protected def deriveInlineCollectionReader[A: Type, Item: Type](
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
          import isCollection.CtorResult
          val readFnExpr = directLambda[reactivemongo.api.bson.BSONArray, scala.util.Try[A]] { arrExpr =>
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
            Expr.quote(scala.util.Try(Expr.splice(buildResultExpr.asInstanceOf[Expr[A]])))
          }
          MIO.pure(Expr.quote {
            new reactivemongo.api.bson.BSONReader[A] {
              def readTry(bson: reactivemongo.api.bson.BSONValue): scala.util.Try[A] = bson match {
                case arr: reactivemongo.api.bson.BSONArray => Expr.splice(readFnExpr).apply(arr)
                case _                                     =>
                  scala.util.Failure(
                    new IllegalArgumentException(
                      s"Expected BSONArray for collection, got ${bson.getClass.getSimpleName}"
                    )
                  )
              }
            }
          })
        case _ =>
          val err = BsonDocumentHandlerDerivationError.CannotDeriveField(
            Type[A].prettyPrint,
            "Collection build step must be PlainValue for inline reading"
          )
          Log.error(err.message) >> MIO.fail(err)
      }
    }
  }

  protected def deriveInlineMapReader[A: Type, Pair: Type](
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
    implicit val BsonDocumentT: Type[BSONDocument] = Types.BsonDocument
    implicit val TryBsonDocumentT: Type[Try[BSONDocument]] = Types.TryCtor[BSONDocument]
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
          case _ if isCaseClassOrEnum[A] && fieldCtx.writeOnly =>
            // Inline writing must recurse through cached write defs, not through a derived handler instance.
            fieldCtx.cache.get1Ary[A, Try[BSONDocument]]("cached-write-body").flatMap {
              case Some(writeCall) => MIO.pure(writerFromDocumentWrite[A](writeCall))
              case None            =>
                deriveResultRecursivelyViaRules[A](using fieldCtx) >>
                  fieldCtx.cache.get1Ary[A, Try[BSONDocument]]("cached-write-body").flatMap {
                    case Some(writeCall) => MIO.pure(writerFromDocumentWrite[A](writeCall))
                    case None            =>
                      MIO.fail(
                        BsonDocumentHandlerDerivationError.CannotDeriveField(
                          Type[A].prettyPrint,
                          "No cached BSON document writer found"
                        )
                      )
                  }
            }
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

  /** Adapt a cached document-write def to ReactiveMongo's BSONWriter for nested structural fields. */
  protected def writerFromDocumentWrite[A: Type](
      writeCall: Expr[A] => Expr[Try[BSONDocument]]
  ): Expr[reactivemongo.api.bson.BSONWriter[A]] =
    Expr.quote {
      new reactivemongo.api.bson.BSONWriter[A] {
        def writeTry(value: A): Try[reactivemongo.api.bson.BSONValue] =
          Expr.splice(writeCall(Expr.quote(value))).map(document => document: reactivemongo.api.bson.BSONValue)
      }
    }

  protected def deriveInlineCollectionWriter[A: Type, Item: Type](
      isCollection: IsCollectionOf[A, Item],
      fieldCtx: DerivationCtx[A]
  ): MIO[Expr[reactivemongo.api.bson.BSONWriter[A]]] = {
    @scala.annotation.nowarn("msg=is never used")
    implicit val BsonValueT: Type[reactivemongo.api.bson.BSONValue] = Types.BsonValue
    @scala.annotation.nowarn("msg=is never used")
    implicit val TryBsonValueT: Type[scala.util.Try[reactivemongo.api.bson.BSONValue]] =
      Types.TryCtor[reactivemongo.api.bson.BSONValue]
    resolveBsonWriter[Item](fieldCtx.nest[Item]).map { itemWriterExpr =>
      val writeFnExpr = directLambda[A, scala.util.Try[reactivemongo.api.bson.BSONValue]] { valueExpr =>
        Expr.quote {
          val iterable = Expr.splice(isCollection.asIterable(valueExpr)).asInstanceOf[Iterable[Item]]
          val items = iterable.iterator
          val builder = scala.collection.mutable.ListBuffer.empty[reactivemongo.api.bson.BSONValue]
          var err: Throwable = null
          while (err == null && items.hasNext)
            try builder += Expr.splice(itemWriterExpr).writeTry(items.next()).get
            catch { case e: Exception => err = e }
          if (err != null) scala.util.Failure(err)
          else scala.util.Success(reactivemongo.api.bson.BSONArray(builder.result()): reactivemongo.api.bson.BSONValue)
        }
      }
      Expr.quote {
        new reactivemongo.api.bson.BSONWriter[A] {
          def writeTry(value: A): scala.util.Try[reactivemongo.api.bson.BSONValue] =
            Expr.splice(writeFnExpr).apply(value)
        }
      }
    }
  }

  protected def deriveInlineMapWriter[A: Type, Pair: Type](
      isMap: IsMapOf[A, Pair],
      fieldCtx: DerivationCtx[A]
  ): MIO[Expr[reactivemongo.api.bson.BSONWriter[A]]] = {
    @scala.annotation.nowarn("msg=is never used")
    implicit val BsonValueT: Type[reactivemongo.api.bson.BSONValue] = Types.BsonValue
    @scala.annotation.nowarn("msg=is never used")
    implicit val TryBsonValueT: Type[scala.util.Try[reactivemongo.api.bson.BSONValue]] =
      Types.TryCtor[reactivemongo.api.bson.BSONValue]
    import isMap.{Key, Value}
    implicit val KeyWriterT: Type[reactivemongo.api.bson.KeyWriter[Key]] = Types.KeyWriter[Key]
    Type[reactivemongo.api.bson.KeyWriter[Key]].summonExprIgnoring(Types.ignoredAutoDerivationMethods*).toEither match {
      case Left(reason) => MIO.fail(BsonDocumentHandlerDerivationError.CannotDeriveField(Type[Key].prettyPrint, reason))
      case Right(keyWriterExpr) =>
        resolveBsonWriter[Value](fieldCtx.nest[Value]).map { valueWriterExpr =>
          val writeFnExpr = directLambda[A, scala.util.Try[reactivemongo.api.bson.BSONValue]] { valueExpr =>
            Expr.quote {
              val pairs = Expr.splice(isMap.asIterable(valueExpr)).asInstanceOf[Iterable[(Key, Value)]]
              val iter = pairs.iterator
              val elements = scala.collection.mutable.ListBuffer.empty[reactivemongo.api.bson.BSONElement]
              var err: Throwable = null
              while (err == null && iter.hasNext) {
                val (key, value) = iter.next()
                try {
                  val name = Expr.splice(keyWriterExpr).writeTry(key).get
                  elements += reactivemongo.api.bson.BSONElement(name, Expr.splice(valueWriterExpr).writeTry(value).get)
                } catch { case e: Exception => err = e }
              }
              if (err != null) scala.util.Failure(err)
              else
                scala.util.Success(
                  reactivemongo.api.bson.BSONDocument(elements.result()*): reactivemongo.api.bson.BSONValue
                )
            }
          }
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
    val annotationName = "hearth.kindlings.reactivemongobsonderivation.annotations.Reader"
    if (annotationTypeConstructorCount(param, annotationName) > 1)
      Environment.reportErrorAndAbort(s"At most one @Reader annotation is allowed for field ${param.name}")
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
            annotationName
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
  protected def annotateReaderValue[A: Type](untyped: UntypedExpr): Expr[reactivemongo.api.bson.BSONReader[A]] = {
    implicit val ReaderT: Type[reactivemongo.api.bson.BSONReader[A]] = Types.BsonReader[A]
    Expr.quote {
      Expr
        .splice(untyped.asTyped[reactivemongo.api.bson.BSONReader[A]])
        .asInstanceOf[reactivemongo.api.bson.BSONReader[A]]
    }
  }

  /** Try to extract a @writer-annotated BSONWriter for a field. Returns None if no annotation. */
  def annotatedWriter[A: Type](param: Parameter): Option[Expr[reactivemongo.api.bson.BSONWriter[A]]] = {
    val annotationName = "hearth.kindlings.reactivemongobsonderivation.annotations.Writer"
    if (annotationTypeConstructorCount(param, annotationName) > 1)
      Environment.reportErrorAndAbort(s"At most one @Writer annotation is allowed for field ${param.name}")
    val annTpe = Type.of[hearth.kindlings.reactivemongobsonderivation.annotations.Writer[A]]
    getAnnotationValueUntyped(param)(annTpe) match {
      case Some(untyped) => Some(annotateWriterValue[A](untyped))
      case None
          if hasAnnotationTypeConstructor(
            param,
            annotationName
          ) =>
        Environment.reportErrorAndAbort(
          s"Invalid @Writer annotation for field ${param.name}: BSONWriter[${Type[A].prettyPrint}] expected"
        )
      case None => None
    }
  }

  protected def annotateWriterValue[A: Type](untyped: UntypedExpr): Expr[reactivemongo.api.bson.BSONWriter[A]] = {
    implicit val WriterT: Type[reactivemongo.api.bson.BSONWriter[A]] = Types.BsonWriter[A]
    Expr.quote {
      Expr
        .splice(untyped.asTyped[reactivemongo.api.bson.BSONWriter[A]])
        .asInstanceOf[reactivemongo.api.bson.BSONWriter[A]]
    }
  }

}
