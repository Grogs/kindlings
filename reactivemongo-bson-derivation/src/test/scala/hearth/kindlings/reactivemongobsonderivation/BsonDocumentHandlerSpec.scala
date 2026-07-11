package hearth.kindlings.reactivemongobsonderivation

import hearth.MacroSuite
import reactivemongo.api.bson.*
// On Scala 2.13, the macro-reified calls to `BSONDocument(... -> ...)` reference the package
// object via the term `bson` (i.e. `bson.ElementProducer`). Scala 3's `import x.*` exposes
// that term automatically; Scala 2's `import x._` does not, so we add the alias explicitly.
import reactivemongo.api.bson as bson

final class BsonDocumentHandlerSpec extends MacroSuite {

  group("standalone document readers") {

    test("reader derivation requires only nested BSONReader") {
      final case class ReadOnlySecret(value: String)
      final case class ReadRequest(secret: ReadOnlySecret)
      implicit val secretReader: BSONReader[ReadOnlySecret] = BSONReader.from {
        case BSONString(value) => scala.util.Success(ReadOnlySecret(value))
        case other             => scala.util.Failure(new IllegalArgumentException(s"Expected BSONString, got $other"))
      }

      val reader: BSONDocumentReader[ReadRequest] = KindlingsBsonDocumentReader.derived[ReadRequest]
      assertEquals(reader.readDocument(BSONDocument("secret" -> "token")).get, ReadRequest(ReadOnlySecret("token")))
    }

    test("root BSONDocumentReader overrides structural derivation") {
      final case class RootRead(value: String)
      implicit val rootReader: BSONDocumentReader[RootRead] =
        BSONDocumentReader.from(_ => scala.util.Success(RootRead("from-root-reader")))

      assertEquals(
        KindlingsBsonDocumentReader.derived[RootRead].readDocument(BSONDocument.empty).get,
        RootRead("from-root-reader")
      )
    }

    test("reader derives nested case classes without a writer") {
      final case class NestedRead(value: String)
      final case class ReadEnvelope(nested: NestedRead)

      val reader = KindlingsBsonDocumentReader.derived[ReadEnvelope]
      assertEquals(
        reader.readDocument(BSONDocument("nested" -> BSONDocument("value" -> "ok"))).get,
        ReadEnvelope(NestedRead("ok"))
      )
    }

    test("reader resolves Option and collection fields without writers") {
      final case class ReadItem(value: String)
      final case class ReadCollections(optional: Option[ReadItem], items: List[ReadItem])
      implicit val itemReader: BSONReader[ReadItem] = BSONReader.from {
        case BSONString(value) => scala.util.Success(ReadItem(value))
        case other             => scala.util.Failure(new IllegalArgumentException(s"Expected BSONString, got $other"))
      }

      val reader = KindlingsBsonDocumentReader.derived[ReadCollections]
      assertEquals(
        reader
          .readDocument(BSONDocument("optional" -> "one", "items" -> BSONArray("two", "three")))
          .get,
        ReadCollections(Some(ReadItem("one")), List(ReadItem("two"), ReadItem("three")))
      )
    }

    test("reader requires only KeyReader for map keys") {
      final case class ReadKey(value: Int)
      final case class ReadMap(values: Map[ReadKey, String])
      implicit val keyReader: KeyReader[ReadKey] = KeyReader(key => ReadKey(key.stripPrefix("id-").toInt))

      val reader = KindlingsBsonDocumentReader.derived[ReadMap]
      assertEquals(
        reader.readDocument(BSONDocument("values" -> BSONDocument("id-1" -> "one"))).get,
        ReadMap(Map(ReadKey(1) -> "one"))
      )
    }

    test("reader applies directional field names") {
      val reader = KindlingsBsonDocumentReader.derived[AnnotatedFields]
      assertEquals(
        reader.readDocument(BSONDocument("first_name" -> "Alice", "years_old" -> 30)).get,
        AnnotatedFields("Alice", 30)
      )
    }

    test("reader honors @Reader without consulting @Writer") {
      val reader = KindlingsBsonDocumentReader.derived[WithPerFieldIO]
      assertEquals(
        reader.readDocument(BSONDocument("id" -> "a", "name" -> "b")).get,
        WithPerFieldIO("a", "b")
      )
    }

    test("reader reconstructs @Ignore fields from defaults") {
      val reader = KindlingsBsonDocumentReader.derived[WithIgnoredField]
      assertEquals(
        reader.readDocument(BSONDocument("id" -> 1, "name" -> "test")).get,
        WithIgnoredField(1, visible = true, "test")
      )
    }
  }

