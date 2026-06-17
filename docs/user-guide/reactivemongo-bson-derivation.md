# ReactiveMongo BSON Derivation

Derives `BSONDocumentHandler[A]` (read + write) for case classes, sealed traits, Scala 3 enums, value types, options, and collections — built on [Hearth](https://github.com/kubuszok/hearth/) for cross-compiled macro derivation.

## Installation

!!! example "sbt"

    ```scala
    libraryDependencies += "com.kubuszok" %% "kindlings-reactivemongo-bson-derivation" % "{{ kindlings_version() }}"
    ```

    JVM-only (reactivemongo-bson-api is not published for Scala.js / Scala Native):

    ```scala
    libraryDependencies += "com.kubuszok" %% "kindlings-reactivemongo-bson-derivation" % "{{ kindlings_version() }}"
    ```

## Quick start

```scala
import hearth.kindlings.reactivemongobsonderivation._

case class Person(name: String, age: Int)

val handler: KindlingsBsonDocumentHandler[Person] = KindlingsBsonDocumentHandler.derived[Person]

val written = handler.writeTry(Person("Alice", 30)).get
// BSONDocument("name" -> "Alice", "age" -> 30)

val read = handler.readDocument(written).get
// Person("Alice", 30)
```

The `derived` macro picks up the implicit `BsonDocumentHandlerConfig` from scope. With no config in scope, it uses the default (see [Configuration](#configuration)).

## Supported types

| Type | Notes |
|------|-------|
| Case classes | All primitive fields, nested case classes, generics |
| Sealed traits / Scala 3 enums | Discriminator field `"className"` by default |
| Options | `None` decodes from missing field or `BSONNull` |
| `AnyVal` value types | Treated as their underlying type |
| Collections | `List`, `Seq`, `Vector`, `Set`, `Array` |
| Maps | `Map[String, V]` (key type fixed to `String`) |
| Default field values | Applied when field is missing on read; `@defaultValue` for per-field override |
| `@fieldName` / `@noneAsNull` / `@reader` / `@writer` / `@flatten` | Per-field annotations supported |
| Field naming | `String => String` or structured `FieldNaming` |

## Configuration

Customize derivation with `BsonDocumentHandlerConfig`:

```scala
import hearth.kindlings.reactivemongobsonderivation._

val customConfig = BsonDocumentHandlerConfig()
  .withSnakeCaseFieldNames
  .withDiscriminatorFieldName("type")
  .withSkipUnexpectedFields(true)

given BsonDocumentHandlerConfig = customConfig
val handler = KindlingsBsonDocumentHandler.derived[Person]
```

### Field naming

Transform field names during read and write. Default: identity.

Use the helpers on `BsonDocumentHandlerConfig`:

```scala
case class CamelCaseFields(firstName: String, lastName: String, ageInYears: Int)

given BsonDocumentHandlerConfig = BsonDocumentHandlerConfig().withSnakeCaseFieldNames
val handler = KindlingsBsonDocumentHandler.derived[CamelCaseFields]

handler.writeTry(CamelCaseFields("Alice", "Smith", 30)).get
// BSONDocument("first_name" -> "Alice", "last_name" -> "Smith", "age_in_years" -> 30)
```

Available helpers:
- `withFieldNameMapper(f: String => String)` — arbitrary function
- `withFieldNaming(naming: FieldNaming)` — structured strategy (see below)
- `withSnakeCaseFieldNames`
- `withKebabCaseFieldNames`
- `withPascalCaseFieldNames`

#### `FieldNaming` structured API

For users migrating from ReactiveMongo-BSON, a structured `FieldNaming` trait is available:

```scala
import hearth.kindlings.reactivemongobsonderivation.FieldNaming

given BsonDocumentHandlerConfig = BsonDocumentHandlerConfig().withFieldNaming(FieldNaming.SnakeCase)
```

Variants: `Identity`, `SnakeCase`, `PascalCase`, `KebabCase`, and `Custom(f)`.

### `discriminatorFieldName: Option[String]`

The field name used for sealed-trait / enum discrimination. Default: `Some("className")` (aligned with ReactiveMongo-BSON).

Set to `None` to use wrapper-style encoding instead of discriminator-style:

```scala
given BsonDocumentHandlerConfig = BsonDocumentHandlerConfig().withoutDiscriminator
```

### `typeNaming: TypeNaming`

Controls how sealed-trait / enum case types are mapped to discriminator values. Default: `TypeNaming.SimpleName`.

```scala
import hearth.kindlings.reactivemongobsonderivation.TypeNaming

// Use the full type name (e.g. com.example.MyModule.Leaf)
given BsonDocumentHandlerConfig = BsonDocumentHandlerConfig().withTypeNaming(TypeNaming.FullName)

// Custom transformation of the simple name
given BsonDocumentHandlerConfig =
  BsonDocumentHandlerConfig().withTypeNaming(TypeNaming.Custom(_.toLowerCase))
```

### `skipUnexpectedFields: Boolean`

If `true` (default), unknown BSON fields are silently ignored on read. If `false`, an `IllegalArgumentException` is returned in the `Try` listing the unexpected field names.

## Annotations

### `@fieldName`

Override the BSON key for a specific field. Takes precedence over `fieldNameMapper`.

```scala
import hearth.kindlings.reactivemongobsonderivation.annotations.fieldName

case class User(
  @fieldName("user_id") id: String,
  @fieldName("created_at") createdAt: Long
)
```

### `@noneAsNull`

By default, `None` values are omitted from the written document. Annotate an `Option` field with `@noneAsNull` to write `None` as `BSONNull` instead.

```scala
import hearth.kindlings.reactivemongobsonderivation.annotations.noneAsNull

case class Record(
  name: String,
  @noneAsNull description: Option[String]
)

val handler = KindlingsBsonDocumentHandler.derived[Record]
handler.writeTry(Record("x", None)).get
// BSONDocument("name" -> "x", "description" -> BSONNull)
```

On read, `BSONNull` is always decoded as `None`, whether or not the annotation is present.

### `@defaultValue`

Provide a default value for a field that doesn't have a Scala-level default. Applied when the field is missing on read.

```scala
import hearth.kindlings.reactivemongobsonderivation.annotations.defaultValue

case class Config(
  name: String,
  @defaultValue(8080) port: Int
)

val handler = KindlingsBsonDocumentHandler.derived[Config]
handler.readDocument(BSONDocument("name" -> "app")).get
// Config("app", 8080)
```

### `@reader` and `@writer`

Override the BSON reader or writer for a specific field. Useful when a field needs a custom codec without defining an implicit for the whole type.

```scala
import reactivemongo.api.bson.{ BSONReader, BSONWriter }
import hearth.kindlings.reactivemongobsonderivation.annotations.{ reader, writer }

object codecs {
  implicit val upperReader: BSONReader[String] = BSONReader.collect { case reactivemongo.api.bson.BSONString(s) => s.toUpperCase }
  implicit val lowerWriter: BSONWriter[String] = BSONWriter[String](s => reactivemongo.api.bson.BSONString(s.toLowerCase))
}

case class Styled(
  @reader(codecs.upperReader) label: String,
  @writer(codecs.lowerWriter) value: String
)
```

### `@flatten`

Flatten a nested case class so its fields are read/written directly in the parent document.

```scala
import hearth.kindlings.reactivemongobsonderivation.annotations.flatten

case class Range(start: Int, end: Int)
case class LabelledRange(name: String, @flatten range: Range)

val handler = KindlingsBsonDocumentHandler.derived[LabelledRange]

handler.writeTry(LabelledRange("r1", Range(2, 5))).get
// BSONDocument("name" -> "r1", "start" -> 2, "end" -> 5)

handler.readDocument(BSONDocument("name" -> "r1", "start" -> 2, "end" -> 5)).get
// LabelledRange("r1", Range(2, 5))
```

## Examples

### Sealed trait / enum

```scala
sealed trait Shape
case class Circle(radius: Double) extends Shape
case class Square(side: Double) extends Shape

val handler = KindlingsBsonDocumentHandler.derived[Shape]

handler.writeTry(Circle(2.5)).get
// BSONDocument("className" -> "Circle", "radius" -> 2.5)

handler.readDocument(BSONDocument("className" -> "Square", "side" -> 3.0)).get
// Square(3.0)
```

### Collections and options

```scala
case class Order(id: String, items: List[String], discount: Option[Double])

val handler = KindlingsBsonDocumentHandler.derived[Order]
handler.writeTry(Order("o1", List("apple", "banana"), Some(0.1))).get
// BSONDocument("id" -> "o1", "items" -> BSONArray("apple", "banana"), "discount" -> 0.1)

handler.readDocument(BSONDocument("id" -> "o2", "items" -> BSONArray())).get
// Order("o2", List(), None)
```

### Default values

```scala
case class Settings(name: String = "default", timeout: Int = 30)

val handler = KindlingsBsonDocumentHandler.derived[Settings]
handler.readDocument(BSONDocument()).get
// Settings("default", 30)
```

## Limitations

- Scala 3 only (Scala 2.13 cross-compilation is a future task)
- JVM only (Scala.js / Scala Native are not applicable — `reactivemongo-bson-api` is JVM-only)
- `Map` key type is fixed to `String`; non-`String` keys are not supported
- Sealed trait hierarchies must be reachable from the derived type (no orphan sub-hierarchies)
- `@flatten` with conflicting inner field names is not detected at compile time; the resulting BSON document will have duplicate keys
- Case class fields of collection type with `Option[CaseClass]` elements are not yet supported (e.g. `items: Seq[Option[MyCaseClass]]`)
- No `UnionType` for non-sealed ADTs
