package hearth.kindlings.reactivemongobsonderivation

import reactivemongo.api.bson.*
import reactivemongo.api.bson as bson

final class BsonDocumentHandlerAdvancedSpec extends BsonDocumentHandlerSuite {

  group("advanced document handlers") {

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

      test("combined handler round trips a recursive sealed ADT") {
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
  }
}
