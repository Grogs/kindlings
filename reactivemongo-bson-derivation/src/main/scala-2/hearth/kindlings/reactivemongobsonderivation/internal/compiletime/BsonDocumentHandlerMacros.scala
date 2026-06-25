package hearth.kindlings.reactivemongobsonderivation
package internal.compiletime

import hearth.MacroCommonsScala2
import scala.reflect.macros.blackbox

final private[reactivemongobsonderivation] class BsonDocumentHandlerMacros(val c: blackbox.Context)
    extends MacroCommonsScala2
    with AnnotationSupportScala2
    with BsonDocumentHandlerMacrosImpl {

  override protected def fullNameOf[A: c.WeakTypeTag]: String = {
    val sym       = c.weakTypeOf[A].typeSymbol
    val fullName  = sym.fullName
    // Hearth/the reference strip trailing `$` on module classes and join outer objects with `.`.
    fullName.split(Array('.', '$')).filter(_.nonEmpty).mkString(".")
  }

  def deriveTypeClassImpl[A: c.WeakTypeTag](
      config: c.Expr[BsonDocumentHandlerConfig]
  ): c.Expr[KindlingsBsonDocumentHandler[A]] =
    deriveTypeClass[A](config).asInstanceOf[c.Expr[KindlingsBsonDocumentHandler[A]]]
}