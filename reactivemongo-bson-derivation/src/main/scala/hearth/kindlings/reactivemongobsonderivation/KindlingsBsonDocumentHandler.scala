package hearth.kindlings.reactivemongobsonderivation

import reactivemongo.api.bson.BSONDocumentHandler

trait KindlingsBsonDocumentHandler[A] extends BSONDocumentHandler[A]
object KindlingsBsonDocumentHandler extends KindlingsBsonDocumentHandlerCompanionCompat {

  /** Special type — if its implicit is in scope then macros will log the derivation process. */
  sealed trait LogDerivation
  object LogDerivation extends LogDerivation

  /** Opt-in marker for the ReactiveMongo BSON derivation policy. Import
    * [[hearth.kindlings.reactivemongobsonderivation.policy.allowDerivationForReactiveMongoBson]] when the policy is
    * configured as opt-in.
    */
  sealed trait AllowDerivation
  object AllowDerivation extends AllowDerivation
}
