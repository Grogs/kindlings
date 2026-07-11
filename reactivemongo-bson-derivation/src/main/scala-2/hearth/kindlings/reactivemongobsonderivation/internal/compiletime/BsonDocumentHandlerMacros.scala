package hearth.kindlings.reactivemongobsonderivation
package internal.compiletime

import hearth.MacroCommonsScala2
import scala.reflect.macros.blackbox

final private[reactivemongobsonderivation] class BsonDocumentHandlerMacros(val c: blackbox.Context)
    extends MacroCommonsScala2
    with AnnotationSupportScala2
    with BsonDocumentHandlerMacrosImpl {

  override protected def fullNameOf[A: c.WeakTypeTag]: String = {
    val sym = c.weakTypeOf[A].typeSymbol
    val fullName = sym.fullName
    // Hearth/the reference strip trailing `$` on module classes and join outer objects with `.`.
    fullName.split(Array('.', '$')).filter(_.nonEmpty).mkString(".")
  }

  def deriveInlineImpl[A: c.WeakTypeTag](
      value: c.Expr[A]
  )(
      config: c.Expr[BsonDocumentHandlerConfig]
  ): c.Expr[scala.util.Try[reactivemongo.api.bson.BSONDocument]] =
    deriveInline[A](value, config).asInstanceOf[c.Expr[scala.util.Try[reactivemongo.api.bson.BSONDocument]]]

  def deriveReaderTypeClassImpl[A: c.WeakTypeTag](
      config: c.Expr[BsonDocumentHandlerConfig]
  ): c.Expr[KindlingsBsonDocumentReader[A]] =
    deriveReaderTypeClass[A](config).asInstanceOf[c.Expr[KindlingsBsonDocumentReader[A]]]

  def deriveWriterTypeClassImpl[A: c.WeakTypeTag](
      config: c.Expr[BsonDocumentHandlerConfig]
  ): c.Expr[KindlingsBsonDocumentWriter[A]] =
    deriveWriterTypeClass[A](config).asInstanceOf[c.Expr[KindlingsBsonDocumentWriter[A]]]

  def deriveTypeClassImpl[A: c.WeakTypeTag](
      config: c.Expr[BsonDocumentHandlerConfig]
  ): c.Expr[KindlingsBsonDocumentHandler[A]] =
    deriveTypeClass[A](config).asInstanceOf[c.Expr[KindlingsBsonDocumentHandler[A]]]
}
