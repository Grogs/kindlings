package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.MacroCommons
import hearth.std.*

trait AnnotationSupport { this: MacroCommons & StdExtensions =>

  protected def findAnnotationOfType[Ann: Type](param: Parameter): Option[UntypedExpr]

  protected def extractStringLiteralFromAnnotation(annotation: UntypedExpr): Option[String]

  /** Extracts the single positional argument of a single-argument annotation as an UntypedExpr. Useful for annotations
    * like `@defaultValue(value)` that carry a typed value.
    */
  protected def extractSingleArgFromAnnotation(annotation: UntypedExpr): Option[UntypedExpr]

  final def hasAnnotationType[Ann: Type](param: Parameter): Boolean =
    findAnnotationOfType[Ann](param).isDefined

  final def getAnnotationStringArg[Ann: Type](param: Parameter): Option[String] =
    findAnnotationOfType[Ann](param).flatMap(extractStringLiteralFromAnnotation)

  final def getAnnotationValueUntyped[Ann: Type](param: Parameter): Option[UntypedExpr] =
    findAnnotationOfType[Ann](param).flatMap(extractSingleArgFromAnnotation)
}
