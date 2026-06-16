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

      test("Seq[String]") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WordLover] = KindlingsBsonDocumentHandler.derived[WordLover]

        val value = WordLover("john", Seq("hello", "world"))
        val written = handler.writeTry(value).get
        assertEquals(handler.readDocument(written).get, value)
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

    group("flatten annotation") {

      test("@flatten merges inner case class fields into parent document") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[LabelledRange] =
          KindlingsBsonDocumentHandler.derived[LabelledRange]

        val value = LabelledRange("range1", Range(2, 5))
        val expectedDoc = BSONDocument("name" -> "range1", "start" -> 2, "end" -> 5)

        assertEquals(handler.writeTry(value).get, expectedDoc)
        assertEquals(handler.readDocument(expectedDoc).get, value)
      }

      test("@flatten works with nested flattening") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[OuterFlatten] =
          KindlingsBsonDocumentHandler.derived[OuterFlatten]

        val value = OuterFlatten(MiddleFlatten(InnerFlatten(1, 2), "middle"), "outer")
        val expectedDoc = BSONDocument("a" -> 1, "b" -> 2, "c" -> "middle", "d" -> "outer")

        assertEquals(handler.writeTry(value).get, expectedDoc)
        assertEquals(handler.readDocument(expectedDoc).get, value)
      }
    }

    group("type naming") {
      import hearth.kindlings.reactivemongobsonderivation.TypeNaming

      test("FullName discriminator includes enclosing objects") {
        given BsonDocumentHandlerConfig = BsonDocumentHandlerConfig().withTypeNaming(TypeNaming.FullName)

        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[TreeModule.Node] =
          KindlingsBsonDocumentHandler.derived[TreeModule.Node]

        val leaf = TreeModule.Leaf("data")
        val written = handler.writeTry(leaf).get

        // Full name should include the enclosing object
        val discriminator = written.get("className").map(_.asInstanceOf[BSONString].value)
        assertEquals(discriminator, Some("hearth.kindlings.reactivemongobsonderivation.TreeModule.Leaf"))

        assertEquals(handler.readDocument(written).get, leaf)
      }

      test("Custom type naming transforms simple name") {
        given BsonDocumentHandlerConfig = BsonDocumentHandlerConfig().withTypeNaming(TypeNaming.Custom(_.toLowerCase))

        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[SimpleEnum] =
          KindlingsBsonDocumentHandler.derived[SimpleEnum]

        val written = handler.writeTry(Foo).get
        val discriminator = written.get("className").map(_.asInstanceOf[BSONString].value)
        assertEquals(discriminator, Some("foo"))

        assertEquals(handler.readDocument(written).get, Foo)
      }

      test("FullName normalizes case object symbols") {
        given BsonDocumentHandlerConfig = BsonDocumentHandlerConfig().withTypeNaming(TypeNaming.FullName)

        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[Status] = KindlingsBsonDocumentHandler.derived[Status]

        val written = handler.writeTry(Active).get
        val discriminator = written.get("className").map(_.asInstanceOf[BSONString].value)
        assertEquals(discriminator, Some("hearth.kindlings.reactivemongobsonderivation.Active"))

        assertEquals(handler.readDocument(written).get, Active)
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

      test("FieldNaming structured API") {
        import hearth.kindlings.reactivemongobsonderivation.FieldNaming
        given BsonDocumentHandlerConfig = BsonDocumentHandlerConfig().withFieldNaming(FieldNaming.SnakeCase)

        val handler = KindlingsBsonDocumentHandler.derived[CamelCaseFields]
        val value = CamelCaseFields("Alice", "Smith", 30)

        val written = handler.writeTry(value).get
        assertEquals(written.get("first_name").map(_.asInstanceOf[BSONString].value), Some("Alice"))
        assertEquals(written.get("last_name").map(_.asInstanceOf[BSONString].value), Some("Smith"))
        assertEquals(written.get("age_in_years").map(_.asInstanceOf[BSONInteger].value), Some(30))

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

    group("reference ported tests") {

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

      test("support nesting") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[Pet] = KindlingsBsonDocumentHandler.derived[Pet]

        val value = Pet("woof", Person("john", 30))
        val written = handler.writeTry(value).get
        val expected = BSONDocument("name" -> "woof", "owner" -> BSONDocument("name" -> "john", "age" -> 30))
        assertEquals(written, expected)
        assertEquals(handler.readDocument(expected).get, value)
      }

      test("support optional") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[Optional] = KindlingsBsonDocumentHandler.derived[Optional]

        val some = Optional("some", Some("value"))
        val none = Optional("none", None)

        val someDoc = BSONDocument("name" -> "some", "value" -> "value")
        val noneDoc = BSONDocument("name" -> "none")

        assertEquals(handler.writeTry(some).get, someDoc)
        assertEquals(handler.writeTry(none).get, noneDoc)
        assertEquals(handler.readDocument(someDoc).get, some)
        assertEquals(handler.readDocument(noneDoc).get, none)
      }

      test("support optional as null") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[OptionalAsNull] =
          KindlingsBsonDocumentHandler.derived[OptionalAsNull]

        val value = OptionalAsNull("asNull", None)
        val expected = BSONDocument("name" -> "asNull", "value" -> BSONNull)
        assertEquals(handler.writeTry(value).get, expected)
        assertEquals(handler.readDocument(expected).get, value)
      }

      test("support single member options") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[OptionalSingle] =
          KindlingsBsonDocumentHandler.derived[OptionalSingle]

        val some = OptionalSingle(Some("foo"))
        val none = OptionalSingle(None)

        assertEquals(handler.readDocument(handler.writeTry(some).get).get, some)
        assertEquals(handler.readDocument(handler.writeTry(none).get).get, none)
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
        val written = handler.writeTry(value).get
        assertEquals(handler.readDocument(written).get, value)
      }

      test("support generic case class GenSeq") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[GenSeq[String]] =
          KindlingsBsonDocumentHandler.derived[GenSeq[String]]

        val value = GenSeq(Seq("hello", "world"), 2)
        val written = handler.writeTry(value).get
        assertEquals(handler.readDocument(written).get, value)
      }

      test("handle overloaded apply") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[OverloadedApply] =
          KindlingsBsonDocumentHandler.derived[OverloadedApply]

        val doc1 = OverloadedApply("hello")
        val doc2 = OverloadedApply(Seq("hello", "world"))

        assertEquals(handler.readDocument(handler.writeTry(doc1).get).get, doc1)
        assertEquals(handler.readDocument(handler.writeTry(doc2).get).get, doc2)
      }

      test("handle overloaded apply with different number of arguments") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[OverloadedApply2] =
          KindlingsBsonDocumentHandler.derived[OverloadedApply2]

        val doc1 = OverloadedApply2("hello", 5)
        val doc2 = OverloadedApply2("hello")

        assertEquals(handler.readDocument(handler.writeTry(doc1).get).get, doc1)
        assertEquals(handler.readDocument(handler.writeTry(doc2).get).get, doc2)
      }

      test("handle overloaded apply with 0 number of arguments") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[OverloadedApply3] =
          KindlingsBsonDocumentHandler.derived[OverloadedApply3]

        val doc1 = OverloadedApply3("hello", 5)
        val doc2 = OverloadedApply3()

        assertEquals(handler.readDocument(handler.writeTry(doc1).get).get, doc1)
        assertEquals(handler.readDocument(handler.writeTry(doc2).get).get, doc2)
      }

      test("handle case class inside trait with handler outside") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[NestModule.Nested] =
          KindlingsBsonDocumentHandler.derived[NestModule.Nested]

        val value = NestModule.Nested("it works")
        assertEquals(handler.readDocument(handler.writeTry(value).get).get, value)
      }

      test("not persist class name for case class") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[Address] = KindlingsBsonDocumentHandler.derived[Address]

        val written = handler.writeTry(Address("street", "city")).get
        assert(written.get("className").isEmpty, "Plain case class should not have className field")
      }

      test("handle empty case classes") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[Empty] = KindlingsBsonDocumentHandler.derived[Empty]

        val value = Empty()
        assertEquals(handler.readDocument(handler.writeTry(value).get).get, value)
      }

      test("support overriding keys with annotations") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[RenamedId] = KindlingsBsonDocumentHandler.derived[RenamedId]

        val value = RenamedId("id-value", "foo")
        val written = handler.writeTry(value).get
        assertEquals(written.get("_id").map(_.asInstanceOf[BSONString].value), Some("id-value"))
        assertEquals(written.get("value").map(_.asInstanceOf[BSONString].value), Some("foo"))
        assertEquals(handler.readDocument(written).get, value)
      }

      test("be generated for class with self reference") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[Bar] = KindlingsBsonDocumentHandler.derived[Bar]

        val bar1 = Bar("bar1", None)
        val bar2 = Bar("bar2", Some(bar1))

        assertEquals(handler.readDocument(handler.writeTry(bar1).get).get, bar1)
        assertEquals(handler.readDocument(handler.writeTry(bar2).get).get, bar2)
      }

      test("be generated for value class (wrapped)") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithValueTypeField] =
          KindlingsBsonDocumentHandler.derived[WithValueTypeField]

        val value = WithValueTypeField("foo", WithValueClass(42))
        val written = handler.writeTry(value).get
        // Our value-class handling wraps as {"value": <underlying>} rather than unwrapping inline
        assertEquals(written.get("name").map(_.asInstanceOf[BSONString].value), Some("foo"))
        assertEquals(
          written.get("id").flatMap(_.asInstanceOf[BSONDocument].get("value")).map(_.asInstanceOf[BSONInteger].value),
          Some(42)
        )
        assertEquals(handler.readDocument(written).get, value)
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

        val result = handler.readDocument(BSONDocument("id" -> 1)).get
        assertEquals(result.id, 1)
        assertEquals(result.title, "default2")
        assertEquals(result.range, Range(7, 11))
      }

      test("Map with String keys") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithMap1] = KindlingsBsonDocumentHandler.derived[WithMap1]

        val value = WithMap1("name", Map("en" -> "English", "fr" -> "French"))
        val written = handler.writeTry(value).get
        assertEquals(handler.readDocument(written).get, value)
      }
    }

  }
}
