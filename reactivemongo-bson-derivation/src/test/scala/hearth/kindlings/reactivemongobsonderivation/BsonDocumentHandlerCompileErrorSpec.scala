package hearth.kindlings.reactivemongobsonderivation

import reactivemongo.api.bson.*
import reactivemongo.api.bson as bson

final class BsonDocumentHandlerCompileErrorSpec extends BsonDocumentHandlerSuite {

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

      test("Map with a non-String key and no key codecs reports both directional failures") {
        compileErrors(
          """
          import hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler

          final case class MissingKeyCodec(value: Int)
          final case class InvalidKeyMap(values: Map[MissingKeyCodec, String])
          KindlingsBsonDocumentHandler.derived[InvalidKeyMap]
          """
        ).check("Map key", "MissingKeyCodec", "requires a KeyReader", "requires a KeyWriter")
      }

      test("reader-only field reports the missing writer") {
        compileErrors(
          """
          import hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler
          import reactivemongo.api.bson.{BSONReader, BSONString}

          final class ReadOnlySecret
          final case class ReadOnlyEnvelope(secret: ReadOnlySecret)
          implicit val secretReader: BSONReader[ReadOnlySecret] = BSONReader.from(_ => scala.util.Success(new ReadOnlySecret))
          KindlingsBsonDocumentHandler.derived[ReadOnlyEnvelope]
          """
        ).check("Cannot derive field", "ReadOnlySecret")
      }

      test("writer-only field reports the missing reader") {
        compileErrors(
          """
          import hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler
          import reactivemongo.api.bson.{BSONString, BSONWriter}

          final class WriteOnlySecret
          final case class WriteOnlyEnvelope(secret: WriteOnlySecret)
          implicit val secretWriter: BSONWriter[WriteOnlySecret] = BSONWriter.from(_ => scala.util.Success(BSONString("secret")))
          KindlingsBsonDocumentHandler.derived[WriteOnlyEnvelope]
          """
        ).check("Cannot derive field", "WriteOnlySecret")
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
