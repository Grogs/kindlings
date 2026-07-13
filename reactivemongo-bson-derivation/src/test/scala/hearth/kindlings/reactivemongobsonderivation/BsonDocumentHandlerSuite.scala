package hearth.kindlings.reactivemongobsonderivation

import hearth.MacroSuite
import reactivemongo.api.bson.BSONDocument

private[reactivemongobsonderivation] abstract class BsonDocumentHandlerSuite extends MacroSuite {

  protected final def assertRoundTrip[A](
      handler: KindlingsBsonDocumentHandler[A],
      value: A,
      document: BSONDocument
  ): Unit = {
    assertEquals(handler.writeTry(value).get, document)
    assertEquals(handler.readDocument(document).get, value)
  }

  protected final def assertSelfRoundTrips[A](handler: KindlingsBsonDocumentHandler[A], values: A*): Unit =
    values.foreach(value => assertEquals(handler.readDocument(handler.writeTry(value).get).get, value))
}
