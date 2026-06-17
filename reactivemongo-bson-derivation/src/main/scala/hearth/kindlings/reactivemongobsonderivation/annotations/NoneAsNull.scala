package hearth.kindlings.reactivemongobsonderivation.annotations

import scala.annotation.StaticAnnotation

/** When applied to an `Option` field, `None` is written as `BSONNull` instead of being omitted. */
final class NoneAsNull extends StaticAnnotation
