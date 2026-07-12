# ReactiveMongo BSON Derivation

Drop-in replacement for ReactiveMongo BSON macros — derives standard `BSONDocumentHandler` instances for case classes, sealed traits, Scala 3 enums, value types, options, collections, and maps.

Derived `KindlingsBsonDocumentHandler[A]` instances extend ReactiveMongo's `BSONDocumentHandler[A]`, so they can be passed anywhere a `BSONDocumentHandler`, `BSONDocumentReader`, `BSONDocumentWriter`, `BSONReader`, or `BSONWriter` is required.

When an application needs only one document direction, derive that direction directly:

```scala
val reader: BSONDocumentReader[Person] = KindlingsBsonDocumentReader.derived[Person]
val writer: BSONDocumentWriter[Person] = KindlingsBsonDocumentWriter.derived[Person]
```

The reader derives and requires only read capabilities; the writer derives and requires only write capabilities. A
combined `KindlingsBsonDocumentHandler` derives both directions and reports failures from both sides together.

## Installation

!!! example "sbt"

    ```scala
    libraryDependencies += "com.kubuszok" %% "kindlings-reactivemongo-bson-derivation" % "{{ kindlings_version() }}"
    ```

!!! note
    This is a JVM-only module because `reactivemongo-bson-api` is JVM-only.

## Quick start

```scala
//> using scala {{ scala.2_13 }}
//> using dep com.kubuszok::kindlings-reactivemongo-bson-derivation:{{ kindlings_version() }}
//> using dep com.kubuszok::kindlings-fast-show-pretty:{{ kindlings_version() }}

import hearth.kindlings.fastshowpretty._
import hearth.kindlings.reactivemongobsonderivation._
import reactivemongo.api.bson.{ BSONDocument, BSONDocumentHandler }

case class Person(name: String, age: Int)

implicit val handler: BSONDocumentHandler[Person] =
  KindlingsBsonDocumentHandler.derived[Person]

val document = handler.writeTry(Person("Alice", 30)).get
assert(document == BSONDocument("name" -> "Alice", "age" -> 30))

println(FastShowPretty.render(handler.readDocument(document).get, RenderConfig.Default))
// expected output:
// Person(
//   name = "Alice",
//   age = 30
// )
```

`derived[A]` also supports sanely-automatic derivation: place a derived instance in a companion object or implicit scope and it is used for nested fields.

### Inline writing

When only serialization is needed, `KindlingsBsonDocumentHandler.write(value)` is a supported inline alternative. It emits the BSON write path directly instead of allocating a handler instance:

```scala
val document = KindlingsBsonDocumentHandler.write(Person("Alice", 30)).get
assert(document == BSONDocument("name" -> "Alice", "age" -> 30))
```

It uses the same implicit `BsonDocumentHandlerConfig` and honors an existing `BSONDocumentHandler[A]`.

## Supported types

| Type | Notes |
|---|---|
| Case classes | Including nested and generic case classes |
| Sealed traits and Scala 3 enums | Uses a BSON discriminator |
| `Option[A]` | Missing fields and `BSONNull` decode as `None` |
| `AnyVal` value classes | Encoded as their underlying value |
| Scala 3 named tuples | Including single-element named tuples; field labels become BSON keys |
| Collections | `List`, `Seq`, `Vector`, `Set`, `Array`, and standard supported collection types |
| Maps | `Map[K, V]`; non-`String` keys need `KeyReader[K]` to read and `KeyWriter[K]` to write |
| Existing BSON codecs | User-provided `BSONReader[A]` and `BSONWriter[A]` take precedence in their respective directions |

## Configuration

`BsonDocumentHandlerConfig` is resolved from implicit scope. Its default configuration uses identity field names, the `"className"` discriminator field, fully-qualified subtype names, and ignores unexpected BSON fields while decoding.

