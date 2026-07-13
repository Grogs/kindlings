package hearth.kindlings.reactivemongobsonderivation

import reactivemongo.api.bson.*
import reactivemongo.api.bson as bson

final class BsonDocumentReaderSpec extends BsonDocumentHandlerSuite {

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

    test("reader unwraps directional value types") {
      val reader = KindlingsBsonDocumentReader.derived[WithValueType]
      assertEquals(
        reader.readDocument(BSONDocument("id" -> 42, "name" -> "test")).get,
        WithValueType(WrapperId(42), "test")
      )
    }

    test("reader derives singleton and recursive sealed ADTs independently") {
      val enumReader = KindlingsBsonDocumentReader.derived[SimpleEnum]
      val treeReader = KindlingsBsonDocumentReader.derived[Tree]
      assertEquals(
        enumReader.readDocument(BSONDocument("className" -> "hearth.kindlings.reactivemongobsonderivation.Foo")).get,
        Foo
      )
      val document = BSONDocument(
        "left" -> BSONDocument("data" -> "a", "className" -> "hearth.kindlings.reactivemongobsonderivation.TreeLeaf"),
        "right" -> BSONDocument("data" -> "b", "className" -> "hearth.kindlings.reactivemongobsonderivation.TreeLeaf"),
        "className" -> "hearth.kindlings.reactivemongobsonderivation.TreeNode"
      )
      assertEquals(treeReader.readDocument(document).get, TreeNode(TreeLeaf("a"), TreeLeaf("b")))
    }

    test("reader flattens nested records independently") {
      val reader = KindlingsBsonDocumentReader.derived[OuterFlatten]
      assertEquals(
        reader.readDocument(BSONDocument("a" -> 1, "b" -> 2, "c" -> "middle", "d" -> "outer")).get,
        OuterFlatten(MiddleFlatten(InnerFlatten(1, 2), "middle"), "outer")
      )
    }

    test("reader flattening honors a directional custom reader") {
      val reader = KindlingsBsonDocumentReader.derived[FlattenWithCustomIO]
      assertEquals(
        reader.readDocument(BSONDocument("name" -> "range", "start" -> 2, "end" -> 5)).get,
        FlattenWithCustomIO("range", Range(2, 5))
      )
    }

    test("reader flattening uses an external document reader") {
      implicit val externalReader: BSONDocumentReader[ExternalFlattened] = BSONDocumentReader.from { document =>
        scala.util.Success(ExternalFlattened(document.getAsTry[Int]("externalValue").get))
      }
      val reader = KindlingsBsonDocumentReader.derived[WithExternalFlatten]
      assertEquals(
        reader.readDocument(BSONDocument("name" -> "external", "externalValue" -> 42)).get,
        WithExternalFlatten("external", ExternalFlattened(42))
      )
    }

    test("reader applies optional, Scala, and annotation defaults independently") {
      assertEquals(
        KindlingsBsonDocumentReader.derived[MaybeName].readDocument(BSONDocument.empty).get,
        MaybeName(None)
      )
      assertEquals(
        KindlingsBsonDocumentReader.derived[WithDefault].readDocument(BSONDocument.empty).get,
        WithDefault("unknown")
      )
      assertEquals(
        KindlingsBsonDocumentReader.derived[WithAnnotatedDefaults].readDocument(BSONDocument("id" -> 1)).get,
        WithAnnotatedDefaults(1, "anon", 0)
      )
    }

    test("reader applies field naming and unexpected-field config independently") {
      implicit val config: BsonDocumentHandlerConfig =
        BsonDocumentHandlerConfig(skipUnexpectedFields = false).withSnakeCaseFieldNames
      val reader = KindlingsBsonDocumentReader.derived[CamelCaseFields]

      assertEquals(
        reader.readDocument(BSONDocument("first_name" -> "Alice", "last_name" -> "Smith", "age_in_years" -> 30)).get,
        CamelCaseFields("Alice", "Smith", 30)
      )
      assert(
        reader
          .readDocument(
            BSONDocument("first_name" -> "Alice", "last_name" -> "Smith", "age_in_years" -> 30, "extra" -> 1)
          )
          .isFailure
      )
    }

  }
}
