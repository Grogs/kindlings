package hearth.kindlings.reactivemongobsonpolicytest

import hearth.MacroSuite
import reactivemongo.api.bson.BSONDocument

final case class Allowed(value: Int)
final case class Imported(value: String)
final case class Denied(value: Long)
final case class AllowedReader(value: Int)
final case class AllowedWriter(value: Int)

package allowed {
  object Instances {
    val handler: hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler[Allowed] =
      hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler.derived[Allowed]
    val reader: hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentReader[AllowedReader] =
      hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentReader.derived[AllowedReader]
    val writer: hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentWriter[AllowedWriter] =
      hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentWriter.derived[AllowedWriter]
  }
}

package viaimport {
  object Instances {
    import hearth.kindlings.reactivemongobsonderivation.policy.allowDerivationForReactiveMongoBson
    val handler: hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler[Imported] =
      hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler.derived[Imported]
  }
}

final class DerivationPolicySpec extends MacroSuite {
  group("ReactiveMongo BSON derivation policy") {
    test("permits an allowed scope") {
      assertEquals(allowed.Instances.handler.writeTry(Allowed(1)).get, BSONDocument("value" -> 1))
      assertEquals(allowed.Instances.reader.readDocument(BSONDocument("value" -> 2)).get, AllowedReader(2))
      assertEquals(allowed.Instances.writer.writeTry(AllowedWriter(3)).get, BSONDocument("value" -> 3))
    }

    test("permits the opt-in import") {
      assertEquals(viaimport.Instances.handler.writeTry(Imported("x")).get, BSONDocument("value" -> "x"))
    }

    test("denies an unapproved scope") {
      compileErrors(
        """hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler.derived[hearth.kindlings.reactivemongobsonpolicytest.Denied]"""
      ).check("is enabled only in the following scopes")
      compileErrors(
        """hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentReader.derived[hearth.kindlings.reactivemongobsonpolicytest.Denied]"""
      ).check("is enabled only in the following scopes")
      compileErrors(
        """hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentWriter.derived[hearth.kindlings.reactivemongobsonpolicytest.Denied]"""
      ).check("is enabled only in the following scopes")
    }
  }
}
