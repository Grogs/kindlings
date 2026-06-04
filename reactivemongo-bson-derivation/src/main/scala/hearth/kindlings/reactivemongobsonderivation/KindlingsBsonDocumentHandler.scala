package hearth.kindlings.reactivemongobsonderivation

import reactivemongo.api.bson.BSONDocumentHandler

trait KindlingsBsonDocumentHandler[A] extends BSONDocumentHandler[A]
object KindlingsBsonDocumentHandler extends KindlingsBsonDocumentHandlerCompanionCompat {

  /** Special type — if its implicit is in scope then macros will log the derivation process. */
  sealed trait LogDerivation
  object LogDerivation extends LogDerivation
}
