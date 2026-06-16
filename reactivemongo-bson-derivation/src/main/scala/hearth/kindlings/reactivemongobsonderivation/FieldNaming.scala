package hearth.kindlings.reactivemongobsonderivation

/** Structured field naming strategy, mirroring ReactiveMongo-BSON's `FieldNaming`.
  *
  * Use the predefined variants (`Identity`, `SnakeCase`, `PascalCase`, `KebabCase`) or provide a custom function via
  * `FieldNaming.Custom(f)`.
  *
  * This is provided for users migrating from ReactiveMongo-BSON. The underlying config field remains a
  * `String => String` function, so existing code using `BsonDocumentHandlerConfig.withFieldNameMapper(f)` continues to
  * work.
  */
sealed trait FieldNaming extends (String => String) {
  def apply(propertyName: String): String
}

object FieldNaming {

  /** Leaves field names unchanged. */
  case object Identity extends FieldNaming {
    def apply(propertyName: String): String = propertyName
  }

  /** Converts `fooBar` to `foo_bar`. */
  case object SnakeCase extends FieldNaming {
    def apply(propertyName: String): String = {
      val sb = new StringBuilder
      var i = 0
      while (i < propertyName.length) {
        val c = propertyName.charAt(i)
        if (c.isUpper) {
          if (i > 0) sb.append('_')
          sb.append(c.toLower)
        } else sb.append(c)
        i += 1
      }
      sb.toString
    }
  }

  /** Converts `fooBar` to `FooBar`. */
  case object PascalCase extends FieldNaming {
    def apply(propertyName: String): String =
      if (propertyName.isEmpty) propertyName
      else s"${propertyName.head.toUpper}${propertyName.tail}"
  }

  /** Converts `fooBar` to `foo-bar`. */
  case object KebabCase extends FieldNaming {
    def apply(propertyName: String): String = {
      val sb = new StringBuilder
      var i = 0
      while (i < propertyName.length) {
        val c = propertyName.charAt(i)
        if (c.isUpper) {
          if (i > 0) sb.append('-')
          sb.append(c.toLower)
        } else sb.append(c)
        i += 1
      }
      sb.toString
    }
  }

  /** Custom strategy backed by an arbitrary function. */
  final case class Custom(f: String => String) extends FieldNaming {
    def apply(propertyName: String): String = f(propertyName)
  }
}
