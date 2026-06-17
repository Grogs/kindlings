package hearth.kindlings.reactivemongobsonderivation.annotations

import scala.annotation.StaticAnnotation

/** Provides a default value for a field that has no BSON value when reading. */
final class DefaultValue[+T](val value: T) extends StaticAnnotation
