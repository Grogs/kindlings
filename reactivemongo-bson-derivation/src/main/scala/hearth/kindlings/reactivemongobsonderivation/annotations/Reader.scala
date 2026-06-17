package hearth.kindlings.reactivemongobsonderivation.annotations

import reactivemongo.api.bson.BSONReader

import scala.annotation.StaticAnnotation

/** Provides a custom BSONReader for a specific field, overriding the derived one. */
final class Reader[T](val reader: BSONReader[T]) extends StaticAnnotation
