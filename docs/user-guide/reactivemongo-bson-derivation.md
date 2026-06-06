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
| Default field values | Applied when field is missing on read |

## Configuration

Customize derivation with `BsonDocumentHandlerConfig`:

```scala
import hearth.kindlings.reactivemongobsonderivation._

val customConfig = BsonDocumentHandlerConfig(
  fieldNameMapper = BsonDocumentHandlerConfig.snakeCase,
  discriminatorFieldName = Some("type"),
  skipUnexpectedFields = true
)

given BsonDocumentHandlerConfig = customConfig
val handler = KindlingsBsonDocumentHandler.derived[Person]
```

### `fieldNameMapper: String => String`

Transform field names during read and write. Default: `identity`.

Helpers:
- `BsonDocumentHandlerConfig.snakeCase`
- `BsonDocumentHandlerConfig.kebabCase`

```scala
case class CamelCaseFields(firstName: String, lastName: String)

given BsonDocumentHandlerConfig = BsonDocumentHandlerConfig().withSnakeCaseFieldNames
val handler = KindlingsBsonDocumentHandler.derived[CamelCaseFields]

handler.writeTry(CamelCaseFields("Alice", "Smith")).get
// BSONDocument("first_name" -> "Alice", "last_name" -> "Smith")
```

### `discriminatorFieldName: Option[String]`

The field name used for sealed-trait / enum discrimination. Default: `Some("className")` (aligned with ReactiveMongo-BSON).

Set to `None` to use wrapper-style encoding instead of discriminator-style:

```scala
given BsonDocumentHandlerConfig = BsonDocumentHandlerConfig().withoutDiscriminator
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
