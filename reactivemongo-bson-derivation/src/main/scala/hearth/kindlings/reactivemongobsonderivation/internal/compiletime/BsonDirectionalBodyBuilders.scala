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
        Enum.parse[A].toEither match {
          case Right(enumm) => deriveEnumWriterBody[A](enumm, writerCtx)
          case Left(_)      => deriveRecordWriterBody[A](writerCtx)
        }
    }
  }
}
