package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.MacroCommons
import hearth.fp.effect.*
import hearth.fp.syntax.*
import hearth.std.*
import hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler
import reactivemongo.api.bson.BSONDocument
import scala.util.Try

/** Derivation rule for collections and maps, including generated iteration callbacks. */
trait BsonCollectionDerivation {
  this: BsonDocumentHandlerMacrosImpl & MacroCommons & StdExtensions & AnnotationSupport =>

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
        readLambda <- ctx.cacheReadBody[A] { docExpr =>
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
        writeLambda <- ctx.cacheWriteBody[A] { valueExpr =>
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
        readLambda <- ctx.cacheReadBody[A] { docExpr =>
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
        writeLambda <- ctx.cacheWriteBody[A] { valueExpr =>
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

}
