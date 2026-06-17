# Migrating from ReactiveMongo BSON Macros

This guide documents the differences between the **ReactiveMongo BSON** macro
derivation (`reactivemongo.api.bson.Macros`) and the **Kindlings BSON Derivation**
(`hearth.kindlings.reactivemongobsonderivation`). It is intended for users
considering or planning a migration.

## Why migrate?

- **Hearth-based macro infrastructure**: maintains correctness across recursive
  types, generic case classes, and sealed traits without `AutomaticMaterialization`
  opt-ins
- **Always-on defaults**: Scala-level default values and `@defaultValue` annotations
  work out of the box — no `ReadDefaultValues` flag needed
- **Same annotation API**: `@fieldName` (instead of `@Key`), `@reader`, `@writer`,
  `@flatten`, `@noneAsNull`, `@defaultValue` — familiar ergonomics
- **Structured config**: `BsonDocumentHandlerConfig` with `FieldNaming` and
  `TypeNaming` sealed traits and composable helpers
- **Generated code**: follows the same patterns as jsoniter-derivation, circe-derivation
  and other Kindlings modules — consistent ecosystem

## Setup

### Before (ReactiveMongo BSON)

```scala
// build.sbt
libraryDependencies += "org.reactivemongo" %% "reactivemongo-bson-macros" % "1.1.0"

// usage
import reactivemongo.api.bson._
import reactivemongo.api.bson.Macros.using
import Macros.Options._

case class Person(name: String, age: Int)

implicit val handler: BSONDocumentHandler[Person] = {
  implicit val cfg = MacroConfiguration()
  Macros.handler[Person]
}
```

### After (Kindlings)

```scala
// build.sbt
libraryDependencies += "com.kubuszok" %% "kindlings-reactivemongo-bson-derivation" % "{{ kindlings_version() }}"

// usage
import hearth.kindlings.reactivemongobsonderivation._

case class Person(name: String, age: Int)

implicit val config: BsonDocumentHandlerConfig = BsonDocumentHandlerConfig.default
implicit val handler: KindlingsBsonDocumentHandler[Person] =
  KindlingsBsonDocumentHandler.derived[Person]
```

## API Comparison

| ReactiveMongo BSON | Kindlings BSON |
|---|---|
| `Macros.handler[T]` | `KindlingsBsonDocumentHandler.derived[T]` |
| `Macros.reader[T]` | **Not supported** — only combined handler |
| `Macros.writer[T]` | **Not supported** — only combined handler |
| `MacroConfiguration()` | `BsonDocumentHandlerConfig.default` |
| `MacroOptions.ReadDefaultValues` | **Always-on** — no opt-in needed |
| `MacroOptions.AutomaticMaterialization` | **Always-on** — no opt-in needed |
| `MacroOptions.UnionType[ ... ]` | **Not supported** — sealed traits only |
| `MacroOptions.DisableWarnings` | **Not supported** — info logs are always on |

## Type Class Hierarchy

| Concept | ReactiveMongo | Kindlings |
|---|---|---|
| Combined handler | `BSONDocumentHandler[T]` | `KindlingsBsonDocumentHandler[T]` (extends `BSONDocumentHandler[T]`) |
| Reader only | `BSONDocumentReader[T]` / `BSONReader[T]` | Same types — field-level resolution uses `BSONReader[T]` |
| Writer only | `BSONDocumentWriter[T]` / `BSONWriter[T]` | Same types — field-level resolution uses `BSONWriter[T]` |
| Handler instance | `Macros.handler[T]` returns `BSONDocumentHandler[T]` | `KindlingsBsonDocumentHandler.derived[T]` returns `KindlingsBsonDocumentHandler[T]` |

The output type `KindlingsBsonDocumentHandler[T]` is a subtype of the standard
`BSONDocumentHandler[T]` and can be used anywhere a `BSONDocumentHandler[T]`,
`BSONReader[T]`, `BSONWriter[T]`, or `BSONHandler[T]` is expected.

## Annotations

| Feature | ReactiveMongo | Kindlings | Notes |
|---|---|---|---|
| Override field key | `@Key("name")` | `@fieldName("name")` | Same behavior |
| Custom reader per field | `@Reader` (type annotation) | `@reader(instance)` | Kindlings uses a value argument |
| Custom writer per field | `@Writer` (type annotation) | `@writer(instance)` | Kindlings uses a value argument |
| Flatten inner fields | `@Flatten` | `@flatten` | Same behavior |
| None as BSONNull | `@NoneAsNull` | `@noneAsNull` | Same behavior |
| Default value override | `@DefaultValue("val")` | `@defaultValue(val)` | Kindlings: argument is the field type, not string |
| Ignore field | `@Ignore` | **Not supported** | Use `Option` with default |
| Skip field | `transient` | **Not supported** | Manual reader/writer needed |