  group("standalone document writers") {

    test("writer derivation requires only nested BSONWriter") {
      final case class WriteOnlySecret(value: String)
      final case class WriteRequest(secret: WriteOnlySecret)
      implicit val secretWriter: BSONWriter[WriteOnlySecret] = BSONWriter.from[WriteOnlySecret] { secret =>
        scala.util.Success(BSONString(secret.value))
      }

      val writer: BSONDocumentWriter[WriteRequest] = KindlingsBsonDocumentWriter.derived[WriteRequest]
      assertEquals(writer.writeTry(WriteRequest(WriteOnlySecret("token"))).get, BSONDocument("secret" -> "token"))
    }

    test("root BSONDocumentWriter overrides structural derivation") {
      final case class RootWrite(value: String)
      implicit val rootWriter: BSONDocumentWriter[RootWrite] =
        BSONDocumentWriter.from(_ => scala.util.Success(BSONDocument("source" -> "root-writer")))

      assertEquals(
        KindlingsBsonDocumentWriter.derived[RootWrite].writeTry(RootWrite("ignored")).get,
        BSONDocument("source" -> "root-writer")
      )
    }

    test("writer derives nested case classes without a reader") {
      final case class NestedWrite(value: String)
      final case class WriteEnvelope(nested: NestedWrite)

      val writer = KindlingsBsonDocumentWriter.derived[WriteEnvelope]
      assertEquals(
        writer.writeTry(WriteEnvelope(NestedWrite("ok"))).get,
        BSONDocument("nested" -> BSONDocument("value" -> "ok"))
      )
    }

    test("writer resolves Option and collection fields without readers") {
      final case class WriteItem(value: String)
      final case class WriteCollections(optional: Option[WriteItem], items: List[WriteItem])
      implicit val itemWriter: BSONWriter[WriteItem] =
        BSONWriter.from(item => scala.util.Success(BSONString(item.value)))

      val writer = KindlingsBsonDocumentWriter.derived[WriteCollections]
      assertEquals(
        writer.writeTry(WriteCollections(Some(WriteItem("one")), List(WriteItem("two"), WriteItem("three")))).get,
        BSONDocument("optional" -> "one", "items" -> BSONArray("two", "three"))
      )
    }

    test("writer requires only KeyWriter for map keys") {
      final case class WriteKey(value: Int)
      final case class WriteMap(values: Map[WriteKey, String])
      implicit val keyWriter: KeyWriter[WriteKey] = KeyWriter(key => s"id-${key.value}")

      val writer = KindlingsBsonDocumentWriter.derived[WriteMap]
      assertEquals(
        writer.writeTry(WriteMap(Map(WriteKey(1) -> "one"))).get,
        BSONDocument("values" -> BSONDocument("id-1" -> "one"))
      )
    }

    test("writer applies directional field names") {
      val writer = KindlingsBsonDocumentWriter.derived[AnnotatedFields]
      assertEquals(
        writer.writeTry(AnnotatedFields("Alice", 30)).get,
        BSONDocument("first_name" -> "Alice", "years_old" -> 30)
      )
    }

    test("writer honors @Writer without consulting @Reader") {
      val writer = KindlingsBsonDocumentWriter.derived[WithPerFieldIO]
      assertEquals(
        writer.writeTry(WithPerFieldIO("a", "b")).get,
        BSONDocument("id" -> "a", "name" -> "b")
      )
    }

    test("writer omits @Ignore fields before codec resolution") {
      val writer = KindlingsBsonDocumentWriter.derived[WithIgnoredField]
      assertEquals(
        writer.writeTry(WithIgnoredField(1, visible = false, "test")).get,
        BSONDocument("id" -> 1, "name" -> "test")
      )
    }
  }

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

