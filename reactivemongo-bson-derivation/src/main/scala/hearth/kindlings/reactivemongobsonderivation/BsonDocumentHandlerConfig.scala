package hearth.kindlings.reactivemongobsonderivation

/** Configuration for BSONDocumentHandler derivation.
  *
  * @param fieldNameMapper
  *   Function to transform field names (default: identity)
  * @param discriminatorFieldName
  *   The field name used for sealed trait/enum discrimination (None = wrapper-style, Some(name) = discriminator-style,
  *   default: Some("className"))
  * @param skipUnexpectedFields
  *   If true, skip unknown fields during decoding (default: true)
  */
final case class BsonDocumentHandlerConfig(
    fieldNameMapper: String => String = identity,
    discriminatorFieldName: Option[String] = BsonDocumentHandlerConfig.defaultDiscriminatorFieldName,
    skipUnexpectedFields: Boolean = true
) {

  def withFieldNameMapper(f: String => String): BsonDocumentHandlerConfig =
    copy(fieldNameMapper = f)

  def withSnakeCaseFieldNames: BsonDocumentHandlerConfig =
    copy(fieldNameMapper = BsonDocumentHandlerConfig.snakeCase)

  def withKebabCaseFieldNames: BsonDocumentHandlerConfig =
    copy(fieldNameMapper = BsonDocumentHandlerConfig.kebabCase)

  def withPascalCaseFieldNames: BsonDocumentHandlerConfig =
    copy(fieldNameMapper = BsonDocumentHandlerConfig.pascalCase)

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

  private[reactivemongobsonderivation] val snakeCase: String => String = { s =>
    val sb = new StringBuilder
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      if (c.isUpper) {
        if (i > 0) sb.append('_')
        sb.append(c.toLower)
      } else sb.append(c)
      i += 1
    }
    sb.toString
  }

  private[reactivemongobsonderivation] val kebabCase: String => String = { s =>
    val sb = new StringBuilder
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      if (c.isUpper) {
        if (i > 0) sb.append('-')
        sb.append(c.toLower)
      } else sb.append(c)
      i += 1
    }
    sb.toString
  }

  private[reactivemongobsonderivation] val pascalCase: String => String = { s =>
    if (s.isEmpty) s
    else s"${s.head.toUpper}${s.tail}"
  }
}
