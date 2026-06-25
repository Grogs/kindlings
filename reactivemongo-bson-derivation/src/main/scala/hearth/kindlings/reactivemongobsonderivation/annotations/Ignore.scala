package hearth.kindlings.reactivemongobsonderivation.annotations

import scala.annotation.StaticAnnotation

/** Indicates that the annotated field must not be serialized to BSON. The field is skipped during both reading and
  * writing. If the field must be readable, a default value must be defined, either from the field Scala-level default,
  * or via @DefaultValue annotation.
  */
final class Ignore extends StaticAnnotation
