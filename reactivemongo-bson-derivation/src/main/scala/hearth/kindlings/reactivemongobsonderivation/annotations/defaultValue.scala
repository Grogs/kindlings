package hearth.kindlings.reactivemongobsonderivation.annotations

import scala.annotation.StaticAnnotation

/** Provides a default value for a field that has no BSON value when reading. */
final class defaultValue[+T](val value: T) extends StaticAnnotation
