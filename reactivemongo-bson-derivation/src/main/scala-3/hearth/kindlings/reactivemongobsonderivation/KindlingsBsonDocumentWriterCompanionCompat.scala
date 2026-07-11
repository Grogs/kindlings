package hearth.kindlings.reactivemongobsonderivation

private[reactivemongobsonderivation] trait KindlingsBsonDocumentWriterCompanionCompat {
  this: KindlingsBsonDocumentWriter.type =>

  inline given derived[A](using config: BsonDocumentHandlerConfig): KindlingsBsonDocumentWriter[A] = ${
    internal.compiletime.BsonDocumentHandlerMacros.deriveWriterTypeClassImpl[A]('config)
  }
}
