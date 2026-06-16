package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.MacroCommonsScala3
import scala.quoted.*

final private[reactivemongobsonderivation] class BsonDocumentHandlerMacros(q: Quotes)
    extends MacroCommonsScala3(using q),
      AnnotationSupportScala3,
      BsonDocumentHandlerMacrosImpl {

  import q.reflect.*

  override protected def fullNameOf[A: Type]: String = {
    val raw = TypeRepr.of[A].typeSymbol.fullName
    // TypeRepr includes a trailing `$` on module (object/class-of-object) symbols.
    // Normalize to match the reference: split on package/object separators and join with `.`.
    raw.split(Array('.', '$')).filter(_.nonEmpty).mkString(".")
  }
}

private[reactivemongobsonderivation] object BsonDocumentHandlerMacros {

  def deriveTypeClassImpl[A: Type](using
      q: Quotes
  )(
      configExpr: Expr[hearth.kindlings.reactivemongobsonderivation.BsonDocumentHandlerConfig]
  ): Expr[hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentHandler[A]] =
    new BsonDocumentHandlerMacros(q).deriveTypeClass[A](configExpr)
}
