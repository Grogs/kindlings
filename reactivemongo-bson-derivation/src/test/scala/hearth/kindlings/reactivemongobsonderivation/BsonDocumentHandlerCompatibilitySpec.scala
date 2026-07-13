package hearth.kindlings.reactivemongobsonderivation

import reactivemongo.api.bson.*
import reactivemongo.api.bson as bson

final class BsonDocumentHandlerCompatibilitySpec extends BsonDocumentHandlerSuite {

  group("compatibility edge cases") {

      test("handle primitives") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[Primitives] = KindlingsBsonDocumentHandler.derived[Primitives]

        val value = Primitives(1.2, "hai", true, 42, Long.MaxValue)
        val written = handler.writeTry(value).get
        assertEquals(written.get("dbl").map(_.asInstanceOf[BSONDouble].value), Some(1.2))
        assertEquals(written.get("str").map(_.asInstanceOf[BSONString].value), Some("hai"))
        assertEquals(written.get("bl").map(_.asInstanceOf[BSONBoolean].value), Some(true))
        assertEquals(written.get("int").map(_.asInstanceOf[BSONInteger].value), Some(42))
        assertEquals(written.get("long").map(_.asInstanceOf[BSONLong].value), Some(Long.MaxValue))
        assertEquals(handler.readDocument(written).get, value)
      }

      test("support single member options") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[OptionalSingle] =
          KindlingsBsonDocumentHandler.derived[OptionalSingle]

        val some = OptionalSingle(Some("foo"))
        val none = OptionalSingle(None)

        assertSelfRoundTrips(handler, some, none)
      }

      test("support generic optional value") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[OptionalGeneric[String]] =
          KindlingsBsonDocumentHandler.derived[OptionalGeneric[String]]

        val none = OptionalGeneric[String](1, None)
        val some = OptionalGeneric(2, Some("foo"))

        assertEquals(handler.readDocument(BSONDocument("v" -> 1)).get, none)
        assertEquals(handler.readDocument(BSONDocument("v" -> 2, "opt" -> "foo")).get, some)
      }

      test("support generic case class Foo") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[Foo[SingleBigDecimal]] =
          KindlingsBsonDocumentHandler.derived[Foo[SingleBigDecimal]]

        val value = Foo(SingleBigDecimal(BigDecimal("1.23")), "ipsum")
        assertSelfRoundTrips(handler, value)
      }

      test("support generic case class GenSeq") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[GenSeq[Option[SingleBigDecimal]]] =
          KindlingsBsonDocumentHandler.derived[GenSeq[Option[SingleBigDecimal]]]

        val value = GenSeq(
          Seq(Some(SingleBigDecimal(BigDecimal("1.23"))), None, Some(SingleBigDecimal(BigDecimal("4.56")))),
          2
        )
        assertSelfRoundTrips(handler, value)
      }

      test("handle overloaded apply") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[OverloadedApply] =
          KindlingsBsonDocumentHandler.derived[OverloadedApply]

        val doc1 = OverloadedApply("hello")
        val doc2 = OverloadedApply(Seq("hello", "world"))

        assertSelfRoundTrips(handler, doc1, doc2)
      }

      test("handle overloaded apply with different number of arguments") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[OverloadedApply2] =
          KindlingsBsonDocumentHandler.derived[OverloadedApply2]

        val doc1 = OverloadedApply2("hello", 5)
        val doc2 = OverloadedApply2("hello")

        assertSelfRoundTrips(handler, doc1, doc2)
      }

      test("handle overloaded apply with 0 number of arguments") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[OverloadedApply3] =
          KindlingsBsonDocumentHandler.derived[OverloadedApply3]

        val doc1 = OverloadedApply3("hello", 5)
        val doc2 = OverloadedApply3()

        assertSelfRoundTrips(handler, doc1, doc2)
      }

      test("handle case class inside trait with handler outside") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[NestModule.Nested] =
          KindlingsBsonDocumentHandler.derived[NestModule.Nested]

        val value = NestModule.Nested("it works")
        assertSelfRoundTrips(handler, value)
      }

      test("support overriding keys with annotations") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[RenamedId] = KindlingsBsonDocumentHandler.derived[RenamedId]

        val id = reactivemongo.api.bson.BSONObjectID.generate()
        val value = RenamedId(id, "foo")
        val written = handler.writeTry(value).get
        assertEquals(written.get("_id").map(_.asInstanceOf[BSONObjectID]), Some(id))
        assertEquals(written.get("value").map(_.asInstanceOf[BSONString].value), Some("foo"))
        assertEquals(handler.readDocument(written).get, value)
      }

      test("be generated for class with self reference") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[Bar] = KindlingsBsonDocumentHandler.derived[Bar]

        val bar1 = Bar("bar1", None)
        val bar2 = Bar("bar2", Some(bar1))

        assertSelfRoundTrips(handler, bar1, bar2)
      }

      test("default values from Scala-level defaults") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithDefaultValues1] =
          KindlingsBsonDocumentHandler.derived[WithDefaultValues1]

        assertEquals(
          handler.readDocument(BSONDocument("id" -> 1)).get,
          WithDefaultValues1(1)
        )
      }

      test("default values from @defaultValue annotation") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithDefaultValues2] =
          KindlingsBsonDocumentHandler.derived[WithDefaultValues2]

        assertEquals(
          handler.readDocument(BSONDocument("id" -> 1)).get,
          WithDefaultValues2(1, "default2", Some(45.6f), Range(7, 11))
        )
      }

      test("Map with java.util.Locale keys") {
        import java.util.Locale
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithLocaleMap] =
          KindlingsBsonDocumentHandler.derived[WithLocaleMap]

        val value = WithLocaleMap("name", Map(Locale.FRANCE -> "French", Locale.GERMANY -> "German"))
        assertSelfRoundTrips(handler, value)
      }

      test("Map with java.util.UUID keys") {
        import java.util.UUID
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithUUIDMap] =
          KindlingsBsonDocumentHandler.derived[WithUUIDMap]

        val id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        val value = WithUUIDMap(Map(id -> 1))
        val expected = BSONDocument("items" -> BSONDocument(id.toString -> 1))
        assertRoundTrip(handler, value, expected)
      }

      test("Map with a user-provided value-class key codec") {
        implicit val fooValKeyReader: KeyReader[FooVal] =
          KeyReader(key => new FooVal(key.stripPrefix("id-").toInt))
        implicit val fooValKeyWriter: KeyWriter[FooVal] =
          KeyWriter(key => s"id-${key.v}")

        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithFooValMap] =
          KindlingsBsonDocumentHandler.derived[WithFooValMap]

        val value = WithFooValMap(Map(new FooVal(7) -> "seven"))
        val expected = BSONDocument("values" -> BSONDocument("id-7" -> "seven"))

        assertRoundTrip(handler, value, expected)
      }


    }
}
