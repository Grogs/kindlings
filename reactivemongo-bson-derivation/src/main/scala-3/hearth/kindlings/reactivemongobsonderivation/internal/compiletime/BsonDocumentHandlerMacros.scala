package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.MacroCommonsScala3
import scala.quoted.*

final private[reactivemongobsonderivation] class BsonDocumentHandlerMacros(q: Quotes)
    extends MacroCommonsScala3(using q),
      BsonDocumentHandlerMacrosImpl

private[reactivemongobsonderivation] object BsonDocumentHandlerMacros {

  def deriveTypeClassImpl[A: Type](using q: Quotes): Expr[hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler[A]] =
    new BsonDocumentHandlerMacros(q).deriveTypeClass[A]
}
