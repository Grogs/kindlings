package hearth.kindlings.reactivemongobsonderivation

private[reactivemongobsonderivation] trait KindlingsBsonDocumentReaderCompanionCompat {
  this: KindlingsBsonDocumentReader.type =>

  inline given derived[A](using config: BsonDocumentHandlerConfig): KindlingsBsonDocumentReader[A] = ${
    internal.compiletime.BsonDocumentHandlerMacros.deriveReaderTypeClassImpl[A]('config)
  }
}