### Custom reader/writer differences

In ReactiveMongo BSON, `@Reader` and `@Writer` are **type annotations** applied
to the field's type:

```scala
case class Foo(@Reader BSONObjectID _id)
```

In Kindlings, `@reader` and `@writer` take a **value argument**:

```scala
implicit val objectIdReader: BSONReader[BSONObjectID] = ???

case class Foo(@reader(objectIdReader) _id: BSONObjectID)
```

## Configuration

### ReactiveMongo BSON

```scala
implicit val cfg = MacroConfiguration.withOptions[MacroOptions.Verbose]
  .withFieldNaming(MacroOptions.SnakeCase)
  .withTypeNaming(TypeNaming.SimpleName)
```

### Kindlings BSON

```scala
implicit val config: BsonDocumentHandlerConfig =
  BsonDocumentHandlerConfig.default
    .withFieldNaming(FieldNaming.SnakeCase)
    .withTypeNaming(TypeNaming.SimpleName)
```

Available config knobs:

| Setting | Kindlings API | Default |
|---|---|---|
| Field name mapping | `config.fieldNameMapper` / `config.withFieldNaming(naming)` | Identity |
| Discriminator field name | `config.discriminatorFieldName` | `"className"` |
| Type naming | `config.typeNaming` / `config.withTypeNaming(naming)` | `TypeNaming.FullName` |
| Optional formatting | `config.noneAsNull` / `config.withNoneAsNull` | `false` |

## Behavioral Differences

### 1. Default values are always-on

**Reference**: Requires `ReadDefaultValues` opt-in.
**Kindlings**: Always applied. Scala-level defaults and `@defaultValue` both work.

Migration: If you explicitly omitted `ReadDefaultValues` to suppress defaults,
you may see different behavior. Add explicit `Option` types or `@fieldName` to
handle missing fields the way you want.

### 2. No `AutomaticMaterialization` opt-in

**Reference**: Sealed trait member derivation requires
`AutomaticMaterialization` (off by default).
**Kindlings**: Always auto-materialized.

Migration: Remove `AutomaticMaterialization` flag — sealed trait handlers
are always derived.

### 3. Discriminator defaults to `FullName`

**Reference**: Default discriminator value is `FullName`
(e.g., `"com.example.Tree.Leaf"`).
**Kindlings**: Same — default is `FullName`.

Migration: No change needed. If you were using `SimpleName` short names,
set `config.withTypeNaming(TypeNaming.SimpleName)`.

### 4. `BSONNull` always decodes as `None`

**Reference**: Without `@noneAsNull`, a `BSONNull` value for an `Option` field
would **fail to read** (field expected to be missing, not present as null).
**Kindlings**: `BSONNull` always decodes as `None`.

Migration: If you relied on the strict behavior to catch unexpected `BSONNull`
values, add validation in your application layer.

### 5. No separate Reader / Writer derivation

**Reference**: `Macros.reader[T]` / `Macros.writer[T]`.
**Kindlings**: Only combined `KindlingsBsonDocumentHandler.derived[T]`.

Migration: Use the combined handler everywhere — it provides both `readTry`
and `writeTry`. For read-only or write-only use, just call the relevant method.

### 6. No `UnionType` / non-sealed ADTs

**Reference**: Supports `UnionType[Foo \/ Bar]` for non-sealed hierarchies.
**Kindlings**: Sealed traits only.

Migration: Refactor hierarchies to use sealed traits, or provide manual
`BSONDocumentHandler` instances for non-sealed types.

### 7. No `@Ignore` annotation

**Reference**: `@Ignore` on a field means it is never serialized to BSON
(completely absent from the document). If the field must be readable,
a default must be provided.
**Kindlings**: Not supported.

Migration: Use `Option` with `None` default. The field will be written as
missing/null and read back as `None`, which is semantically similar for
most use cases. For fields that should never appear in BSON, provide a
custom reader/writer via `@reader`/`@writer` that skips the field.

### 8. Non-`String` map keys

**Reference**: Supports non-`String` keys (e.g. `Map[Locale, V]`, `Map[UUID, V]`)
via the `KeyReader[T]` / `KeyWriter[T]` type classes from reactivemongo-bson-api.

