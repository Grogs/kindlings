package hearth.kindlings.reactivemongobsonderivation

import scala.language.experimental.macros

private[reactivemongobsonderivation] trait KindlingsBsonDocumentReaderCompanionCompat {
  this: KindlingsBsonDocumentReader.type =>

  implicit def derived[A](implicit config: BsonDocumentHandlerConfig): KindlingsBsonDocumentReader[A] =
    macro internal.compiletime.BsonDocumentHandlerMacros.deriveReaderTypeClassImpl[A]
}
