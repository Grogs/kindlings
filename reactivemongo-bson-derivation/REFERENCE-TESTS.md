# Reference Test Porting Status

This document tracks which tests from the reference implementation
(`ReactiveMongo-BSON/api/src/test/scala/MacroSpec.scala`) have been ported to
`BsonDocumentHandlerSpec.scala`, and which ones have not (with reasons).

## Ported reference tests

The following `MacroSpec` tests are covered by our test suite:

| Reference test | Our test | Notes |
|---|---|---|
| `"handle primitives"` | `handle primitives` | |
| `"support nesting"` | `support nesting` | |
| `"support optional"` / `using default instances` | `support optional` | |
| `"write empty option as null"` | `support optional as null` | Uses `@noneAsNull` |
| `"support single member options"` | `support single member options` | |
| `"support generic optional value"` | `support generic optional value` | Handler only |
| `"support generic case class Foo"` | `support generic case class Foo` | |
| `"support generic case class GenSeq"` | `support generic case class GenSeq` | Now uses `GenSeq[Option[SingleBigDecimal]]` |
| `"support seq"` | `Seq[String]` | |
| `"support single member case classes"` | `single member case class` | |
| `"handle overloaded apply correctly"` | `handle overloaded apply` | |
| `"handle overloaded apply with different number of arguments correctly"` | `handle overloaded apply with different number of arguments` | |
| `"handle overloaded apply with 0 number of arguments correctly"` | `handle overloaded apply with 0 number of arguments` | |
| `"handle case class inside trait with handler outside"` | `handle case class inside trait with handler outside` | |
| `"not persist class name for case class"` | `not persist class name for case class` | |
| `"handle empty case classes"` | `handle empty case classes` | |
| `"respect field naming"` / `with macro-configured handler (SnakeCase)` | `snake_case field name mapper` | |
| `"automate Union on sealed traits with simple name"` | `FullName normalizes case object symbols` | Adapted to test `TypeNaming.FullName` |
| `"handle recursive structure"` / `with recursive auto-materialization` | `recursive structure (Tree)` | |
| `"support overriding keys with annotations"` | `support overriding keys with annotations` | Uses `@fieldName` instead of `@Key` |
| `"be generated for class class with self reference"` | `be generated for class with self reference` | |
| `"support @Flatten annotation"` | `@flatten merges inner case class fields into parent document` | Positive cases only |
| `"support @Reader & @Writer annotations"` | `round-trip with @reader and @writer` | |
| `"be generated for Value class"` | `be generated for value class` | |
| Default-value tests | `default values from Scala-level defaults`, `default values from @defaultValue annotation` | |
| Map tests | `Map[String, Int]`, `Map with String keys` | |
| `BSONObjectID` field | `support overriding keys with annotations` | Uses `BSONObjectID` |
| `TypeNaming` tests | `FullName discriminator includes enclosing objects`, `Custom type naming transforms simple name` | New feature tests |
| `@flatten` nested test | `@flatten works with nested flattening` | New feature test |

## Reference tests not ported

### Utility macros (`"Utility macros"`)

**Not ported.** These test `migrationRequired` and other non-derivation macros
that are not part of `KindlingsBsonDocumentHandler`.

### Configuration resolution (`"Configuration"`)

**Partially ported.** Our config is passed as an implicit `BsonDocumentHandlerConfig`
(similar to reference's implicit `MacroConfiguration`). We do not have separate
tests for "resolved from call site" vs "resolved from implicit scope" because our
API has a single `derived[A](using config)` entry point.

### Reader / Writer sections

**Mostly not ported.** The reference has separate `"Reader"` and `"Writer"`
sections testing `Macros.reader[A]` and `Macros.writer[A]`. Our module only
derives combined `BSONDocumentHandler` instances (`KindlingsBsonDocumentHandler`).
Any read/write behavior covered by those sections is exercised indirectly via
our handler round-trip tests.

### Union types / non-sealed ADTs (`"handle union types (ADT)"`, `"grab an implicit handler for type used in union"`, etc.)

**Not ported.** These require `MacroOptions.UnionType` (a type-level list of
allowed subtypes using scalaz-style `\/`). We decided to drop this feature
(see `REFERENCE-COMPARISON.md` #6).

### Automatic materialization opt-in (`"support auto-materialization for property types"`, some `"automate Union on sealed traits"` variants)

**Not ported.** The reference requires an explicit
`MacroOptions.AutomaticMaterialization` opt-in to derive handlers for sealed-trait
members. We always auto-materialize handlers for sealed traits, so the opt-in
behavior and the associated warning-suppression tests do not apply.

### `@Ignore` annotation (`"skip ignored fields"`)

**Not ported.** We do not support the `@Ignore` annotation. Fields are always
read/written. Users can achieve similar results by using `Option` with a default
or by defining a custom reader/writer.

### Case class with refinement type as field (`"handle case class with refinement type as field"`)

**Not ported.** This test relies on a custom `BSONHandler[PrefKind]` implicit
for a type with a refinement (`PrefKind.Aux[V]`). Porting it would require
reproducing the custom implicit setup rather than testing our macro.

### Nested traits with full-name discriminator (`"support automatic implementations search with nested traits"`)

**Not ported as-is.** These tests assume the reference's default `TypeNaming.FullName`.
We default to `TypeNaming.SimpleName`. The same hierarchy behavior is covered by
our sealed-trait tests; only the discriminator string differs.

### Specific annotation combinations

- `@Flatten @Writer(...) @Reader(...)` on the same field (`"support @Reader & @Writer annotations"`)
  **Not ported.** Our current implementation does not support combining `@flatten`
  with `@reader`/`@writer` on the same field. `@flatten` takes precedence.

### Strict BSONNull handling for Option (`"not support type mismatch for optional value"`, `"support null for optional value"` with strict semantics)

**Not ported.** We always decode `BSONNull` as `None` and are more permissive
than the reference (see `REFERENCE-COMPARISON.md` #3).

### Maps with non-String keys (`WithMap1[java.util.Locale, String]`, `WithMap2[FooVal, String]`)

**Ported.** Our map handler now summons `KeyReader[K]`/`KeyWriter[K]` for
non-String key types. Tested with `java.util.Locale` and `java.util.UUID` keys.

### `@defaultValue` with `Option` literal (`WithDefaultValues2.score: Option[Float]`)

**Ported.** Fixed by making `defaultValue[T]` covariant and using `<:<` for
annotation lookup, so `@defaultValue(Some(45.6f))` on an `Option[Float]` field
is found and applied correctly.

## Coverage summary

- **Total reference `MacroSpec` test cases**: ~75 top-level test groups, ~199
  individual assertions (including nested `in` blocks).
- **Ported / adapted**: ~22 top-level behaviors.
- **Skipped**: features we explicitly decided not to support (`UnionType`,
  `@Ignore`, separate Reader/Writer derivation, strict `BSONNull` semantics).
- **Intentional differences**: see `REFERENCE-COMPARISON.md` for the complete list.
