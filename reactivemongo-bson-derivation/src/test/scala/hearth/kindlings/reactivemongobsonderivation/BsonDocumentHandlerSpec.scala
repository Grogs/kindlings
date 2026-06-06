package hearth.kindlings.reactivemongobsonderivation

import hearth.MacroSuite
import reactivemongo.api.bson.*

final class BsonDocumentHandlerSpec extends MacroSuite {

  group("KindlingsBsonDocumentHandler") {

    test("derive for empty case class") {
      @scala.annotation.nowarn("msg=is never used|unused")
      val handler: KindlingsBsonDocumentHandler[Empty] = KindlingsBsonDocumentHandler.derived[Empty]

      val doc = BSONDocument.empty
      val result = handler.readDocument(doc).get
      assertEquals(result, Empty())

      val written = handler.writeTry(Empty()).get
      assertEquals(written, BSONDocument.empty)
    }

    test("derive for simple case class") {
      @scala.annotation.nowarn("msg=is never used|unused")
      val handler: KindlingsBsonDocumentHandler[Person] = KindlingsBsonDocumentHandler.derived[Person]

      val person = Person("Alice", 30)
      val doc = BSONDocument("name" -> "Alice", "age" -> 30)

      assertEquals(handler.readDocument(doc).get, person)
      assertEquals(handler.writeTry(person).get, doc)
    }

    test("derive for nested case class") {
      @scala.annotation.nowarn("msg=is never used|unused")
      val handler: KindlingsBsonDocumentHandler[PersonWithAddress] =
        KindlingsBsonDocumentHandler.derived[PersonWithAddress]

      val person = PersonWithAddress("Bob", Address("123 Main St", "Springfield"))
      val doc = BSONDocument(
        "name" -> "Bob",
        "address" -> BSONDocument("street" -> "123 Main St", "city" -> "Springfield")
      )

      assertEquals(handler.readDocument(doc).get, person)
      assertEquals(handler.writeTry(person).get, doc)
    }

    group("Option fields") {

      test("Option field - Some") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[MaybeName] = KindlingsBsonDocumentHandler.derived[MaybeName]

        val value = MaybeName(Some("Alice"))
        val doc = BSONDocument("name" -> "Alice")

        assertEquals(handler.readDocument(doc).get, value)
        assertEquals(handler.writeTry(value).get, doc)
      }

      test("Option field - None (missing field)") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[MaybeName] = KindlingsBsonDocumentHandler.derived[MaybeName]

        val value = MaybeName(None)
        val doc = BSONDocument.empty

        assertEquals(handler.readDocument(doc).get, value)
        assertEquals(handler.writeTry(value).get, doc)
      }

      test("Option field - None (explicit BSONNull)") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[MaybeName] = KindlingsBsonDocumentHandler.derived[MaybeName]

        val value = MaybeName(None)
        val doc = BSONDocument("name" -> BSONNull)

