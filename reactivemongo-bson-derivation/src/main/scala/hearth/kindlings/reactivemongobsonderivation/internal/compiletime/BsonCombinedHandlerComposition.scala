package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.*
import hearth.fp.effect.MIO
import hearth.fp.syntax.*
import hearth.std.*

import hearth.kindlings.reactivemongobsonderivation.{BsonDocumentHandlerConfig, KindlingsBsonDocumentHandler}
import reactivemongo.api.bson.BSONDocument

import scala.util.Try

/** Assembles the combined document-handler API from the two independent directional algebras. */
private[compiletime] trait BsonCombinedHandlerComposition {
  this: BsonDocumentHandlerMacrosImpl & MacroCommons & StdExtensions =>

  final protected def deriveCombinedHandler[A: Type](
      derivedType: Option[??],
      configExpr: Expr[BsonDocumentHandlerConfig],
      evaluatedConfig: Option[BsonDocumentHandlerConfig]
  ): MIO[Expr[KindlingsBsonDocumentHandler[A]]] =
    rootExternalHandler[A](derivedType) match {
      case Some(parent) =>
        MIO.pure(Expr.quote {
          hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories
            .handlerInstance[A](
              (document: BSONDocument) => Expr.splice(parent).readDocument(document),
              (value: A) => Expr.splice(parent).writeTry(value)
            )
        })
      case None => composeDirectionalHandler[A](configExpr, evaluatedConfig)
    }

  /** An explicit parent handler remains the unconditional root override. Skip the Kindlings value currently being
    * initialized by a Scala 3 `derives` expansion.
    */
  private def rootExternalHandler[A: Type](
      derivedType: Option[??]
  ): Option[Expr[reactivemongo.api.bson.BSONDocumentHandler[A]]] = {
    implicit val HandlerA: Type[KindlingsBsonDocumentHandler[A]] = Types.BsonDocumentHandler[A]
    implicit val ParentHandlerA: Type[reactivemongo.api.bson.BSONDocumentHandler[A]] =
      Types.ExternalBsonDocumentHandler[A]
    val rootHasKindlingsHandler =
      derivedType.exists(_.Underlying =:= Type[A]) &&
        Type[KindlingsBsonDocumentHandler[A]]
          .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
          .toOption
          .nonEmpty
    if (rootHasKindlingsHandler) None
    else
      Type[reactivemongo.api.bson.BSONDocumentHandler[A]]
        .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
        .toOption
  }

  /** Derives both bodies at the outer quote scope. Separate caches permit `parTuple` error aggregation and keep each
    * recursive helper graph independent; both cache snapshots wrap the final factory instance.
    */
  private def composeDirectionalHandler[A: Type](
      configExpr: Expr[BsonDocumentHandlerConfig],
      evaluatedConfig: Option[BsonDocumentHandlerConfig]
  ): MIO[Expr[KindlingsBsonDocumentHandler[A]]] = {
    implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
    implicit val TryAT: Type[Try[A]] = Types.TryCtor[A]
    implicit val TryDocumentT: Type[Try[BSONDocument]] = Types.TryCtor[BSONDocument]
    val readerCtx = ReaderCtx.from[A](configExpr, evaluatedConfig)
    val writerCtx = WriterCtx.from[A](configExpr, evaluatedConfig)

    for {
      _ <- checkDerivationPolicyOncePerExpansion(Type[A].prettyPrint)
      _ <- deriveReaderBody[A](readerCtx).parTuple(deriveWriterBody[A](writerCtx))
      readerCall <- readerCtx.cache.get1Ary[BSONDocument, Try[A]]("cached-reader-body")
      writerCall <- writerCtx.cache.get1Ary[A, Try[BSONDocument]]("cached-writer-body")
      readerCache <- readerCtx.cache.get
      writerCache <- writerCtx.cache.get
    } yield (readerCall, writerCall) match {
      case (Some(read), Some(write)) =>
        readerCache.toValDefs.use { _ =>
          writerCache.toValDefs.use { _ =>
            Expr.quote {
              hearth.kindlings.reactivemongobsonderivation.internal.runtime.BsonDocumentHandlerFactories
                .handlerInstance[A](
                  (document: BSONDocument) => Expr.splice(read(Expr.quote(document))),
                  (value: A) => Expr.splice(write(Expr.quote(value)))
                )
            }
          }
        }
      case _ =>
        Environment.reportErrorAndAbort(s"No directional BSON document body generated for ${Type[A].prettyPrint}")
    }
  }
}
