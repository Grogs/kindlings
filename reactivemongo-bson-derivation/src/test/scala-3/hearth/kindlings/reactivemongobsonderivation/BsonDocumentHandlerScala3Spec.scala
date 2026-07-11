package hearth.kindlings.reactivemongobsonderivation

import hearth.MacroSuite
import reactivemongo.api.bson.BSONDocument

final case class DerivesPerson(name: String, age: Int) derives KindlingsBsonDocumentHandler
final case class DerivesReaderPerson(name: String, age: Int) derives KindlingsBsonDocumentReader
final case class DerivesWriterPerson(name: String, age: Int) derives KindlingsBsonDocumentWriter

object OpaqueBsonTypes {
  opaque type UserId = Int

  object UserId {
    def apply(value: Int): UserId = value
  }

  final case class User(id: UserId, name: String)
}

final class BsonDocumentHandlerScala3Spec extends MacroSuite {

  group("Scala 3 derives") {

    test("derives a Kindlings BSON document handler subtype") {
      val value = DerivesPerson("Alice", 30)
      val document = BSONDocument("name" -> "Alice", "age" -> 30)
      val handler = summon[KindlingsBsonDocumentHandler[DerivesPerson]]

      assertEquals(handler.writeTry(value).get, document)
      assertEquals(handler.readDocument(document).get, value)
    }

    test("derives standalone reader and writer subtypes") {
      val document = BSONDocument("name" -> "Alice", "age" -> 30)
      val reader = summon[KindlingsBsonDocumentReader[DerivesReaderPerson]]
      val writer = summon[KindlingsBsonDocumentWriter[DerivesWriterPerson]]

      assertEquals(reader.readDocument(document).get, DerivesReaderPerson("Alice", 30))
      assertEquals(writer.writeTry(DerivesWriterPerson("Alice", 30)).get, document)
    }
  }

  group("opaque aliases") {

    test("opaque field round-trip through its underlying value") {
      import OpaqueBsonTypes.*

      val handler = KindlingsBsonDocumentHandler.derived[User]
      val value = User(UserId(42), "Alice")
      val document = BSONDocument("id" -> 42, "name" -> "Alice")

      assertEquals(handler.writeTry(value).get, document)
      assertEquals(handler.readDocument(document).get, value)
    }
  }

  group("named tuples (Scala 3.7+)") {

    test("single-element named tuple round-trip") {
      val handler = KindlingsBsonDocumentHandler.derived[(field: Int)]
      val value: (field: Int) = Tuple1(3)
      val document = BSONDocument("field" -> 3)

      assertEquals(handler.writeTry(value).get, document)
      assertEquals(handler.readDocument(document).get, value)
    }

    test("multi-element named tuple round-trip") {
      val handler = KindlingsBsonDocumentHandler.derived[(name: String, age: Int)]
      val value: (name: String, age: Int) = ("Alice", 42)
      val document = BSONDocument("name" -> "Alice", "age" -> 42)

      assertEquals(handler.writeTry(value).get, document)
      assertEquals(handler.readDocument(document).get, value)
    }
  }
}
