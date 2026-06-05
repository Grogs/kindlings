package hearth.kindlings.reactivemongobsonderivation.internal.runtime

import reactivemongo.api.bson.{BSONDocument, BSONElement, BSONNull, BSONReader, BSONValue, BSONWriter}
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

  /** Reads a required field from a BSONDocument. */
  def readField(doc: BSONDocument, name: String, reader: BSONReader[Any]): Try[Any] =
    doc.get(name) match {
      case Some(_: BSONNull.type) =>
        Failure(new NoSuchElementException(s"Field '$name' is null"))
      case Some(v) =>
        reader.readTry(v)
      case None =>
        Failure(new NoSuchElementException(s"Field '$name' not found"))
    }

  /** Reads an optional field from a BSONDocument. */
  def readOptionField(doc: BSONDocument, name: String, reader: BSONReader[Any]): Try[Any] =
    doc.get(name) match {
      case None | Some(_: BSONNull.type) =>
        Success(None)
      case Some(v) =>
        reader.readTry(v).map(Some(_))
    }

  /** Reads a field with a default value for when it's missing. */
  def readFieldWithDefault(doc: BSONDocument, name: String, reader: BSONReader[Any], default: Any): Try[Any] =
    doc.get(name) match {
      case Some(_: BSONNull.type) =>
        Failure(new NoSuchElementException(s"Field '$name' is null"))
      case Some(v) =>
        reader.readTry(v)
      case None =>
        Success(default)
    }

  /** Writes a field to a BSONElement. */
  def writeField(name: String, value: Any, writer: BSONWriter[Any]): Try[Option[BSONElement]] =
    writer.writeTry(value).map { bsonValue =>
      Some(BSONElement(name, bsonValue))
    }

  /** Writes an optional field to a BSONElement (None omits the field). */
  def writeOptionField(name: String, value: Option[Any], writer: BSONWriter[Any]): Try[Option[BSONElement]] =
    value match {
      case Some(v) =>
        writer.writeTry(v).map { bsonValue =>
          Some(BSONElement(name, bsonValue))
        }
      case None =>
        Success(None)
    }

  /** Sequences a list of Try[Option[BSONElement]] and builds a BSONDocument. */
  def sequenceOptionTries(tries: List[Try[Option[BSONElement]]]): Try[List[BSONElement]] = {
    var result = List.empty[BSONElement]
    var i = tries.size - 1
    while (i >= 0) {
      tries(i) match {
        case Success(Some(el)) => result = el :: result
        case Success(None)     => // skip
        case f: Failure[?]     => return f.asInstanceOf[Failure[List[BSONElement]]]
      }
      i -= 1
    }
    Success(result)
  }
}
