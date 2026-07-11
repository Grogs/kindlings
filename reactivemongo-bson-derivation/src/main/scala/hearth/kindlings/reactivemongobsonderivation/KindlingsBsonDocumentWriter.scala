package hearth.kindlings.reactivemongobsonderivation

import reactivemongo.api.bson.BSONDocumentWriter

/** A ReactiveMongo document writer produced by Kindlings' independent write derivation. */
trait KindlingsBsonDocumentWriter[A] extends BSONDocumentWriter[A]