```scala
import hearth.kindlings.reactivemongobsonderivation._

implicit val config: BsonDocumentHandlerConfig =
  BsonDocumentHandlerConfig.default
    .withSnakeCaseFieldNames
    .withDiscriminatorFieldName("type")
    .withTypeNaming(TypeNaming.SimpleName)
    .withSkipUnexpectedFields(false)
```

| Method | Effect |
|---|---|
| `withFieldNameMapper(f)` | Use an arbitrary field-name mapping function |
| `withFieldNaming(FieldNaming.SnakeCase)` | Use a structured field-name strategy |
| `withSnakeCaseFieldNames`, `withKebabCaseFieldNames`, `withPascalCaseFieldNames` | Common field-name mappings |
| `withDiscriminatorFieldName(name)` | Set the sealed-ADT discriminator field |
| `withTypeNaming(TypeNaming.SimpleName)` | Use short subtype names instead of the default fully-qualified names |
| `withSkipUnexpectedFields(false)` | Fail decoding when the document contains unknown fields |

`FieldNaming` provides `Identity`, `SnakeCase`, `PascalCase`, `KebabCase`, and `Custom`. `TypeNaming` provides `FullName`, `SimpleName`, and `Custom`.

## Field annotations

Import annotations from `hearth.kindlings.reactivemongobsonderivation.annotations`.

| Annotation | Effect |
|---|---|
| `@FieldName("key")` | Override the BSON key for one field |
| `@NoneAsNull` | Write a `None` option as `BSONNull`; otherwise it is omitted |
| `@DefaultValue(value)` | Value to use when the BSON field is missing |
| `@Ignore` | Omit a field on write; it must have a Scala default or `@DefaultValue` for decoding |
| `@Reader(reader)` | Use this `BSONReader` for the field |
| `@Writer(writer)` | Use this `BSONWriter` for the field |
| `@Flatten` | Read/write a nested document's elements directly in the enclosing document |

### Field names, defaults, and options

```scala
import hearth.kindlings.reactivemongobsonderivation._
import hearth.kindlings.reactivemongobsonderivation.annotations.{ DefaultValue, FieldName, NoneAsNull }

case class Account(
  @FieldName("user_id") id: String,
  @DefaultValue(3) retries: Int,
  @NoneAsNull note: Option[String]
)

val handler = KindlingsBsonDocumentHandler.derived[Account]
handler.writeTry(Account("a-1", 3, None)).get
// BSONDocument("user_id" -> "a-1", "retries" -> 3, "note" -> BSONNull)
```

A Scala constructor default and `@DefaultValue` are applied when the corresponding BSON field is absent. `BSONNull` always decodes as `None` for an `Option` field.

### Custom field codecs

`@Reader` and `@Writer` take codec *values*. This makes the selected codec explicit and local to the field.

```scala
import hearth.kindlings.reactivemongobsonderivation.annotations.{ Reader, Writer }
import reactivemongo.api.bson.{ BSONReader, BSONString, BSONWriter }

object codecs {
  implicit val upperReader: BSONReader[String] = BSONReader.collect { case BSONString(value) => value.toUpperCase }
  implicit val lowerWriter: BSONWriter[String] = BSONWriter[String](value => BSONString(value.toLowerCase))
}

case class Styled(
  @Reader(codecs.upperReader) label: String,
  @Writer(codecs.lowerWriter) value: String
)
```

Use at most one `@Reader` and one `@Writer` per field. An annotation whose codec has the wrong field type is rejected during derivation.

### Flattening nested documents

```scala
import hearth.kindlings.reactivemongobsonderivation._
import hearth.kindlings.reactivemongobsonderivation.annotations.Flatten

case class Range(start: Int, end: Int)
case class LabelledRange(name: String, @Flatten range: Range)

val handler = KindlingsBsonDocumentHandler.derived[LabelledRange]
handler.writeTry(LabelledRange("r1", Range(2, 5))).get
// BSONDocument("name" -> "r1", "start" -> 2, "end" -> 5)
```

