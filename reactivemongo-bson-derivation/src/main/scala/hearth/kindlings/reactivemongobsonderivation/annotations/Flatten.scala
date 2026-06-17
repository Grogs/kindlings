package hearth.kindlings.reactivemongobsonderivation.annotations

/** Marks a field of a case class type to be flattened into the parent document.
  *
  * Instead of nesting the inner case class as a sub-document, its fields are read/written directly as fields of the
  * parent document.
  *
  * Example: {{~ case class Inner(a: Int, b: String) case class Outer(@Flatten inner: Inner, c: Boolean)
  *
  * // Writes as: BSONDocument("a" -> 1, "b" -> "x", "c" -> true) // Instead of: BSONDocument("inner" ->
  * BSONDocument("a" -> 1, "b" -> "x"), "c" -> true) }}
  */
final class Flatten extends scala.annotation.StaticAnnotation
