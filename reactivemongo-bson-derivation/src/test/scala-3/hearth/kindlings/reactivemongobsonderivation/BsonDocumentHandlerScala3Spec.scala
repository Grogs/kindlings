package hearth.kindlings.reactivemongobsonderivation

import hearth.MacroSuite
import reactivemongo.api.bson.BSONDocument

final case class DerivesPerson(name: String, age: Int) derives KindlingsBsonDocumentHandler
final case class DerivesReader(name: String, age: Int) derives KindlingsBsonDocumentReader
final case class DerivesWriter(name: String, age: Int) derives KindlingsBsonDocumentWriter

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

    test("derives a standalone reader without self-initialization recursion") {
      val reader = summon[KindlingsBsonDocumentReader[DerivesReader]]
      assertEquals(
        reader.readDocument(BSONDocument("name" -> "Alice", "age" -> 30)).get,
        DerivesReader("Alice", 30)
      )
    }

    test("derives a standalone writer without self-initialization recursion") {
      val writer = summon[KindlingsBsonDocumentWriter[DerivesWriter]]
      assertEquals(
        writer.writeTry(DerivesWriter("Alice", 30)).get,
        BSONDocument("name" -> "Alice", "age" -> 30)
      )
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

    test("standalone single-element named tuple reader") {
      val reader = KindlingsBsonDocumentReader.derived[(field: Int)]
      val expected: (field: Int) = Tuple1(3)
      assertEquals(reader.readDocument(BSONDocument("field" -> 3)).get, expected)
    }

    test("standalone single-element named tuple writer") {
      val writer = KindlingsBsonDocumentWriter.derived[(field: Int)]
      val value: (field: Int) = Tuple1(3)
      assertEquals(writer.writeTry(value).get, BSONDocument("field" -> 3))
    }

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

  group("compile-time errors") {

    test("ambiguous field readers fail derivation") {
      compileErrors(
        """
        import hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentReader
        import reactivemongo.api.bson.BSONReader

        final class AmbiguousReaderSecret
        final case class AmbiguousReaderEnvelope(secret: AmbiguousReaderSecret)
        implicit val firstReader: BSONReader[AmbiguousReaderSecret] =
          BSONReader.from(_ => scala.util.Success(new AmbiguousReaderSecret))
        implicit val secondReader: BSONReader[AmbiguousReaderSecret] =
          BSONReader.from(_ => scala.util.Success(new AmbiguousReaderSecret))
        KindlingsBsonDocumentReader.derived[AmbiguousReaderEnvelope]
        """
      ).check("Ambiguous implicit")
    }

    test("ambiguous field writers fail derivation") {
      compileErrors(
        """
        import hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentWriter
        import reactivemongo.api.bson.{BSONString, BSONWriter}

        final class AmbiguousWriterSecret
        final case class AmbiguousWriterEnvelope(secret: AmbiguousWriterSecret)
        implicit val firstWriter: BSONWriter[AmbiguousWriterSecret] =
          BSONWriter.from(_ => scala.util.Success(BSONString("first")))
        implicit val secondWriter: BSONWriter[AmbiguousWriterSecret] =
          BSONWriter.from(_ => scala.util.Success(BSONString("second")))
        KindlingsBsonDocumentWriter.derived[AmbiguousWriterEnvelope]
        """
      ).check("Ambiguous implicit")
    }
  }
}