A flattened field must have a document codec. Kindlings uses an existing `BSONDocumentHandler[A]`, or separately supplied `BSONDocumentReader[A]` and `BSONDocumentWriter[A]`, before attempting nested derivation. Direct and mutual recursive flattening are rejected at compile time.

## Sealed traits and collections

```scala
import hearth.kindlings.reactivemongobsonderivation._

sealed trait Shape
case class Circle(radius: Double) extends Shape
case class Square(side: Double) extends Shape

// The default is TypeNaming.FullName. Use short names when that is your wire format.
implicit val config: BsonDocumentHandlerConfig =
  BsonDocumentHandlerConfig.default.withTypeNaming(TypeNaming.SimpleName)

val shapeHandler = KindlingsBsonDocumentHandler.derived[Shape]
shapeHandler.writeTry(Circle(2.5)).get
// BSONDocument("className" -> "Circle", "radius" -> 2.5)

case class Order(id: String, items: List[String], discount: Option[Double])
val orderHandler = KindlingsBsonDocumentHandler.derived[Order]
orderHandler.writeTry(Order("o1", List("apple", "banana"), Some(0.1))).get
// BSONDocument("id" -> "o1", "items" -> BSONArray("apple", "banana"), "discount" -> 0.1)
```

For a map whose keys are not `String`, ReactiveMongo BSON represents keys as document field names. Supply the
direction needed by the derived type class; a combined handler needs both:

```scala
import reactivemongo.api.bson.{ KeyReader, KeyWriter }

final case class UserId(value: String)
implicit val userIdReader: KeyReader[UserId] = KeyReader(UserId.apply)
implicit val userIdWriter: KeyWriter[UserId] = KeyWriter(_.value)
```

`KindlingsBsonDocumentReader.derived` requires only `KeyReader`; `KindlingsBsonDocumentWriter.derived` requires only
`KeyWriter`; `KindlingsBsonDocumentHandler.derived` requires both.

## Comparison with ReactiveMongo BSON macros

Both implementations produce standard ReactiveMongo BSON type classes and support naming configuration, non-string
map keys, and field annotations. Kindlings' main difference is that one root declaration recursively derives more of
the model graph:

```scala
val handler = KindlingsBsonDocumentHandler.derived[Root]
```

### Feature differences

| Feature | ReactiveMongo BSON macros | Kindlings |
|---|---|---|
| Nested case classes | Child handlers or automatic-materialization configuration may be required | Derived recursively from the root |
| Sealed and recursive ADTs | Per-subtype handlers and `UnionType` plumbing may be required | Derived recursively from one root declaration |
| `AnyVal` fields | A separate `Macros.valueHandler` is required | Unwrapped automatically while deriving the parent |
| Scala constructor defaults | Requires `MacroOptions.ReadDefaultValues` | Honored automatically for missing fields |
| Nested `@Flatten` fields | Child document handlers must be materialized | Flattened children derive recursively from the root |
| Scala 3 derivation syntax | `Macros.handler[A]` | Also supports `derives KindlingsBsonDocumentHandler` |
| Scala 3 named tuples | Handler derivation is not supported | Supported, including single-element named tuples |
| Standalone document codecs | `Macros.reader[A]` / `Macros.writer[A]` | `KindlingsBsonDocumentReader.derived[A]` / `KindlingsBsonDocumentWriter.derived[A]`, each directional |
| One-off serialization | Materialize a writer or handler | `KindlingsBsonDocumentHandler.write(value)` emits only the write path |
| Naming, map keys, and field annotations | Supported | Supported |

### Less handler plumbing

Value-class fields are handled inline. Given:

```scala
final class UserId(val value: Int) extends AnyVal
final case class User(id: UserId, name: String)
```

Kindlings needs only the enclosing handler:

