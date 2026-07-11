package hearth.kindlings.reactivemongobsonderivation.internal.runtime

import reactivemongo.api.bson.BSONDocument
import scala.util.Try

object BsonDocumentHandlerFactories {

  /** Creates a [[KindlingsBsonDocumentHandler]] from read/write functions. */
  def handlerInstance[A](
      readFn: BSONDocument => Try[A],
      writeFn: A => Try[BSONDocument]
  ): hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler[A] =
    new hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler[A] {
      def readDocument(doc: BSONDocument): Try[A] = readFn(doc)
      def writeTry(value: A): Try[BSONDocument] = writeFn(value)
    }
}
