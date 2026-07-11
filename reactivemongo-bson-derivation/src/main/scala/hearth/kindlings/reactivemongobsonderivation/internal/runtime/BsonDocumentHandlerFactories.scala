package hearth.kindlings.reactivemongobsonderivation.internal.runtime

import reactivemongo.api.bson.BSONDocument
import scala.util.Try

object BsonDocumentHandlerFactories {

  def readerInstance[A](
      readFn: BSONDocument => Try[A]
  ): hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentReader[A] =
    new hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentReader[A] {
      def readDocument(doc: BSONDocument): Try[A] = readFn(doc)
    }

  def writerInstance[A](
      writeFn: A => Try[BSONDocument]
  ): hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentWriter[A] =
    new hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentWriter[A] {
      def writeTry(value: A): Try[BSONDocument] = writeFn(value)
    }

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
