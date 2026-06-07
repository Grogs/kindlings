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

      test("@noneAsNull - Some writes normally") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[MaybeAsNull] =
          KindlingsBsonDocumentHandler.derived[MaybeAsNull]

        val value = MaybeAsNull(Some("Alice"))
        val doc = BSONDocument("name" -> "Alice")

        assertEquals(handler.writeTry(value).get, doc)
        assertEquals(handler.readDocument(doc).get, value)
      }

      test("@noneAsNull - None writes as BSONNull") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[MaybeAsNull] =
          KindlingsBsonDocumentHandler.derived[MaybeAsNull]

        val value = MaybeAsNull(None)
        val doc = BSONDocument("name" -> BSONNull)

        assertEquals(handler.writeTry(value).get, doc)
        assertEquals(handler.readDocument(doc).get, value)
      }

      test("@noneAsNull - nested case class type") {
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
        val fooDoc = BSONDocument("className" -> "Foo")
        assertEquals(handler.readDocument(fooDoc).get, Foo)

        val barDoc = BSONDocument("className" -> "Bar")
        assertEquals(handler.readDocument(barDoc).get, Bar)

        // Test write
        assertEquals(handler.writeTry(Foo).get, fooDoc)
        assertEquals(handler.writeTry(Bar).get, barDoc)
      }

      test("sealed trait - round trip") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[SimpleEnum] = KindlingsBsonDocumentHandler.derived[SimpleEnum]

        val written = handler.writeTry(Foo).get
        val readBack = handler.readDocument(written).get
        assertEquals(readBack, Foo)
      }

      test("unknown discriminator fails") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[SimpleEnum] = KindlingsBsonDocumentHandler.derived[SimpleEnum]

        val unknownDoc = BSONDocument("className" -> "Baz")
        assert(handler.readDocument(unknownDoc).isFailure)
      }

      test("sealed trait with case classes") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[Expr] = KindlingsBsonDocumentHandler.derived[Expr]

        val num = Num(42)
        val str = Str("hello")
        val noExpr = NoExpr

        // Test read
        assertEquals(handler.readDocument(BSONDocument("className" -> "Num", "value" -> 42)).get, num)
        assertEquals(
          handler.readDocument(BSONDocument("className" -> "Str", "value" -> "hello")).get,
          str
        )
        assertEquals(handler.readDocument(BSONDocument("className" -> "NoExpr")).get, noExpr)

        // Test write
        assertEquals(handler.writeTry(num).get, BSONDocument("className" -> "Num", "value" -> 42))
        assertEquals(handler.writeTry(str).get, BSONDocument("className" -> "Str", "value" -> "hello"))
        assertEquals(handler.writeTry(noExpr).get, BSONDocument("className" -> "NoExpr"))
      }

      test("recursive structure (Tree)") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[Tree] = KindlingsBsonDocumentHandler.derived[Tree]

        val tree: Tree = TreeNode(
          TreeLeaf("hi"),
          TreeNode(TreeLeaf("hello"), TreeLeaf("world"))
        )

        val written = handler.writeTry(tree).get
        val readBack = handler.readDocument(written).get
        assertEquals(readBack, tree)
      }
    }

    group("per-field reader / writer annotations") {

      test("@reader uses the provided BSONReader") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithPerFieldIO] =
          KindlingsBsonDocumentHandler.derived[WithPerFieldIO]

        val value = WithPerFieldIO("abc-123", "Alice")
        val doc = BSONDocument("id" -> "abc-123", "name" -> "Alice")

        // Read: BSONReader from annotation decodes `id`
        assertEquals(handler.readDocument(doc).get, value)
      }

      test("@writer uses the provided BSONWriter") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithPerFieldIO] =
          KindlingsBsonDocumentHandler.derived[WithPerFieldIO]

        val value = WithPerFieldIO("abc-123", "Alice")
        val doc = BSONDocument("id" -> "abc-123", "name" -> "Alice")

        // Write: BSONWriter from annotation writes `name`
        assertEquals(handler.writeTry(value).get, doc)
      }

      test("round-trip with @reader and @writer") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithPerFieldIO] =
          KindlingsBsonDocumentHandler.derived[WithPerFieldIO]

        val value = WithPerFieldIO("xyz", "Bob")
        val written = handler.writeTry(value).get
        assertEquals(handler.readDocument(written).get, value)
      }
    }

    group("config") {
      test("custom discriminator field name") {
        given BsonDocumentHandlerConfig = BsonDocumentHandlerConfig(discriminatorFieldName = Some("kind"))

        val handler = KindlingsBsonDocumentHandler.derived[SimpleEnum]

        // Write with custom discriminator
        val written = handler.writeTry(Foo).get
        val discriminatorField = written.get("kind")
        assert(discriminatorField.isDefined, "Should have 'kind' field as discriminator")
        assertEquals(discriminatorField.get.asInstanceOf[BSONString].value, "Foo")

        // Read with custom discriminator
        val result = handler.readDocument(written).get
        assertEquals(result, Foo)
      }

      test("snake_case field name mapper") {
        given BsonDocumentHandlerConfig = BsonDocumentHandlerConfig().withSnakeCaseFieldNames

        val handler = KindlingsBsonDocumentHandler.derived[CamelCaseFields]
        val value = CamelCaseFields("Alice", "Smith", 30)

        // Write should use snake_case keys
        val written = handler.writeTry(value).get
        assertEquals(written.get("first_name").map(_.asInstanceOf[BSONString].value), Some("Alice"))
        assertEquals(written.get("last_name").map(_.asInstanceOf[BSONString].value), Some("Smith"))
        assertEquals(written.get("age_in_years").map(_.asInstanceOf[BSONInteger].value), Some(30))
        // Original camelCase keys should NOT be present
        assert(written.get("firstName").isEmpty, "firstName should be mapped to first_name")

        // Read should also use snake_case keys
        val read = handler.readDocument(written).get
        assertEquals(read, value)
      }

      test("PascalCase field name mapper") {
        given BsonDocumentHandlerConfig = BsonDocumentHandlerConfig().withPascalCaseFieldNames

        val handler = KindlingsBsonDocumentHandler.derived[SnakeFields]
        val value = SnakeFields("Alice", "Smith")

        // Write should use PascalCase keys
        val written = handler.writeTry(value).get
        assertEquals(written.get("First_name").map(_.asInstanceOf[BSONString].value), Some("Alice"))
        assertEquals(written.get("Last_name").map(_.asInstanceOf[BSONString].value), Some("Smith"))

        // Read should also use PascalCase keys
        val read = handler.readDocument(written).get
        assertEquals(read, value)
      }

      test("skipUnexpectedFields=true ignores unknown fields") {
        given BsonDocumentHandlerConfig = BsonDocumentHandlerConfig(skipUnexpectedFields = true)

        val handler = KindlingsBsonDocumentHandler.derived[Person]
        val doc = BSONDocument("name" -> "Alice", "age" -> 30, "extra" -> "ignored", "another" -> 42)

        val result = handler.readDocument(doc).get
        assertEquals(result, Person("Alice", 30))
      }

      test("skipUnexpectedFields=false rejects unknown fields") {
        given BsonDocumentHandlerConfig = BsonDocumentHandlerConfig(skipUnexpectedFields = false)

        val handler = KindlingsBsonDocumentHandler.derived[Person]
        val doc = BSONDocument("name" -> "Alice", "age" -> 30, "extra" -> "ignored")

        val result = handler.readDocument(doc)
        assert(result.isFailure, "Should fail when unknown field is present")
      }
    }

  }
}
