package hearth.kindlings.reactivemongobsonderivation

/** Opt in to structural BSON handler derivation when
  * `reactivemongoBsonDerivation.policy.enabled=opt-in` is configured.
  */
package object policy {
  implicit val allowDerivationForReactiveMongoBson: KindlingsBsonDocumentHandler.AllowDerivation =
    KindlingsBsonDocumentHandler.AllowDerivation
}
