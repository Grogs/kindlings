package hearth.kindlings.reactivemongobsonderivation

private[reactivemongobsonderivation] trait KindlingsBsonDocumentHandlerCompanionCompat {
  this: KindlingsBsonDocumentHandler.type =>

  inline given derived[A](using config: BsonDocumentHandlerConfig): KindlingsBsonDocumentHandler[A] = ${
    internal.compiletime.BsonDocumentHandlerMacros.deriveTypeClassImpl[A]('config)
  }
}
