package hearth.kindlings.reactivemongobsonderivation

import reactivemongo.api.bson.*
import reactivemongo.api.bson as bson

final class BsonDocumentWriterSpec extends BsonDocumentHandlerSuite {

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

    test("writer unwraps directional value types") {
      val writer = KindlingsBsonDocumentWriter.derived[WithValueType]
      assertEquals(
        writer.writeTry(WithValueType(WrapperId(42), "test")).get,
        BSONDocument("id" -> 42, "name" -> "test")
      )
    }

    test("writer derives singleton and recursive sealed ADTs independently") {
      val enumWriter = KindlingsBsonDocumentWriter.derived[SimpleEnum]
      val treeWriter = KindlingsBsonDocumentWriter.derived[Tree]
      assertEquals(
        enumWriter.writeTry(Foo).get,
        BSONDocument("className" -> "hearth.kindlings.reactivemongobsonderivation.Foo")
      )
      assertEquals(
        treeWriter.writeTry(TreeNode(TreeLeaf("a"), TreeLeaf("b"))).get.get("className"),
        Some(BSONString("hearth.kindlings.reactivemongobsonderivation.TreeNode"))
      )
    }

    test("writer flattens nested records independently") {
      val writer = KindlingsBsonDocumentWriter.derived[OuterFlatten]
      assertEquals(
        writer.writeTry(OuterFlatten(MiddleFlatten(InnerFlatten(1, 2), "middle"), "outer")).get,
        BSONDocument("a" -> 1, "b" -> 2, "c" -> "middle", "d" -> "outer")
      )
    }

    test("writer flattening honors a directional custom writer") {
      val writer = KindlingsBsonDocumentWriter.derived[FlattenWithCustomIO]
      assertEquals(
        writer.writeTry(FlattenWithCustomIO("range", Range(2, 5))).get,
        BSONDocument("name" -> "range", "start" -> 2, "end" -> 5)
      )
    }

    test("writer flattening uses an external document writer") {
      implicit val externalWriter: BSONDocumentWriter[ExternalFlattened] = BSONDocumentWriter { value =>
        BSONDocument("externalValue" -> value.value)
      }
      val writer = KindlingsBsonDocumentWriter.derived[WithExternalFlatten]
      assertEquals(
        writer.writeTry(WithExternalFlatten("external", ExternalFlattened(42))).get,
        BSONDocument("name" -> "external", "externalValue" -> 42)
      )
    }

    test("writer omits None unless @NoneAsNull is present") {
      assertEquals(
        KindlingsBsonDocumentWriter.derived[MaybeName].writeTry(MaybeName(None)).get,
        BSONDocument.empty
      )
      assertEquals(
        KindlingsBsonDocumentWriter.derived[MaybeAsNull].writeTry(MaybeAsNull(None)).get,
        BSONDocument("name" -> BSONNull)
      )
    }
  }
}
