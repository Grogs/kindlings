package hearth.kindlings.reactivemongobsonderivation

import hearth.MacroSuite
import reactivemongo.api.bson.BSONDocument

final case class DerivesPerson(name: String, age: Int) derives KindlingsBsonDocumentHandler

final class BsonDocumentHandlerScala3Spec extends MacroSuite {

  group("Scala 3 derives") {

    test("derives a Kindlings BSON document handler subtype") {
      val value = DerivesPerson("Alice", 30)
      val document = BSONDocument("name" -> "Alice", "age" -> 30)
      val handler = summon[KindlingsBsonDocumentHandler[DerivesPerson]]

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
