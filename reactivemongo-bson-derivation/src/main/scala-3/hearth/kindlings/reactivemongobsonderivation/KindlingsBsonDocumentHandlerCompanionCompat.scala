package hearth.kindlings.reactivemongobsonderivation

private[reactivemongobsonderivation] trait KindlingsBsonDocumentHandlerCompanionCompat {
  this: KindlingsBsonDocumentHandler.type =>

  inline given derived[A](using config: BsonDocumentHandlerConfig): KindlingsBsonDocumentHandler[A] = ${
    internal.compiletime.BsonDocumentHandlerMacros.deriveTypeClassImpl[A]('config)
  }

  /** Serialize `value` directly. Unlike `derived[A].writeTry(value)`, this inline API emits only the BSON write path
    * and does not allocate a `KindlingsBsonDocumentHandler`.
    */
  inline def write[A](inline value: A)(using
      config: BsonDocumentHandlerConfig
  ): scala.util.Try[reactivemongo.api.bson.BSONDocument] =
    ${ internal.compiletime.BsonDocumentHandlerMacros.deriveInlineImpl[A]('value, 'config) }
}