**Kindlings**: Supported. The macro summons `KeyReader[K]` and `KeyWriter[K]`
from implicit scope when deriving `Map[K, V]` handlers. Built-in instances
for `String`, `Int`, `Long`, `Double`, `Float`, `Short`, `Byte`, `Char`,
`BigDecimal`, `BigInt`, `Locale`, `UUID`, and `AnyVal` are available from
reactivemongo-bson-api. Custom key types need an explicit `KeyReader[T]` /
`KeyWriter[T]` implicit.

Migration: No change needed — should work out of the box.

## Migration Steps

### Step 1: Add dependency

```scala
libraryDependencies += "com.kubuszok" %% "kindlings-reactivemongo-bson-derivation" % "{{ kindlings_version() }}"
```

### Step 2: Replace imports

```scala
// Before
import reactivemongo.api.bson._
import reactivemongo.api.bson.Macros.using
import Macros.Options._

// After
import hearth.kindlings.reactivemongobsonderivation._
```

### Step 3: Replace handler derivation

```scala
// Before
implicit val handler: BSONDocumentHandler[MyClass] = Macros.handler[MyClass]

// After
implicit val config: BsonDocumentHandlerConfig = BsonDocumentHandlerConfig.default
implicit val handler: KindlingsBsonDocumentHandler[MyClass] =
  KindlingsBsonDocumentHandler.derived[MyClass]
```

### Step 4: Replace annotations

| Before | After |
|---|---|
| `@Key("name")` | `@fieldName("name")` |
| `@Flatten` | `@flatten` |
| `@NoneAsNull` | `@noneAsNull` |
| `@DefaultValue("str")` | `@defaultValue("str")` |
| `@Reader SomeType` | `@reader(instance)` |
| `@Writer SomeType` | `@writer(instance)` |

### Step 5: Adjust sealed trait discriminators

If you relied on `SimpleName` discriminator values,
add explicit config:

```scala
implicit val config: BsonDocumentHandlerConfig =
  BsonDocumentHandlerConfig.default.withTypeNaming(TypeNaming.SimpleName)
```

### Step 6: Test round-trips

Run your existing BSON round-trip tests. Key areas to verify:

- Default values are now always applied (may affect reader behavior)
- `BSONNull` for `Option` fields now decodes (instead of failing)
- Discriminator strings match by default (`FullName`; use `TypeNaming.SimpleName` for short names)
- Recursive types and generic case classes should work without special setup

## FAQ

**Q: Can I still use `BSONDocumentReader[T]` / `BSONDocumentWriter[T]` in my code?**

Yes. `KindlingsBsonDocumentHandler[T]` extends `BSONDocumentHandler[T]` which
extends `BSONDocumentReader[T]` and `BSONDocumentWriter[T]`. Any API expecting
a reader or writer will work with a derived handler.

**Q: What about `BSONReader[T]` and `BSONWriter[T]`?**

Same answer — `KindlingsBsonDocumentHandler[T]` extends `BSONHandler[T]` which
extends `BSONReader[T]` and `BSONWriter[T]`. The derived handler is both.

**Q: My sealed trait discriminator values changed after migration. Why?**

They should not have changed — Kindlings defaults to `TypeNaming.FullName`,
matching the reference. If you were using short (simple) discriminator
names before, set `config.withTypeNaming(TypeNaming.SimpleName)`.

**Q: I have custom `BSONReader`/`BSONWriter` implicits for certain types (like `BSONObjectID`).**

They will be picked up automatically — the field-level resolution summons
existing `BSONReader`/`BSONWriter` instances before attempting derivation.

**Q: Can I mix Kindlings-derived handlers with manually written ones?**

Absolutely. `KindlingsBsonDocumentHandler` is just `BSONDocumentHandler`.
Manual handlers are used as fallback for field-level resolution.

**Q: Does this work with Scala.js or Scala Native?**

No. reactivemongo-bson-api is JVM-only, so Kindlings BSON is JVM-only too.

**Q: What about compile-time overhead?**

The macro uses Hearth's `DerivationTimeout` (default 5s). For large hierarchies
or deeply nested sealed traits, set a longer timeout via `-Xmacro-settings`:

```
-Xmacro-settings:bsonDocumentHandler.timeout=60s
```

## Reference

For a complete per-feature comparison with the ReactiveMongo BSON macros,
see the [reference comparison](../REFERENCE-COMPARISON.md) and
[reference test porting status](../REFERENCE-TESTS.md) documents in the
project repository.
