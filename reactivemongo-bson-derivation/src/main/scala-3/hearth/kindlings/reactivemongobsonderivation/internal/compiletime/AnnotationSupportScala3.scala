package hearth.kindlings.reactivemongobsonderivation
package internal.compiletime

import hearth.MacroCommonsScala3

trait AnnotationSupportScala3 extends AnnotationSupport { this: MacroCommonsScala3 =>
  import quotes.reflect.*

  override protected def findAnnotationOfType[Ann: Type](param: Parameter): Option[UntypedExpr] = {
    val annTpe = UntypedType.fromTyped[Ann]
    param.asUntyped.annotations.find { term =>
      term.tpe <:< annTpe
    }
  }

  override protected def hasAnnotationTypeConstructor0(param: Parameter, fullName: String): Boolean =
    param.asUntyped.annotations.exists(_.tpe.typeSymbol.fullName == fullName)

  override protected def extractStringLiteralFromAnnotation(annotation: UntypedExpr): Option[String] =
    annotation match {
      case Apply(_, List(Literal(StringConstant(value)))) => Some(value)
      case _                                              => None
    }

  override protected def extractSingleArgFromAnnotation(annotation: UntypedExpr): Option[UntypedExpr] =
    annotation match {
      case Apply(_, List(arg)) => Some(arg)
      case _                   => None
    }
}
