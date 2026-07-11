package hearth.kindlings.reactivemongobsonderivation

import scala.language.experimental.macros

private[reactivemongobsonderivation] trait KindlingsBsonDocumentWriterCompanionCompat {
  this: KindlingsBsonDocumentWriter.type =>

  implicit def derived[A](implicit config: BsonDocumentHandlerConfig): KindlingsBsonDocumentWriter[A] =
    macro internal.compiletime.BsonDocumentHandlerMacros.deriveWriterTypeClassImpl[A]
}
