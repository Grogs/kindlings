package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.MacroCommonsScala3
import scala.quoted.*

final private[reactivemongobsonderivation] class BsonDocumentHandlerMacros(q: Quotes)
    extends MacroCommonsScala3(using q),
      AnnotationSupportScala3,
      BsonDocumentHandlerMacrosImpl

private[reactivemongobsonderivation] object BsonDocumentHandlerMacros {

  def deriveTypeClassImpl[A: Type](using
      q: Quotes
  )(
      configExpr: Expr[hearth.kindlings.reactivemongobsonderivation.BsonDocumentHandlerConfig]
  ): Expr[hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler[A]] =
    new BsonDocumentHandlerMacros(q).deriveTypeClass[A](configExpr)
}
