package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.*
import hearth.fp.effect.MIO
import hearth.std.*

import reactivemongo.api.bson.BSONDocument

import scala.util.Try

/** Selects the structural body builder for each independent BSON direction.
  *
  * Record and enum implementations remain overridable helpers on the macro bundle; this layer owns the common
  * cache-first dispatch invariant used by standalone and combined derivation.
  */
private[compiletime] trait BsonDirectionalBodyBuilders { this: BsonDocumentHandlerMacrosImpl & MacroCommons =>

  final protected def deriveReaderBody[A: Type](readerCtx: ReaderCtx[A]): MIO[Unit] = {
    implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
    implicit val TryAT: Type[Try[A]] = Types.TryCtor[A]
    readerCtx.cache.get1Ary[BSONDocument, Try[A]]("cached-reader-body").flatMap {
      case Some(_) => MIO.pure(())
      case None    =>
        if (isDirectionalMap[A]) deriveRootReaderBody[A](readerCtx, None)
        else if (isDirectionalOption[A] || isDirectionalValueType[A])
          deriveRootReaderBody[A](readerCtx, Some("value"))
        else if (isDirectionalCollection[A]) deriveRootReaderBody[A](readerCtx, Some("values"))
        else
          Enum.parse[A].toEither match {
            case Right(enumm) => deriveEnumReaderBody[A](enumm, readerCtx)
            case Left(_)      => deriveRecordReaderBody[A](readerCtx)
          }
    }
  }

  final protected def deriveWriterBody[A: Type](writerCtx: WriterCtx[A]): MIO[Unit] = {
    implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
    implicit val TryDocumentT: Type[Try[BSONDocument]] = Types.TryCtor[BSONDocument]
    writerCtx.cache.get1Ary[A, Try[BSONDocument]]("cached-writer-body").flatMap {
      case Some(_) => MIO.pure(())
      case None    =>
        if (isDirectionalMap[A]) deriveRootWriterBody[A](writerCtx, None)
        else if (isDirectionalOption[A] || isDirectionalValueType[A])
          deriveRootWriterBody[A](writerCtx, Some("value"))
        else if (isDirectionalCollection[A]) deriveRootWriterBody[A](writerCtx, Some("values"))
        else
          Enum.parse[A].toEither match {
            case Right(enumm) => deriveEnumWriterBody[A](enumm, writerCtx)
            case Left(_)      => deriveRecordWriterBody[A](writerCtx)
          }
    }
  }

  /** Adapts a structurally-derived BSON value reader to the public document-level wire format. Maps occupy the root
    * document directly; other wrapper roots retain their established `value`/`values` envelope.
    */
  private def deriveRootReaderBody[A: Type](readerCtx: ReaderCtx[A], envelope: Option[String]): MIO[Unit] = {
    implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
    implicit val TryAT: Type[Try[A]] = Types.TryCtor[A]
    val key = "cached-reader-body"
    val builder = ValDefBuilder.ofDef1[BSONDocument, Try[A]](s"read_${Type[A].shortName}", "document")
    for {
      _ <- readerCtx.cache.forwardDeclare(key, builder)
      reader <- deriveDirectionalReaderStructurally[A](readerCtx)
      _ <- MIO.scoped { runSafe =>
        runSafe(readerCtx.cache.buildCachedWith(key, builder) { case (_, document) =>
          envelope match {
            case Some(fieldName) =>
              val fieldNameExpr = Expr(fieldName)
              Expr.quote {
                Expr
                  .splice(reader)
                  .readTry(
                    Expr
                      .splice(document)
                      .get(Expr.splice(fieldNameExpr))
                      .getOrElse(reactivemongo.api.bson.BSONNull)
                  )
              }
            case None => Expr.quote(Expr.splice(reader).readTry(Expr.splice(document)))
          }
        })
      }
    } yield ()
  }

  /** Writer counterpart of [[deriveRootReaderBody]]. */
  private def deriveRootWriterBody[A: Type](writerCtx: WriterCtx[A], envelope: Option[String]): MIO[Unit] = {
    implicit val DocumentT: Type[BSONDocument] = Types.BsonDocument
    implicit val TryDocumentT: Type[Try[BSONDocument]] = Types.TryCtor[BSONDocument]
    val key = "cached-writer-body"
    val builder = ValDefBuilder.ofDef1[A, Try[BSONDocument]](s"write_${Type[A].shortName}", "value")
    for {
      _ <- writerCtx.cache.forwardDeclare(key, builder)
      writer <- deriveDirectionalWriterStructurally[A](writerCtx)
      _ <- MIO.scoped { runSafe =>
        runSafe(writerCtx.cache.buildCachedWith(key, builder) { case (_, value) =>
          envelope match {
            case Some(fieldName) =>
              val fieldNameExpr = Expr(fieldName)
              Expr.quote {
                Expr.splice(writer).writeTry(Expr.splice(value)).map {
                  case reactivemongo.api.bson.BSONNull => BSONDocument.empty
                  case bsonValue                       => BSONDocument(Expr.splice(fieldNameExpr) -> bsonValue)
                }
              }
            case None =>
              Expr.quote {
                Expr.splice(writer).writeTry(Expr.splice(value)).flatMap {
                  case document: BSONDocument => scala.util.Success(document)
                  case bsonValue              =>
                    scala.util.Failure(
                      new IllegalArgumentException("Expected BSONDocument from root writer, got " + bsonValue)
                    )
                }
              }
          }
        })
      }
    } yield ()
  }
}
