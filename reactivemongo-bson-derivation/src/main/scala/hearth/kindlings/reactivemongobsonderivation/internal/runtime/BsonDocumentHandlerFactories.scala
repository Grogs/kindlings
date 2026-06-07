package hearth.kindlings.reactivemongobsonderivation.internal.runtime

import reactivemongo.api.bson.{BSONDocument, BSONValue}
import scala.util.{Failure, Success, Try}

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

  /** Sequences a list of [[scala.util.Try]] values and constructs a result. */
  def sequenceTries[T](tries: List[Try[Any]], construct: Array[Any] => T): Try[T] = {
    val result = new Array[Any](tries.size)
    var i = 0
    while (i < tries.size) {
      tries(i) match {
        case Success(v)    => result(i) = v
        case f: Failure[?] => return f.asInstanceOf[Failure[T]]
      }
      i += 1
    }
    Success(construct(result))
  }
}
