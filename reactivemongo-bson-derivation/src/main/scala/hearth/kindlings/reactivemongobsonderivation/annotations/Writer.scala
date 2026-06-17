package hearth.kindlings.reactivemongobsonderivation.annotations

import reactivemongo.api.bson.BSONWriter

import scala.annotation.StaticAnnotation

/** Provides a custom BSONWriter for a specific field, overriding the derived one. */
final class Writer[T](val writer: BSONWriter[T]) extends StaticAnnotation
