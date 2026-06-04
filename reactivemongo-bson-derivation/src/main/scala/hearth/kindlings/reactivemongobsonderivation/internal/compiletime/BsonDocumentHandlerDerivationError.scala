package hearth.kindlings.reactivemongobsonderivation.internal.compiletime

sealed trait BsonDocumentHandlerDerivationError extends util.control.NoStackTrace {
  def message: String
  override def getMessage: String = message
}

object BsonDocumentHandlerDerivationError {
  final case class UnsupportedType(typeName: String, reasons: List[String]) extends BsonDocumentHandlerDerivationError {
    val message: String = {
      val reasonsStr = reasons.mkString("\n")
      s"Cannot derive BSONDocumentHandler for $typeName:\n$reasonsStr"
    }
  }
  final case class CannotConstructType(typeName: String, reason: Option[String]) extends BsonDocumentHandlerDerivationError {
    val message: String = reason match {
      case Some(r) => s"Cannot construct $typeName: $r"
      case None    => s"Cannot construct $typeName"
    }
  }
  final case class CannotDeriveField(fieldType: String, reason: String) extends BsonDocumentHandlerDerivationError {
    val message: String = s"Cannot derive field reader/writer for $fieldType: $reason"
  }
}
