package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

import hearth.*
import hearth.fp.effect.MLocal

import hearth.kindlings.reactivemongobsonderivation.BsonDocumentHandlerConfig

/** Direction-specific state and immutable structural planning data.
  *
  * Reader and writer derivation deliberately receive distinct instances of these contexts: sharing the immutable
  * configuration is safe, sharing their [[ValDefsCache]] is not.
  */
private[compiletime] trait BsonDirectionalInfrastructure { this: MacroCommons =>

  final case class ReaderCtx[A](
      tpe: Type[A],
      cache: MLocal[ValDefsCache],
      config: Expr[BsonDocumentHandlerConfig],
      evaluatedConfig: Option[BsonDocumentHandlerConfig],
      flattenStack: List[String]
  ) {
    def nest[B: Type]: ReaderCtx[B] = ReaderCtx(Type[B], cache, config, evaluatedConfig, flattenStack)
    def nestFlattened[B: Type]: ReaderCtx[B] =
      ReaderCtx(Type[B], cache, config, evaluatedConfig, tpe.prettyPrint :: flattenStack)
  }

  object ReaderCtx {
    def from[A: Type](
        config: Expr[BsonDocumentHandlerConfig],
        evaluatedConfig: Option[BsonDocumentHandlerConfig]
    ): ReaderCtx[A] = ReaderCtx(Type[A], ValDefsCache.mlocal, config, evaluatedConfig, Nil)
  }

  final case class WriterCtx[A](
      tpe: Type[A],
      cache: MLocal[ValDefsCache],
      config: Expr[BsonDocumentHandlerConfig],
      evaluatedConfig: Option[BsonDocumentHandlerConfig],
      flattenStack: List[String]
  ) {
    def nest[B: Type]: WriterCtx[B] = WriterCtx(Type[B], cache, config, evaluatedConfig, flattenStack)
    def nestFlattened[B: Type]: WriterCtx[B] =
      WriterCtx(Type[B], cache, config, evaluatedConfig, tpe.prettyPrint :: flattenStack)
  }

  object WriterCtx {
    def from[A: Type](
        config: Expr[BsonDocumentHandlerConfig],
        evaluatedConfig: Option[BsonDocumentHandlerConfig]
    ): WriterCtx[A] = WriterCtx(Type[A], ValDefsCache.mlocal, config, evaluatedConfig, Nil)
  }

  /** Immutable record facts shared by both directional body builders. */
  final case class DirectionalRecordPlan(
      constructor: Method,
      fields: List[(String, Parameter)]
  )

  /** Immutable enum facts shared by both directional body builders. */
  final case class DirectionalEnumMetadata(
      discriminatorField: Expr[String],
      knownNames: Expr[String]
  )
}
