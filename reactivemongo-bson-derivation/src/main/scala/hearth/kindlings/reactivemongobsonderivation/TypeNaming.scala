package hearth.kindlings.reactivemongobsonderivation

/** Strategy for mapping a sealed-trait/enum case type to a discriminator value.
  *
  * Mirrors ReactiveMongo-BSON's `TypeNaming` concept. The default is `FullName` (fully qualified name, e.g.
  * `"com.example.Tree.Leaf"`), matching ReactiveMongo-BSON's default. Use `SimpleName` for short class names only.
  */
sealed trait TypeNaming {

  /** Produces the discriminator value from the simple and full type names. */
  def apply(simpleName: String, fullName: String): String
}

object TypeNaming {

  /** Uses the short class name (e.g. `Leaf`). */
  case object SimpleName extends TypeNaming {
    def apply(simpleName: String, fullName: String): String = simpleName
  }

  /** Uses the full type name, with package and enclosing objects joined by `.` (e.g. `com.example.TreeModule.Leaf`).
    * This is the default, matching ReactiveMongo-BSON.
    */
  case object FullName extends TypeNaming {
    def apply(simpleName: String, fullName: String): String = fullName
  }

  /** Custom transformation applied to the simple name. */
  final case class Custom(f: String => String) extends TypeNaming {
    def apply(simpleName: String, fullName: String): String = f(simpleName)
  }
}
