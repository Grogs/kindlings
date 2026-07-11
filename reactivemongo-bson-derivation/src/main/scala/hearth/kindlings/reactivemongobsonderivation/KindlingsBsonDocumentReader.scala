package hearth.kindlings.reactivemongobsonderivation

import reactivemongo.api.bson.BSONDocumentReader

/** A ReactiveMongo document reader produced by Kindlings' independent read derivation. */
trait KindlingsBsonDocumentReader[A] extends BSONDocumentReader[A]
object KindlingsBsonDocumentReader extends KindlingsBsonDocumentReaderCompanionCompat
