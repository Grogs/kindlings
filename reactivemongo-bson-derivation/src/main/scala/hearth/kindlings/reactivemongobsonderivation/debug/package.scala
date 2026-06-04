package hearth.kindlings.reactivemongobsonderivation

package object debug {
  implicit val logDerivationForBsonDocumentHandler: KindlingsBsonDocumentHandler.LogDerivation =
    KindlingsBsonDocumentHandler.LogDerivation
}