```scala
val userHandler = KindlingsBsonDocumentHandler.derived[User]
// User(UserId(42), "Alice") -> BSONDocument("id" -> 42, "name" -> "Alice")
```

With ReactiveMongo's macros, deriving `User` directly reports that no `BSONWriter[UserId]` exists; a separate
`Macros.valueHandler[UserId]` must first be placed in implicit scope.

The same root-only behavior applies to nested flattening:

```scala
case class Coordinates(x: Int, y: Int)
case class Address(city: String, @Flatten coordinates: Coordinates)
case class Person(name: String, @Flatten address: Address)

val personHandler = KindlingsBsonDocumentHandler.derived[Person]
// BSONDocument("name" -> "Alice", "city" -> "Paris", "x" -> 1, "y" -> 2)
```

Kindlings recursively derives both flattened children. ReactiveMongo can produce the same BSON representation, but
requires their document handlers to be materialized separately.

### Defaults without a second switch

For a model such as:

```scala
case class Account(id: Int, retries: Int = 3)
```

an ordinary Kindlings handler reads `BSONDocument("id" -> 1)` as `Account(1, 3)`. ReactiveMongo's macros support the
same constructor default only when derivation opts into `MacroOptions.ReadDefaultValues`. Kindlings treats the Scala
default as the model's missing-field behavior without requiring every derivation site to repeat that policy.

These are developer-experience and maintenance differences, not runtime-performance claims. ReactiveMongo offers many
of the same underlying mechanisms; Kindlings reduces the declarations and configuration needed to compose them.

## Migrating from ReactiveMongo macros

The derived handler is compatible with ReactiveMongo APIs because it is a `BSONDocumentHandler[A]`. The derivation entry point and configuration are Kindlings APIs:

| ReactiveMongo BSON | Kindlings BSON |
|---|---|
| `Macros.handler[A]` | `KindlingsBsonDocumentHandler.derived[A]` |
| `Macros.reader[A]` | `KindlingsBsonDocumentReader.derived[A]` |
| `Macros.writer[A]` | `KindlingsBsonDocumentWriter.derived[A]` |
| `MacroConfiguration()` | `BsonDocumentHandlerConfig.default` |
| `@Key("name")` | `@FieldName("name")` |
| type-position `@Reader` / `@Writer` | value-position `@Reader(reader)` / `@Writer(writer)` |
| `MacroOptions.AutomaticMaterialization` | Automatic for supported sealed hierarchies |
| `MacroOptions.ReadDefaultValues` | Constructor defaults and `@DefaultValue` are applied on missing fields |

Check existing BSON round-trip tests when migrating, especially for custom field codecs, discriminator configuration, and defaults.

## Limitations

- JVM only.
- Non-sealed legacy `UnionType` ADTs are not supported. Prefer a sealed protocol sub-hierarchy, even when the broader
  domain parent must remain open, or write a manual handler. For example, `sealed trait ExternalEvent extends Event`
  can contain only the event variants admitted by the BSON protocol while `Event` remains extensible.
- A flattened field's BSON key collisions are not detected at compile time.
- Regular non-case classes require a manual `BSONDocumentHandler`.

## Derivation policy

Structural derivation is allowed by default. Builds that use the shared derivation policy can require an explicit opt-in:

```text
-Xmacro-settings:reactivemongoBsonDerivation.policy.enabled=opt-in
```

Then import the marker where derivation is intended:

```scala
import hearth.kindlings.reactivemongobsonderivation.policy.allowDerivationForReactiveMongoBson
```

See the [derivation policy guide](derivation-policy.md) for allowed scopes and the complete setting reference.

## Debugging and timeout

Import the debug package to log derivation decisions:

```scala
import hearth.kindlings.reactivemongobsonderivation.debug._
```

The default derivation timeout is five seconds. Increase it for a large hierarchy with a compiler option:

```text
-Xmacro-settings:reactivemongoBsonDerivation.timeout=60s
```
