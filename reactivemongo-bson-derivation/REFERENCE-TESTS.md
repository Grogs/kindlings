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
| `"write empty option as null"` | `support optional as null` | Uses `@NoneAsNull` |
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
| `"support overriding keys with annotations"` | `support overriding keys with annotations` | Uses `@FieldName` instead of `@Key` |
| `"be generated for class class with self reference"` | `be generated for class with self reference` | |
| `"support @Flatten annotation"` | `@Flatten merges inner case class fields into parent document`, `@Flatten uses a user-provided BSONDocumentHandler`, `@Flatten on a non-document field fails derivation`, `recursive @Flatten fails derivation`, `mutually recursive @Flatten fails derivation` | Covers derived/external handlers and invalid targets |
| `"support @Reader & @Writer annotations"` | `round-trip with @reader and @writer` | |
| `"be generated for Value class"` | `be generated for value class` | |
| Default-value tests | `default values from Scala-level defaults`, `default values from @defaultValue annotation` | |
| Map tests | `Map[String, Int]`, `Map with String keys` | |
| `BSONObjectID` field | `support overriding keys with annotations` | Uses `BSONObjectID` |
| `@Ignore` field (`"skip ignored fields"`) | `@Ignore field is not serialized` | Uses `@Ignore`; value supplied by default on read, omitted from BSON on write |
| Non-String map keys (`WithMap1[java.util.Locale, String]`, `WithMap2[FooVal, String]`) | `Map[Locale, String]`, `Map[UUID, Int]`, `Map with a user-provided value-class key codec` | Summons built-in and user-provided `KeyReader`/`KeyWriter` instances |
| `@DefaultValue` with `Option[Float]` (`WithDefaultValues2.score`) | `default values from @DefaultValue annotation` | Covariant `defaultValue` + `<:<` lookup |
| `TypeNaming` tests | `FullName discriminator includes enclosing objects`, `Custom type naming transforms simple name`, `SimpleName discriminator survives nested sealed trait` | Default `FullName`; `SimpleName` opt-in |
| `@Flatten` nested test | `@Flatten works with nested flattening`, `@Flatten merges inner case class fields into parent document` | Nested positive coverage |

## Reference tests not ported

### Utility macros (`"Utility macros"`)

**Not ported.** These test `migrationRequired` and other non-derivation macros
that are not part of `KindlingsBsonDocumentHandler`.

### Configuration resolution (`"Configuration"`)

**Ported / adapted.** Our config is passed as an implicit `BsonDocumentHandlerConfig`
(similar to reference's implicit `MacroConfiguration`) through a single
`derived[A](using config)` entry point. The field-naming tests cover evaluable
configs, while `runtime field-name mapper fallback captures call-site values`
verifies the non-evaluable call-site fallback.

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

**Ported** (`@Ignore field is not serialized`). An `@Ignore`d field is omitted
from the written BSON and receives its Scala default value or `@DefaultValue` on
read. Derivation fails when no default is available, matching the reference.

### Case class with refinement type as field (`"handle case class with refinement type as field"`)

**Ported** (`custom handlers work for refinement-typed fields`). The test supplies
custom `BSONReader`/`BSONWriter` instances for `PrefKind.Aux[String]`, verifying
that user-provided handlers take precedence over automatic derivation.

### Nested traits with full-name discriminator (`"support automatic implementations search with nested traits"`)

**Partially ported.** Both use `TypeNaming.FullName` as the default now (matching
the reference). Covered by `FullName discriminator includes enclosing objects` and
`SimpleName discriminator survives nested sealed trait` (opting into `SimpleName`).

### Specific annotation combinations

- `@Flatten @Writer(...) @Reader(...)` on the same field (`"support @Reader & @Writer annotations"`)
  **Ported** (`@Flatten composes with @Reader and @Writer`). A flattened custom
  reader receives the containing `BSONDocument`; its custom writer must produce a
  `BSONDocument`, whose elements are merged into the containing document.
- `@Ignore` combined with `@Reader`/`@Writer` on the same field: not tested;
  `@Ignore` intentionally short-circuits read and write before the custom handler
  is consulted.
- Invalid `@Reader`/`@Writer` types are rejected at derivation time (`@Reader with
  the wrong field type fails derivation`, `@Writer with the wrong field type fails
  derivation`), matching the reference's validation behavior.

### Option BSONNull handling (`"not support type mismatch for optional value"`, `"support null for optional value"`)

**Ported.** `Option field - None (explicit BSONNull)` verifies that `BSONNull`
decodes as `None`, matching the reference. A non-null BSON value with the wrong
type is delegated to the inner `BSONReader` and fails, as in the reference.

## Coverage summary

- **Total reference `MacroSpec` test cases**: ~75 top-level test groups, ~199
  individual assertions (including nested `in` blocks).
- **Ported / adapted**: ~36 top-level behaviors (78 tests in our suite).
- **Skipped**: features we explicitly decided not to support (`UnionType`,
  separate Reader/Writer derivation, strict `BSONNull` on un-annotated fields).
- **Intentional differences**: see `REFERENCE-COMPARISON.md` for the complete list.
- **Test suite**: 78 tests currently passing.
