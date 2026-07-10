package hearth.kindlings.reactivemongobsonderivation

import scala.language.experimental.macros

private[reactivemongobsonderivation] trait KindlingsBsonDocumentHandlerCompanionCompat {
  this: KindlingsBsonDocumentHandler.type =>

  implicit def derived[A](implicit config: BsonDocumentHandlerConfig): KindlingsBsonDocumentHandler[A] =
    macro internal.compiletime.BsonDocumentHandlerMacros.deriveTypeClassImpl[A]

  /** Serialize `value` directly. Unlike `derived[A].writeTry(value)`, this macro emits only the BSON write path and
    * does not allocate a `KindlingsBsonDocumentHandler`.
    */
  def write[A](value: A)(implicit
      config: BsonDocumentHandlerConfig
  ): scala.util.Try[reactivemongo.api.bson.BSONDocument] =
    macro internal.compiletime.BsonDocumentHandlerMacros.deriveInlineImpl[A]
}
