package hearth.kindlings.reactivemongobsonderivation

private[reactivemongobsonderivation] trait KindlingsBsonDocumentHandlerCompanionCompat {
  this: KindlingsBsonDocumentHandler.type =>

  inline given derived[A]: KindlingsBsonDocumentHandler[A] = ${
    internal.compiletime.BsonDocumentHandlerMacros.deriveTypeClassImpl[A]
  }
}