    group("enum / sealed trait") {

      test("sealed trait with case objects") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[SimpleEnum] = KindlingsBsonDocumentHandler.derived[SimpleEnum]

        // Test read
        val fooDoc = BSONDocument("className" -> "hearth.kindlings.reactivemongobsonderivation.Foo")
        assertEquals(handler.readDocument(fooDoc).get, Foo)

        val barDoc = BSONDocument("className" -> "hearth.kindlings.reactivemongobsonderivation.Bar")
        assertEquals(handler.readDocument(barDoc).get, Bar)

        // Test write
        assertEquals(
          handler.writeTry(Foo).get,
          BSONDocument("className" -> "hearth.kindlings.reactivemongobsonderivation.Foo")
        )
        assertEquals(
          handler.writeTry(Bar).get,
          BSONDocument("className" -> "hearth.kindlings.reactivemongobsonderivation.Bar")
        )
      }

      test("sealed trait - round trip") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[SimpleEnum] = KindlingsBsonDocumentHandler.derived[SimpleEnum]

        val written = handler.writeTry(Foo).get
        val readBack = handler.readDocument(written).get
        assertEquals(readBack, Foo)
      }

      test("strict child decoding accepts the enum discriminator") {
        implicit val config: BsonDocumentHandlerConfig = BsonDocumentHandlerConfig(skipUnexpectedFields = false)
        val handler: KindlingsBsonDocumentHandler[Expr] = KindlingsBsonDocumentHandler.derived[Expr]

        assertEquals(
          handler
            .readDocument(
              BSONDocument("className" -> "hearth.kindlings.reactivemongobsonderivation.Num", "value" -> 42)
            )
            .get,
          Num(42)
        )
      }

      test("sealed child writer failures are returned") {
        val handler: KindlingsBsonDocumentHandler[WriteFailure] = KindlingsBsonDocumentHandler.derived[WriteFailure]
        assert(handler.writeTry(Broken("boom")).isFailure)
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
        assertEquals(
          handler
            .readDocument(
              BSONDocument("className" -> "hearth.kindlings.reactivemongobsonderivation.Num", "value" -> 42)
            )
            .get,
          num
        )
        assertEquals(
          handler
            .readDocument(
              BSONDocument("className" -> "hearth.kindlings.reactivemongobsonderivation.Str", "value" -> "hello")
            )
            .get,
          str
        )
        assertEquals(
          handler.readDocument(BSONDocument("className" -> "hearth.kindlings.reactivemongobsonderivation.NoExpr")).get,
          noExpr
        )

        // Test write
        assertEquals(
          handler.writeTry(num).get,
          BSONDocument("className" -> "hearth.kindlings.reactivemongobsonderivation.Num", "value" -> 42)
        )
        assertEquals(
          handler.writeTry(str).get,
          BSONDocument("className" -> "hearth.kindlings.reactivemongobsonderivation.Str", "value" -> "hello")
        )
        assertEquals(
          handler.writeTry(noExpr).get,
          BSONDocument("className" -> "hearth.kindlings.reactivemongobsonderivation.NoExpr")
        )
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

    group("custom implicit field handlers") {

      test("custom handlers work for refinement-typed fields") {
        implicit val prefKindReader: BSONReader[PrefKind.Aux[String]] = BSONReader.from { bsonValue =>
          bsonValue match {
            case BSONString(name) => scala.util.Success(PrefKind.of[String](name))
            case other => scala.util.Failure(new IllegalArgumentException(s"Expected BSONString, got $other"))
          }
        }
        implicit val prefKindWriter: BSONWriter[PrefKind.Aux[String]] = BSONWriter.from { kind =>
          scala.util.Success(BSONString(kind.name))
        }

        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[Preference[String]] =
          KindlingsBsonDocumentHandler.derived[Preference[String]]

        val value = Preference("_id", PrefKind.of[String]("ID"), "unique")
        val document = BSONDocument("key" -> "_id", "kind" -> "ID", "value" -> "unique")

        assertEquals(handler.readDocument(document).get, value)
        assertEquals(handler.writeTry(value).get, document)
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

      test("@Flatten merges inner case class fields into parent document") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[LabelledRange] =
          KindlingsBsonDocumentHandler.derived[LabelledRange]

        val value = LabelledRange("range1", Range(2, 5))
        val expectedDoc = BSONDocument("name" -> "range1", "start" -> 2, "end" -> 5)

        assertEquals(handler.writeTry(value).get, expectedDoc)
        assertEquals(handler.readDocument(expectedDoc).get, value)
      }

      test("@Flatten works with nested flattening") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[OuterFlatten] =
          KindlingsBsonDocumentHandler.derived[OuterFlatten]

        val value = OuterFlatten(MiddleFlatten(InnerFlatten(1, 2), "middle"), "outer")
        val expectedDoc = BSONDocument("a" -> 1, "b" -> 2, "c" -> "middle", "d" -> "outer")

        assertEquals(handler.writeTry(value).get, expectedDoc)
        assertEquals(handler.readDocument(expectedDoc).get, value)
      }

      test("@Flatten composes with @Reader and @Writer") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[FlattenWithCustomIO] =
          KindlingsBsonDocumentHandler.derived[FlattenWithCustomIO]

        val value = FlattenWithCustomIO("range", Range(2, 5))
        val expectedDoc = BSONDocument("name" -> "range", "start" -> 2, "end" -> 5)

        assertEquals(handler.writeTry(value).get, expectedDoc)
        assertEquals(handler.readDocument(expectedDoc).get, value)
      }

      test("@Flatten uses a user-provided BSONDocumentHandler") {
        implicit val externalHandler: BSONDocumentHandler[ExternalFlattened] = BSONDocumentHandler(
          read = document => ExternalFlattened(document.getAsTry[Int]("externalValue").get),
          write = value => BSONDocument("externalValue" -> value.value)
        )

        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithExternalFlatten] =
          KindlingsBsonDocumentHandler.derived[WithExternalFlatten]

        val value = WithExternalFlatten("external", ExternalFlattened(42))
        val expectedDoc = BSONDocument("name" -> "external", "externalValue" -> 42)

        assertEquals(handler.writeTry(value).get, expectedDoc)
        assertEquals(handler.readDocument(expectedDoc).get, value)
      }

      test("@Flatten uses separately provided BSONDocumentReader and BSONDocumentWriter") {
        implicit val externalReader: BSONDocumentReader[ExternallyReadWritten] = BSONDocumentReader.from { document =>
          scala.util.Success(ExternallyReadWritten(document.getAsTry[Int]("separateValue").get))
        }
        implicit val externalWriter: BSONDocumentWriter[ExternallyReadWritten] = BSONDocumentWriter { value =>
          BSONDocument("separateValue" -> value.value)
        }

        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithSeparateExternalFlatten] =
          KindlingsBsonDocumentHandler.derived[WithSeparateExternalFlatten]

        val value = WithSeparateExternalFlatten("external", ExternallyReadWritten(7))
        val expectedDoc = BSONDocument("name" -> "external", "separateValue" -> 7)

        assertEquals(handler.writeTry(value).get, expectedDoc)
        assertEquals(handler.readDocument(expectedDoc).get, value)
      }
    }

    group("type naming") {
      import hearth.kindlings.reactivemongobsonderivation.TypeNaming

      test("FullName discriminator includes enclosing objects") {
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

      test("SimpleName discriminator with nested sealed trait") {
        implicit val givenConfig: BsonDocumentHandlerConfig =
          BsonDocumentHandlerConfig().withTypeNaming(TypeNaming.SimpleName)

        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[TreeModule.Node] =
          KindlingsBsonDocumentHandler.derived[TreeModule.Node]

        val leaf = TreeModule.Leaf("data")
        val written = handler.writeTry(leaf).get

        // Simple name should NOT include the enclosing object
        val discriminator = written.get("className").map(_.asInstanceOf[BSONString].value)
        assertEquals(discriminator, Some("Leaf"))

        assertEquals(handler.readDocument(written).get, leaf)
      }

      test("Custom type naming transforms simple name") {
        implicit val givenConfig: BsonDocumentHandlerConfig =
          BsonDocumentHandlerConfig().withTypeNaming(TypeNaming.Custom(_.toLowerCase))

        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[SimpleEnum] =
          KindlingsBsonDocumentHandler.derived[SimpleEnum]

        val written = handler.writeTry(Foo).get
        val discriminator = written.get("className").map(_.asInstanceOf[BSONString].value)
        assertEquals(discriminator, Some("foo"))

        assertEquals(handler.readDocument(written).get, Foo)
      }

      test("FullName normalizes case object symbols") {
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
        implicit val givenConfig: BsonDocumentHandlerConfig =
          BsonDocumentHandlerConfig(discriminatorFieldName = Some("kind"))

        val handler = KindlingsBsonDocumentHandler.derived[SimpleEnum]

        // Write with custom discriminator
        val written = handler.writeTry(Foo).get
        val discriminatorField = written.get("kind")
        assert(discriminatorField.isDefined, "Should have 'kind' field as discriminator")
        assertEquals(
          discriminatorField.get.asInstanceOf[BSONString].value,
          "hearth.kindlings.reactivemongobsonderivation.Foo"
        )

        // Read with custom discriminator
        val result = handler.readDocument(written).get
        assertEquals(result, Foo)
      }

      test("snake_case field name mapper") {
        implicit val givenConfig: BsonDocumentHandlerConfig = BsonDocumentHandlerConfig().withSnakeCaseFieldNames

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
        implicit val givenConfig: BsonDocumentHandlerConfig = BsonDocumentHandlerConfig().withPascalCaseFieldNames

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
        implicit val givenConfig: BsonDocumentHandlerConfig =
          BsonDocumentHandlerConfig().withFieldNaming(FieldNaming.SnakeCase)

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
        implicit val givenConfig: BsonDocumentHandlerConfig = BsonDocumentHandlerConfig(skipUnexpectedFields = true)

        val handler = KindlingsBsonDocumentHandler.derived[Person]
        val doc = BSONDocument("name" -> "Alice", "age" -> 30, "extra" -> "ignored", "another" -> 42)

        val result = handler.readDocument(doc).get
        assertEquals(result, Person("Alice", 30))
      }

      test("skipUnexpectedFields=false rejects unknown fields") {
        implicit val givenConfig: BsonDocumentHandlerConfig = BsonDocumentHandlerConfig(skipUnexpectedFields = false)

        val handler = KindlingsBsonDocumentHandler.derived[Person]
        val doc = BSONDocument("name" -> "Alice", "age" -> 30, "extra" -> "ignored")

        val result = handler.readDocument(doc)
        assert(result.isFailure, "Should fail when unknown field is present")
      }

      test("runtime field-name mapper fallback captures call-site values") {
        def handlerWithPrefix(prefix: String): KindlingsBsonDocumentHandler[Person] = {
          implicit val config: BsonDocumentHandlerConfig =
            BsonDocumentHandlerConfig().withFieldNameMapper(name => prefix + name)
          KindlingsBsonDocumentHandler.derived[Person]
        }

        val handler = handlerWithPrefix("db_")
        val value = Person("Alice", 30)
        val document = BSONDocument("db_name" -> "Alice", "db_age" -> 30)

        assertEquals(handler.writeTry(value).get, document)
        assertEquals(handler.readDocument(document).get, value)
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
        val handler: KindlingsBsonDocumentHandler[GenSeq[Option[SingleBigDecimal]]] =
          KindlingsBsonDocumentHandler.derived[GenSeq[Option[SingleBigDecimal]]]

        val value = GenSeq(
          Seq(Some(SingleBigDecimal(BigDecimal("1.23"))), None, Some(SingleBigDecimal(BigDecimal("4.56")))),
          2
        )
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

        assertEquals(handler.readDocument(handler.writeTry(bar1).get).get, bar1)
        assertEquals(handler.readDocument(handler.writeTry(bar2).get).get, bar2)
      }

      test("be generated for value class") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithValueTypeField] =
          KindlingsBsonDocumentHandler.derived[WithValueTypeField]

        val value = WithValueTypeField("foo", WithValueClass(42))
        val written = handler.writeTry(value).get
        assertEquals(written, BSONDocument("name" -> "foo", "id" -> 42))
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

        assertEquals(
          handler.readDocument(BSONDocument("id" -> 1)).get,
          WithDefaultValues2(1, "default2", Some(45.6f), Range(7, 11))
        )
      }

      test("Map with String keys") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithMap1] = KindlingsBsonDocumentHandler.derived[WithMap1]

        val value = WithMap1("name", Map("en" -> "English", "fr" -> "French"))
        val written = handler.writeTry(value).get
        assertEquals(handler.readDocument(written).get, value)
      }

      test("Map with java.util.Locale keys") {
        import java.util.Locale
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithLocaleMap] =
          KindlingsBsonDocumentHandler.derived[WithLocaleMap]

        val value = WithLocaleMap("name", Map(Locale.FRANCE -> "French", Locale.GERMANY -> "German"))
        val written = handler.writeTry(value).get
        assertEquals(handler.readDocument(written).get, value)
      }

      test("Map with java.util.UUID keys") {
        import java.util.UUID
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithUUIDMap] =
          KindlingsBsonDocumentHandler.derived[WithUUIDMap]

        val id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        val value = WithUUIDMap(Map(id -> 1))
        val expected = BSONDocument("items" -> BSONDocument(id.toString -> 1))
        val written = handler.writeTry(value).get
        assertEquals(written, expected)
        assertEquals(handler.readDocument(expected).get, value)
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

        assertEquals(handler.writeTry(value).get, expected)
        assertEquals(handler.readDocument(expected).get, value)
      }

      test("@Ignore field is not serialized") {
        @scala.annotation.nowarn("msg=is never used|unused")
        val handler: KindlingsBsonDocumentHandler[WithIgnoredField] =
          KindlingsBsonDocumentHandler.derived[WithIgnoredField]

        val value = WithIgnoredField(1, visible = true, "test")
        val written = handler.writeTry(value).get
        // The @Ignore field should not appear in BSON
        assertEquals(written.get("visible"), None)
        assertEquals(written.get("id"), Some(BSONInteger(1)))
        assertEquals(written.get("name"), Some(BSONString("test")))

        // Reading back should use the default value for the ignored field
        val readBack = handler.readDocument(BSONDocument("id" -> 1, "name" -> "test")).get
        assertEquals(readBack, WithIgnoredField(1, visible = true, "test"))
      }

    }

    group("compile-time errors") {

      test("@Ignore without a default fails derivation") {
        compileErrors(
          """
          import hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler
          import hearth.kindlings.reactivemongobsonderivation.annotations.Ignore

          final case class InvalidIgnoredField(@Ignore value: Int)
          KindlingsBsonDocumentHandler.derived[InvalidIgnoredField]
          """
        ).check("Cannot ignore field value: scala.Int needs a Scala default value or @DefaultValue")
      }

      test("recursive @Flatten fails derivation") {
        compileErrors(
          """
          import hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler
          import hearth.kindlings.reactivemongobsonderivation.annotations.Flatten

          final case class InvalidRecursiveFlatten(value: String, @Flatten parent: InvalidRecursiveFlatten)
          KindlingsBsonDocumentHandler.derived[InvalidRecursiveFlatten]
          """
        ).check("Cannot flatten recursive field", "InvalidRecursiveFlatten.parent")
      }

      test("mutually recursive @Flatten fails derivation") {
        compileErrors(
          """
          import hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler
          import hearth.kindlings.reactivemongobsonderivation.annotations.Flatten

          final case class MutualFlattenA(@Flatten b: MutualFlattenB)
          final case class MutualFlattenB(@Flatten a: MutualFlattenA)
          KindlingsBsonDocumentHandler.derived[MutualFlattenA]
          """
        ).check("Cannot flatten recursive field", "MutualFlattenB.a")
      }

      test("@Flatten on a non-document field fails derivation") {
        compileErrors(
          """
          import hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler
          import hearth.kindlings.reactivemongobsonderivation.annotations.Flatten

          final case class InvalidNonDocumentFlatten(@Flatten value: String)
          KindlingsBsonDocumentHandler.derived[InvalidNonDocumentFlatten]
          """
        ).check("Cannot flatten field value: java.lang.String is not a case class or sealed trait")
      }

      test("Map with a non-String key and no key codecs fails derivation") {
        compileErrors(
          """
          import hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler

          final case class MissingKeyCodec(value: Int)
          final case class InvalidKeyMap(values: Map[MissingKeyCodec, String])
          KindlingsBsonDocumentHandler.derived[InvalidKeyMap]
          """
        ).check("Map key", "MissingKeyCodec", "requires both KeyReader and KeyWriter")
      }

      test("@Reader with the wrong field type fails derivation") {
        compileErrors(
          """
          import hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler
          import hearth.kindlings.reactivemongobsonderivation.annotations.Reader
          import reactivemongo.api.bson.BSONIntegerHandler

          final case class InvalidReader(@Reader(BSONIntegerHandler) value: String)
          KindlingsBsonDocumentHandler.derived[InvalidReader]
          """
        ).check("Invalid @Reader annotation for field value: BSONReader[java.lang.String] expected")
      }

      test("@Writer with the wrong field type fails derivation") {
        compileErrors(
          """
          import hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler
          import hearth.kindlings.reactivemongobsonderivation.annotations.Writer
          import reactivemongo.api.bson.BSONIntegerHandler

          final case class InvalidWriter(@Writer(BSONIntegerHandler) value: String)
          KindlingsBsonDocumentHandler.derived[InvalidWriter]
          """
        ).check("Invalid @Writer annotation for field value: BSONWriter[java.lang.String] expected")
      }

      test("duplicate @Reader annotations fail derivation") {
        compileErrors(
          """
          import hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler
          import hearth.kindlings.reactivemongobsonderivation.annotations.Reader
          import reactivemongo.api.bson.BSONStringHandler

          final case class DuplicateReader(@Reader(BSONStringHandler) @Reader(BSONStringHandler) value: String)
          KindlingsBsonDocumentHandler.derived[DuplicateReader]
          """
        ).check("At most one @Reader annotation is allowed for field value")
      }

      test("duplicate @Writer annotations fail derivation") {
        compileErrors(
          """
          import hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler
          import hearth.kindlings.reactivemongobsonderivation.annotations.Writer
          import reactivemongo.api.bson.BSONStringHandler

          final case class DuplicateWriter(@Writer(BSONStringHandler) @Writer(BSONStringHandler) value: String)
          KindlingsBsonDocumentHandler.derived[DuplicateWriter]
          """
        ).check("At most one @Writer annotation is allowed for field value")
      }
    }

  }
}
