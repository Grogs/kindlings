package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.*
import hearth.fp.effect.MIO
import hearth.fp.syntax.*
import hearth.std.*

import hearth.kindlings.reactivemongobsonderivation.{BsonDocumentHandlerConfig, KindlingsBsonDocumentHandler}
import reactivemongo.api.bson.BSONDocument

import scala.util.Try

/** Chooses and assembles the implementation behind the combined document-handler API.
  *
  * Structural records and enums compose the independent directional algebras. Root shapes whose public wire format is
  * defined by the original handler rules remain on the compatibility path until those directional algebras expose the
  * same document-level semantics.
  */
private[compiletime] trait BsonCombinedHandlerComposition {
  this: BsonDocumentHandlerMacrosImpl & MacroCommons & StdExtensions =>

  final protected def deriveCombinedHandler[A: Type](
      derivedType: Option[??],
      configExpr: Expr[BsonDocumentHandlerConfig],
      evaluatedConfig: Option[BsonDocumentHandlerConfig]
  ): MIO[Expr[KindlingsBsonDocumentHandler[A]]] =
    if (canComposeDirectionalHandler[A](derivedType))
      composeDirectionalHandler[A](configExpr, evaluatedConfig)
    else deriveCompatibilityHandler[A](derivedType, configExpr, evaluatedConfig)

  /** An explicit parent handler is always the root override. Collection-like and wrapper roots retain their established
    * document envelope, whereas records and enums have identical directional and combined wire formats.
    */
  private def canComposeDirectionalHandler[A: Type](derivedType: Option[??]): Boolean = {
    implicit val HandlerA: Type[KindlingsBsonDocumentHandler[A]] = Types.BsonDocumentHandler[A]
    implicit val ParentHandlerA: Type[reactivemongo.api.bson.BSONDocumentHandler[A]] =
      Types.ExternalBsonDocumentHandler[A]
    val rootHasKindlingsHandler =
      derivedType.exists(_.Underlying =:= Type[A]) &&
        Type[KindlingsBsonDocumentHandler[A]]
          .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
          .toOption
          .nonEmpty
    val hasExternalHandler =
      !rootHasKindlingsHandler &&
        Type[reactivemongo.api.bson.BSONDocumentHandler[A]]
          .summonExprIgnoring(Types.ignoredAutoDerivationMethods*)
          .toOption
          .nonEmpty

    !hasExternalHandler && !isDirectionalOption[A] && !isDirectionalMap[A] && !isDirectionalCollection[A] &&
    !isDirectionalValueType[A] && !Type[A].isNamedTuple && isCaseClassOrEnum[A]
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

  private def deriveCompatibilityHandler[A: Type](
      derivedType: Option[??],
      configExpr: Expr[BsonDocumentHandlerConfig],
      evaluatedConfig: Option[BsonDocumentHandlerConfig]
  ): MIO[Expr[KindlingsBsonDocumentHandler[A]]] = {
    val compatibilityCtx = DerivationCtx.from[A](derivedType, configExpr, evaluatedConfig)
    for {
      result <- deriveResultRecursively[A](using compatibilityCtx)
      cache <- compatibilityCtx.cache.get
    } yield cache.toValDefs.use(_ => result)
  }

  final protected def isCaseClassOrEnum[A: Type]: Boolean =
    CaseClass.parse[A].toEither.isRight || Enum.parse[A].toEither.isRight
}
