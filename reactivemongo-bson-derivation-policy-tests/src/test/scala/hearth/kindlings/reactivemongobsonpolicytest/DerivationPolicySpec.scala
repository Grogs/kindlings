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
    }

    test("permits the opt-in import") {
      assertEquals(viaimport.Instances.handler.writeTry(Imported("x")).get, BSONDocument("value" -> "x"))
    }

    test("denies an unapproved scope") {
      compileErrors(
        """hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler.derived[hearth.kindlings.reactivemongobsonpolicytest.Denied]"""
      ).check("is enabled only in the following scopes")
    }
  }
}
