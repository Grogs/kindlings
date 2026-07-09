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
  final case class CannotConstructType(typeName: String, reason: Option[String])
      extends BsonDocumentHandlerDerivationError {
    val message: String = reason match {
      case Some(r) => s"Cannot construct $typeName: $r"
      case None    => s"Cannot construct $typeName"
    }
  }
  final case class CannotDeriveField(fieldType: String, reason: String) extends BsonDocumentHandlerDerivationError {
    val message: String = s"Cannot derive field reader/writer for $fieldType: $reason"
  }
  final case class CannotIgnoreFieldWithoutDefault(fieldName: String, fieldType: String)
      extends BsonDocumentHandlerDerivationError {
    val message: String =
      s"Cannot ignore field $fieldName: $fieldType needs a Scala default value or @DefaultValue"
  }
  final case class CannotFlattenRecursiveField(fieldName: String, ownerType: String)
      extends BsonDocumentHandlerDerivationError {
    val message: String = s"Cannot flatten recursive field $ownerType.$fieldName"
  }
  final case class CannotFlattenNonDocumentField(fieldName: String, fieldType: String)
      extends BsonDocumentHandlerDerivationError {
    val message: String = s"Cannot flatten field $fieldName: $fieldType is not a case class or sealed trait"
  }
  final case class CannotDeriveCollection(fieldType: String, reason: String)
      extends BsonDocumentHandlerDerivationError {
    val message: String = s"Cannot derive collection handler for $fieldType: $reason"
  }
  final case class CannotDeriveValueType(innerType: String, reason: String) extends BsonDocumentHandlerDerivationError {
    val message: String = s"Cannot derive value type handler for $innerType: $reason"
  }
  final case class CannotDeriveEnum(typeName: String, reason: String) extends BsonDocumentHandlerDerivationError {
    val message: String = s"Cannot derive enum handler for $typeName: $reason"
  }
  final case class NoChildrenInSealedTrait(typeName: String) extends BsonDocumentHandlerDerivationError {
    val message: String = s"Sealed trait $typeName has no subtypes"
  }
  final case class UnexpectedParameterInSingleton(typeName: String, reason: String)
      extends BsonDocumentHandlerDerivationError {
    val message: String = s"$reason: $typeName"
  }
}
