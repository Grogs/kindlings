package hearth.kindlings.reactivemongobsonpolicytest

import hearth.MacroSuite
import reactivemongo.api.bson.BSONDocument

final case class Allowed(value: Int)
final case class Imported(value: String)
final case class Denied(value: Long)

package allowed {
  object Instances {
    val handler: hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler[Allowed] =
      hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler.derived[Allowed]
    val reader: hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentReader[Allowed] =
      hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentReader.derived[Allowed]
    val writer: hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentWriter[Allowed] =
      hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentWriter.derived[Allowed]
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
    test("permits all entry points in an allowed scope") {
      val document = BSONDocument("value" -> 1)
      assertEquals(allowed.Instances.handler.writeTry(Allowed(1)).get, document)
      assertEquals(allowed.Instances.reader.readDocument(document).get, Allowed(1))
      assertEquals(allowed.Instances.writer.writeTry(Allowed(1)).get, document)
    }

    test("permits the opt-in import") {
      assertEquals(viaimport.Instances.handler.writeTry(Imported("x")).get, BSONDocument("value" -> "x"))
    }

    test("denies every entry point in an unapproved scope") {
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
