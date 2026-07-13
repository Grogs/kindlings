package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.MacroCommons
import hearth.std.*
import reactivemongo.api.bson.BSONDocument

import scala.util.Try

/** Annotation codec extraction and the document-writer adapter shared by directional derivation. */
trait BsonDirectionalCodecSupport {
  this: BsonDocumentHandlerMacrosImpl & MacroCommons & StdExtensions & AnnotationSupport =>

  /** Adapt a cached document-write def to ReactiveMongo's BSONWriter for nested structural fields. */
  protected def writerFromDocumentWrite[A: Type](
      writeCall: Expr[A] => Expr[Try[BSONDocument]]
  ): Expr[reactivemongo.api.bson.BSONWriter[A]] =
    Expr.quote {
      new reactivemongo.api.bson.BSONWriter[A] {
        def writeTry(value: A): Try[reactivemongo.api.bson.BSONValue] =
          Expr.splice(writeCall(Expr.quote(value))).map(document => document: reactivemongo.api.bson.BSONValue)
      }
    }

  /** Try to extract a @reader-annotated BSONReader for a field. Returns None if no annotation. */
  protected def annotatedReader[A: Type](
      param: Parameter
  ): Option[Expr[reactivemongo.api.bson.BSONReader[A]]] = {
    val annotationName = "hearth.kindlings.reactivemongobsonderivation.annotations.Reader"
    if (annotationTypeConstructorCount(param, annotationName) > 1)
      Environment.reportErrorAndAbort(s"At most one @Reader annotation is allowed for field ${param.name}")
    val annotationType = Type.of[hearth.kindlings.reactivemongobsonderivation.annotations.Reader[A]]
    getAnnotationValueUntyped(param)(annotationType) match {
      case Some(untyped)                                               => Some(annotateReaderValue[A](untyped))
      case None if hasAnnotationTypeConstructor(param, annotationName) =>
        Environment.reportErrorAndAbort(
          s"Invalid @Reader annotation for field ${param.name}: BSONReader[${Type[A].prettyPrint}] expected"
        )
      case None => None
    }
  }

  /** Keep `A` as a method type parameter so Scala 2 does not reify a macro-only parameter path. */
  private def annotateReaderValue[A: Type](
      untyped: UntypedExpr
  ): Expr[reactivemongo.api.bson.BSONReader[A]] = {
    implicit val ReaderT: Type[reactivemongo.api.bson.BSONReader[A]] = Types.BsonReader[A]
    Expr.quote {
      Expr
        .splice(untyped.asTyped[reactivemongo.api.bson.BSONReader[A]])
        .asInstanceOf[reactivemongo.api.bson.BSONReader[A]]
    }
  }

  /** Try to extract a @writer-annotated BSONWriter for a field. Returns None if no annotation. */
  protected def annotatedWriter[A: Type](
      param: Parameter
  ): Option[Expr[reactivemongo.api.bson.BSONWriter[A]]] = {
    val annotationName = "hearth.kindlings.reactivemongobsonderivation.annotations.Writer"
    if (annotationTypeConstructorCount(param, annotationName) > 1)
      Environment.reportErrorAndAbort(s"At most one @Writer annotation is allowed for field ${param.name}")
    val annotationType = Type.of[hearth.kindlings.reactivemongobsonderivation.annotations.Writer[A]]
    getAnnotationValueUntyped(param)(annotationType) match {
      case Some(untyped)                                               => Some(annotateWriterValue[A](untyped))
      case None if hasAnnotationTypeConstructor(param, annotationName) =>
        Environment.reportErrorAndAbort(
          s"Invalid @Writer annotation for field ${param.name}: BSONWriter[${Type[A].prettyPrint}] expected"
        )
      case None => None
    }
  }

  private def annotateWriterValue[A: Type](
      untyped: UntypedExpr
  ): Expr[reactivemongo.api.bson.BSONWriter[A]] = {
    implicit val WriterT: Type[reactivemongo.api.bson.BSONWriter[A]] = Types.BsonWriter[A]
    Expr.quote {
      Expr
        .splice(untyped.asTyped[reactivemongo.api.bson.BSONWriter[A]])
        .asInstanceOf[reactivemongo.api.bson.BSONWriter[A]]
    }
  }
}