        assertEquals(handler.readDocument(doc).get, value)
      }

      test("Option field with nested type - Some") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[MaybeNested] = KindlingsBsonDocumentHandler.derived[MaybeNested]

        val value = MaybeNested(Some(Address("123 Main St", "Springfield")))
        val doc = BSONDocument("address" -> BSONDocument("street" -> "123 Main St", "city" -> "Springfield"))

        assertEquals(handler.readDocument(doc).get, value)
        assertEquals(handler.writeTry(value).get, doc)
      }

      test("Option field with nested type - None") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[MaybeNested] = KindlingsBsonDocumentHandler.derived[MaybeNested]

        val value = MaybeNested(None)
        val doc = BSONDocument.empty

        assertEquals(handler.readDocument(doc).get, value)
        assertEquals(handler.writeTry(value).get, doc)
      }
    }

    group("default values") {

      test("missing field uses default value") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithDefault] = KindlingsBsonDocumentHandler.derived[WithDefault]

        val doc = BSONDocument.empty
        assertEquals(handler.readDocument(doc).get, WithDefault("unknown"))
      }

      test("present field overrides default value") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithDefault] = KindlingsBsonDocumentHandler.derived[WithDefault]

        val doc = BSONDocument("name" -> "custom")
        assertEquals(handler.readDocument(doc).get, WithDefault("custom"))
      }

      test("write round-trip with default") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithDefault] = KindlingsBsonDocumentHandler.derived[WithDefault]

        val value = WithDefault("custom")
        val doc = BSONDocument("name" -> "custom")

        assertEquals(handler.writeTry(value).get, doc)
        assertEquals(handler.readDocument(doc).get, value)
      }

      test("Option field with Some default") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[OptionalDefault] =
          KindlingsBsonDocumentHandler.derived[OptionalDefault]

        val doc = BSONDocument.empty
        assertEquals(handler.readDocument(doc).get, OptionalDefault(Some("unknown")))
      }

      test("Option field with Some default overridden") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[OptionalDefault] =
          KindlingsBsonDocumentHandler.derived[OptionalDefault]

        val doc = BSONDocument("name" -> "custom")
        assertEquals(handler.readDocument(doc).get, OptionalDefault(Some("custom")))
      }
    }

    group("collection fields") {

      test("List[String]") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithList] = KindlingsBsonDocumentHandler.derived[WithList]

        val value = WithList(List("a", "b", "c"))

        // Write round-trip
        val written = handler.writeTry(value).get
        assertEquals(handler.readDocument(written).get, value)
      }

      test("Set[Int]") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithSet] = KindlingsBsonDocumentHandler.derived[WithSet]

        val value = WithSet(Set(1, 2, 3))

        val written = handler.writeTry(value).get
        assertEquals(handler.readDocument(written).get, value)
      }

      test("Map[String, Int]") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithMap] = KindlingsBsonDocumentHandler.derived[WithMap]

        val value = WithMap(Map("a" -> 1, "b" -> 2))

        val written = handler.writeTry(value).get
        assertEquals(handler.readDocument(written).get, value)
      }
    }

    group("value types") {

      test("AnyVal wrapper") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithValueType] = KindlingsBsonDocumentHandler.derived[WithValueType]

        val value = WithValueType(WrapperId(42), "test")
        // Value types wrap as {"value": <inner_bson>} in their own document
        val doc = BSONDocument("id" -> BSONDocument("value" -> 42), "name" -> "test")

        assertEquals(handler.readDocument(doc).get, value)
        assertEquals(handler.writeTry(value).get, doc)
      }
    }

    group("field name annotations") {

      test("@fieldName annotation remaps field") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[AnnotatedFields] =
          KindlingsBsonDocumentHandler.derived[AnnotatedFields]

        val value = AnnotatedFields("Alice", 30)
        val doc = BSONDocument("first_name" -> "Alice", "years_old" -> 30)

        assertEquals(handler.readDocument(doc).get, value)
        assertEquals(handler.writeTry(value).get, doc)
      }
    }

    group("enum / sealed trait") {

      test("sealed trait with case objects") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[SimpleEnum] = KindlingsBsonDocumentHandler.derived[SimpleEnum]

        // Test read
        val fooDoc = BSONDocument("@type" -> "Foo")
        assertEquals(handler.readDocument(fooDoc).get, Foo)

        val barDoc = BSONDocument("@type" -> "Bar")
        assertEquals(handler.readDocument(barDoc).get, Bar)

        // Test write
        assertEquals(handler.writeTry(Foo).get, fooDoc)
        assertEquals(handler.writeTry(Bar).get, barDoc)
      }

      test("sealed trait — round trip") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[SimpleEnum] = KindlingsBsonDocumentHandler.derived[SimpleEnum]

        val written = handler.writeTry(Foo).get
        val readBack = handler.readDocument(written).get
        assertEquals(readBack, Foo)
      }

      test("unknown discriminator fails") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[SimpleEnum] = KindlingsBsonDocumentHandler.derived[SimpleEnum]

        val unknownDoc = BSONDocument("@type" -> "Baz")
        assert(handler.readDocument(unknownDoc).isFailure)
      }

      test("sealed trait with case classes") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[Expr] = KindlingsBsonDocumentHandler.derived[Expr]

        val num = Num(42)
        val str = Str("hello")
        val noExpr = NoExpr

        // Test read
        assertEquals(handler.readDocument(BSONDocument("@type" -> "Num", "value" -> 42)).get, num)
        assertEquals(
          handler.readDocument(BSONDocument("@type" -> "Str", "value" -> "hello")).get,
          str
        )
        assertEquals(handler.readDocument(BSONDocument("@type" -> "NoExpr")).get, noExpr)

        // Test write
        assertEquals(handler.writeTry(num).get, BSONDocument("@type" -> "Num", "value" -> 42))
        assertEquals(handler.writeTry(str).get, BSONDocument("@type" -> "Str", "value" -> "hello"))
        assertEquals(handler.writeTry(noExpr).get, BSONDocument("@type" -> "NoExpr"))
      }
    }

  }
}
