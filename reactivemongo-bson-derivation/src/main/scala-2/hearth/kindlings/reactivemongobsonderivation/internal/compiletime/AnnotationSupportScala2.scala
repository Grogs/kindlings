package hearth.kindlings.reactivemongobsonderivation
package internal.compiletime

import hearth.MacroCommonsScala2

trait AnnotationSupportScala2 extends AnnotationSupport { this: MacroCommonsScala2 =>
  import c.universe.*

  override protected def findAnnotationOfType[Ann: Type](param: Parameter): Option[UntypedExpr] = {
    val annTpe = UntypedType.fromTyped[Ann]
    param.asUntyped.symbol.annotations.collectFirst {
      case ann if ann.tree.tpe <:< annTpe => c.untypecheck(ann.tree)
    }
  }

  override protected def hasAnnotationTypeConstructor0(param: Parameter, fullName: String): Boolean =
    param.asUntyped.symbol.annotations.exists(_.tree.tpe.typeSymbol.fullName == fullName)

  override protected def annotationTypeConstructorCount0(param: Parameter, fullName: String): Int =
    param.asUntyped.symbol.annotations.count(_.tree.tpe.typeSymbol.fullName == fullName)

  override protected def extractStringLiteralFromAnnotation(annotation: UntypedExpr): Option[String] =
    annotation match {
      case Apply(_, List(Literal(Constant(value: String)))) => Some(value)
      case _                                                => None
    }

  override protected def extractSingleArgFromAnnotation(annotation: UntypedExpr): Option[UntypedExpr] =
    annotation match {
      case Apply(_, List(arg)) => Some(arg)
      case _                   => None
    }
}
