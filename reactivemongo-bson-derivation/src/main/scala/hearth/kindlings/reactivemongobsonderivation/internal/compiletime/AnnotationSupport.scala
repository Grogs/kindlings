package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.MacroCommons
import hearth.std.*

trait AnnotationSupport { this: MacroCommons & StdExtensions =>

  protected def findAnnotationOfType[Ann: Type](param: Parameter): Option[UntypedExpr]

  /** Whether a parameter has an annotation with this type constructor, regardless of its type arguments. */
  protected def hasAnnotationTypeConstructor0(param: Parameter, fullName: String): Boolean

  protected def extractStringLiteralFromAnnotation(annotation: UntypedExpr): Option[String]

  /** Extracts the single positional argument of a single-argument annotation as an UntypedExpr. Useful for annotations
    * like `@DefaultValue(value)` that carry a typed value.
    */
  protected def extractSingleArgFromAnnotation(annotation: UntypedExpr): Option[UntypedExpr]

  final def hasAnnotationType[Ann: Type](param: Parameter): Boolean =
    findAnnotationOfType[Ann](param).isDefined

  final def hasAnnotationTypeConstructor(param: Parameter, fullName: String): Boolean =
    hasAnnotationTypeConstructor0(param, fullName)

  final def getAnnotationStringArg[Ann: Type](param: Parameter): Option[String] =
    findAnnotationOfType[Ann](param).flatMap(extractStringLiteralFromAnnotation)

  final def getAnnotationValueUntyped[Ann: Type](param: Parameter): Option[UntypedExpr] =
    findAnnotationOfType[Ann](param).flatMap(extractSingleArgFromAnnotation)
}
