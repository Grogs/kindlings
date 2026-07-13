package hearth.kindlings.reactivemongobsonderivation

import reactivemongo.api.bson.*
import reactivemongo.api.bson as bson

final class BsonDocumentHandlerSpec extends BsonDocumentHandlerSuite {

  group("KindlingsBsonDocumentHandler") {

    test("inline write entry point matches derived handler") {
      val value = Person("Alice", 30)
      val handler = KindlingsBsonDocumentHandler.derived[Person]

      val inlineDocument = KindlingsBsonDocumentHandler.write(value).get
      assertEquals(inlineDocument, handler.writeTry(value).get)
    }

    test("inline write handles value types, options, and empty records") {
      assertEquals(KindlingsBsonDocumentHandler.write(WrapperId(7)).get, BSONDocument("value" -> 7))
      val optionHandler = KindlingsBsonDocumentHandler.derived[Option[Int]]
      assertEquals(KindlingsBsonDocumentHandler.write[Option[Int]](Some(3)).get, optionHandler.writeTry(Some(3)).get)
      assertEquals(KindlingsBsonDocumentHandler.write[Option[Int]](None).get, optionHandler.writeTry(None).get)
      assertEquals(KindlingsBsonDocumentHandler.write(Empty()).get, BSONDocument.empty)
    }

    test("root wrappers preserve their document wire format") {
      val collection = List(1, 2, 3)
      val collectionDocument = BSONDocument("values" -> BSONArray(1, 2, 3))
      assertRoundTrip(KindlingsBsonDocumentHandler.derived[List[Int]], collection, collectionDocument)
      assertEquals(KindlingsBsonDocumentHandler.write(collection).get, collectionDocument)

      val map = Map("one" -> 1, "two" -> 2)
      val mapDocument = BSONDocument("one" -> 1, "two" -> 2)
      assertRoundTrip(KindlingsBsonDocumentHandler.derived[Map[String, Int]], map, mapDocument)
      assertEquals(KindlingsBsonDocumentHandler.write(map).get, mapDocument)

      assertRoundTrip(
        KindlingsBsonDocumentHandler.derived[WrapperId],
        WrapperId(7),
        BSONDocument("value" -> 7)
      )
    }

    test("inline write handles nested records") {
      val value = PersonWithAddress("Bob", Address("123 Main St", "Springfield"))
      assertEquals(
        KindlingsBsonDocumentHandler.write(value).get,
        BSONDocument("name" -> "Bob", "address" -> BSONDocument("street" -> "123 Main St", "city" -> "Springfield"))
      )
    }

    test("inline write handles nested maps") {
      case class MapHolder(values: Map[String, Int])
      assertEquals(
        KindlingsBsonDocumentHandler.write(MapHolder(Map("one" -> 1, "two" -> 2))).get,
        BSONDocument("values" -> BSONDocument("one" -> 1, "two" -> 2))
      )
    }

    test("inline write handles sealed traits") {
      assertEquals(
        KindlingsBsonDocumentHandler.write[SimpleEnum](Foo).get,
        BSONDocument("className" -> "hearth.kindlings.reactivemongobsonderivation.Foo")
      )
    }

    test("root derivation honors a BSONDocumentHandler implicit") {
      implicit val external: BSONDocumentHandler[Person] = BSONDocumentHandler[Person](
        _ => Person("from external", 0),
        _ => BSONDocument("external" -> true)
      )
      @scala.annotation.nowarn("msg=is never used|unused")
      val handler: KindlingsBsonDocumentHandler[Person] = KindlingsBsonDocumentHandler.derived[Person]

      assertEquals(handler.readDocument(BSONDocument.empty).get, Person("from external", 0))
      assertEquals(handler.writeTry(Person("ignored", 1)).get, BSONDocument("external" -> true))
    }

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

      test("@NoneAsNull - Some writes normally") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[MaybeAsNull] =
          KindlingsBsonDocumentHandler.derived[MaybeAsNull]

        val value = MaybeAsNull(Some("Alice"))
        val doc = BSONDocument("name" -> "Alice")

        assertEquals(handler.writeTry(value).get, doc)
        assertEquals(handler.readDocument(doc).get, value)
      }

      test("@NoneAsNull - None writes as BSONNull") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[MaybeAsNull] =
          KindlingsBsonDocumentHandler.derived[MaybeAsNull]

        val value = MaybeAsNull(None)
        val doc = BSONDocument("name" -> BSONNull)

        assertEquals(handler.writeTry(value).get, doc)
        assertEquals(handler.readDocument(doc).get, value)
      }

      test("@NoneAsNull - nested case class type") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[MaybeNestedAsNull] =
          KindlingsBsonDocumentHandler.derived[MaybeNestedAsNull]

        val valueSome = MaybeNestedAsNull(Some(Address("123 Main St", "Springfield")))
        val docSome = BSONDocument("address" -> BSONDocument("street" -> "123 Main St", "city" -> "Springfield"))

        assertEquals(handler.writeTry(valueSome).get, docSome)
        assertEquals(handler.readDocument(docSome).get, valueSome)

        val valueNone = MaybeNestedAsNull(None)
        val docNone = BSONDocument("address" -> BSONNull)

        assertEquals(handler.writeTry(valueNone).get, docNone)
        assertEquals(handler.readDocument(docNone).get, valueNone)
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

      test("@defaultValue annotation supplies default for missing field") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithAnnotatedDefaults] =
          KindlingsBsonDocumentHandler.derived[WithAnnotatedDefaults]

        // Only `id` is present; `name` and `score` use @defaultValue
        val doc = BSONDocument("id" -> 1)
        assertEquals(handler.readDocument(doc).get, WithAnnotatedDefaults(1, "anon", 0))

        // All present: defaults are not applied
        val fullDoc = BSONDocument("id" -> 2, "name" -> "Alice", "score" -> 42)
        assertEquals(handler.readDocument(fullDoc).get, WithAnnotatedDefaults(2, "Alice", 42))
      }
    }

    group("collection fields") {

      test("List[String]") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithList] = KindlingsBsonDocumentHandler.derived[WithList]

        val value = WithList(List("a", "b", "c"))

        val expected = BSONDocument("names" -> BSONArray("a", "b", "c"))
        val written = handler.writeTry(value).get
        assertEquals(written, expected)
        assertEquals(handler.readDocument(expected).get, value)
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
        val expected = BSONDocument("items" -> BSONDocument("a" -> 1, "b" -> 2))

        val written = handler.writeTry(value).get
        assertEquals(written, expected)
        assertEquals(handler.readDocument(expected).get, value)
      }

      test("Seq[String]") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WordLover] = KindlingsBsonDocumentHandler.derived[WordLover]

        val value = WordLover("john", Seq("hello", "world"))
        val expected = BSONDocument("name" -> "john", "words" -> BSONArray("hello", "world"))
        val written = handler.writeTry(value).get
        assertEquals(written, expected)
        assertEquals(handler.readDocument(expected).get, value)
      }

      test("single member case class") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[SingleBigDecimal] =
          KindlingsBsonDocumentHandler.derived[SingleBigDecimal]

        val value = SingleBigDecimal(BigDecimal("12.345"))
        val written = handler.writeTry(value).get
        assertEquals(handler.readDocument(written).get, value)
      }
    }

    group("value types") {

      test("AnyVal wrapper") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithValueType] = KindlingsBsonDocumentHandler.derived[WithValueType]

        val value = WithValueType(WrapperId(42), "test")
        // Value types unwrap to their underlying value inline in the parent document
        val doc = BSONDocument("id" -> 42, "name" -> "test")

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
  }
}
