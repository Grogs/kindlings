package hearth.kindlings.reactivemongobsonderivation

import scala.language.experimental.macros

private[reactivemongobsonderivation] trait KindlingsBsonDocumentHandlerCompanionCompat {
  this: KindlingsBsonDocumentHandler.type =>

  implicit def derived[A](implicit config: BsonDocumentHandlerConfig): KindlingsBsonDocumentHandler[A] =
    macro internal.compiletime.BsonDocumentHandlerMacros.deriveTypeClassImpl[A]
}