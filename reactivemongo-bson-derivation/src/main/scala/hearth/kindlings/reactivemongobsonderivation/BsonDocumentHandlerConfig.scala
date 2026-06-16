package hearth.kindlings.reactivemongobsonderivation

/** Configuration for BSONDocumentHandler derivation.
  *
  * @param fieldNaming
  *   Strategy to transform field names (default: identity). Stored as a `FieldNaming` sealed trait so the config can be
  *   evaluated at compile time in the common case.
  * @param typeNaming
  *   Strategy to map sealed-trait/enum case types to discriminator values (default: `TypeNaming.SimpleName`)
  * @param discriminatorFieldName
  *   The field name used for sealed trait/enum discrimination (None = wrapper-style, Some(name) = discriminator-style,
  *   default: Some("className"))
  * @param skipUnexpectedFields
  *   If true, skip unknown fields during decoding (default: true)
  */
final case class BsonDocumentHandlerConfig(
    fieldNaming: FieldNaming = FieldNaming.Identity,
    typeNaming: TypeNaming = TypeNaming.SimpleName,
    discriminatorFieldName: Option[String] = BsonDocumentHandlerConfig.defaultDiscriminatorFieldName,
    skipUnexpectedFields: Boolean = true
) {

  /** Backward-compatible accessor: the field naming strategy as a `String => String` function. */
  def fieldNameMapper: String => String = fieldNaming

  def withFieldNameMapper(f: String => String): BsonDocumentHandlerConfig =
    copy(fieldNaming = FieldNaming.Custom(f))

  def withFieldNaming(naming: FieldNaming): BsonDocumentHandlerConfig =
    copy(fieldNaming = naming)

  def withTypeNaming(naming: TypeNaming): BsonDocumentHandlerConfig =
    copy(typeNaming = naming)

  def withSnakeCaseFieldNames: BsonDocumentHandlerConfig =
    copy(fieldNaming = FieldNaming.SnakeCase)

  def withKebabCaseFieldNames: BsonDocumentHandlerConfig =
    copy(fieldNaming = FieldNaming.KebabCase)

  def withPascalCaseFieldNames: BsonDocumentHandlerConfig =
    copy(fieldNaming = FieldNaming.PascalCase)

  def withDiscriminatorFieldName(name: String): BsonDocumentHandlerConfig =
    copy(discriminatorFieldName = Some(name))

  def withoutDiscriminator: BsonDocumentHandlerConfig =
    copy(discriminatorFieldName = None)

  def withSkipUnexpectedFields(skip: Boolean): BsonDocumentHandlerConfig =
    copy(skipUnexpectedFields = skip)
}

object BsonDocumentHandlerConfig {

  /** Default discriminator field name, aligned with ReactiveMongo-BSON's `MacroConfiguration.defaultDiscriminator` */
  val defaultDiscriminatorFieldName: Option[String] = Some("className")

  implicit val default: BsonDocumentHandlerConfig = BsonDocumentHandlerConfig()
}
