# Reference Implementation Comparison

Comparison of `reactivemongo-bson-derivation` against the original `reactivemongo/bson` macro implementation (located in `./ReactiveMongo-BSON/`).

## Summary

Our implementation matches the reference on the core behavior: case class read/write, sealed trait discrimination with `"className"`, options, collections, value types. The main **intentional differences** are listed below.

## Behavioral Differences

### 1. Default values are applied on read

**Reference**: The current macro implementation computes a field's Scala default
or `@DefaultValue` annotation and applies it when the BSON field is absent. While
`MacroOptions.ReadDefaultValues` remains part of the public API, the current
Scala 2 and Scala 3 macro implementations do not gate this behavior on that
option.

**Ours**: Same behavior: Scala-level defaults and `@DefaultValue` are applied
when a BSON field is absent.

**Status**: **Done** (matches the current reference implementation).

### 2. `@NoneAsNull` annotation

**Reference**: An Option field annotated with `@NoneAsNull` writes `BSONNull` for `None`. Without it, `None` is omitted from the output.

**Ours**: `@NoneAsNull` annotation supported. Option fields annotated with `@NoneAsNull` write `BSONNull` for `None`; unannotated ones are omitted (same as before).

**Status**: **Done** (matches reference).

### 3. `BSONNull` is treated as `None` for Option fields on read

**Reference**: `BSONDocument.getAsUnflattenedTry` returns `Success(None)` for both
an absent field and `BSONNull`. The reference `MacroSpec` explicitly covers this
with `"support null for optional value"`.

**Ours**: Same behavior: an absent Option field and an Option field with
`BSONNull` both decode as `None`.

**Status**: **Done** (matches reference). `@NoneAsNull` controls writing only:
it writes `None` as `BSONNull` instead of omitting the field.

### 4. Discriminator value uses full (qualified) class name

**Reference**: Default `TypeNaming` is `FullName`:
- `sealed trait Foo; case class Bar extends Foo` → discriminator value: `Bar` (in the default package)
- Nested: `object TreeModule { sealed trait Node; case class Leaf extends Node }` → discriminator value: `TreeModule.Leaf` (full name with outer objects joined by `.`)
- Configurable via `MacroConfiguration(typeNaming = TypeNaming.SimpleName)` to get just the simple name.

**Ours**: Same default — `TypeNaming.FullName`. Also provides `TypeNaming.SimpleName` and `TypeNaming.Custom(f)`.

**Status**: **Done** — defaults now match.

### 5. Field naming strategies

**Reference**: `FieldNaming.Identity`, `SnakeCase`, `PascalCase`, `KebabCase`, plus a `FieldNaming` function for custom.

**Ours**: We provide both a structured `FieldNaming` trait (`Identity`, `SnakeCase`, `PascalCase`, `KebabCase`, `Custom`) and the underlying `String => String` function via `BsonDocumentHandlerConfig.fieldNameMapper`. `withFieldNaming(naming)` and the existing `withSnakeCaseFieldNames` / `withKebabCaseFieldNames` / `withPascalCaseFieldNames` helpers are available.

**Status**: **Done** (see task 8).

### 6. `UnionType` / non-sealed ADTs

**Reference**: Supports `UnionType[UA \/ UB]` for non-sealed trait unions with `AutomaticMaterialization`.

**Ours**: Not supported. Sealed traits only.

**Status**: **Deferred** (see task 8). Large feature; would require the user to explicitly enumerate subtypes and tie them together via scalaz's `\/` (or similar) type.

### 7. `@DefaultValue` annotation (per-field default override)

**Reference**: Has `@DefaultValue("default")` annotation that allows specifying a default for fields that don't have a Scala-level default value.

**Ours**: `@DefaultValue` is supported and applied for an absent field, matching
the current reference behavior. It accepts a value of the field's type.

**Status**: **Done** (see task 8).

### 8. `@Key` / `@Reader` / `@Writer` per-field annotations

**Reference**: Has `@Key("custom_name")` to override BSON key for a single field, `@Reader` and `@Writer` to provide custom handlers per field.

**Ours**: We have `@FieldName` (equivalent to `@Key`), `@Reader`, and `@Writer` annotations. `@Reader`/`@Writer` accept a `BSONReader[T]`/`BSONWriter[T]` instance that overrides the derived handler for a specific field.

**Status**: **Done** (see task 8).

### 9. `AutomaticMaterialization`

**Reference**: `AutomaticMaterialization` option auto-derives handlers for sealed-trait members. Default is OFF (must be explicit) to avoid infinite recursion.

**Ours**: We always auto-materialize handlers for sealed-trait members (similar to jsoniter's default).

**Status**: Intentional additive difference. It changes whether derivation
compiles, but not the BSON representation once an instance is available.

### 10. `DisableWarnings` and `Verbose` options

**Reference**: Compile-time options for controlling macro output and warnings.

**Ours**: No equivalent. We use `Environment.reportInfo` / `Environment.reportErrorAndAbort` unconditionally.

**Status**: **Not applicable** (see task 8). The reference's `DisableWarnings` is off by default; ours matches. We can add a config option to suppress macro logs if it ever becomes noisy.

### 11. `@Flatten` annotation

**Reference**: `@Flatten` on a field of a case class type flattens the inner case class's fields into the parent document, rather than nesting it as a sub-document.

**Ours**: `@Flatten` annotation supported. A flattened field is read/written by deriving a handler for the inner type and applying it directly to the parent document. Nested flattening works recursively.

**Status**: **Done** (see task 8). Flattening uses a user-provided standard
`BSONDocumentHandler` when available; fields without a document handler and
self- or mutually-recursive flattened fields fail derivation rather than
producing surprising runtime behavior. Caveat: conflicting inner field names are
not detected at compile time.

## Same Behavior

- **Default discriminator field name**: `"className"` (both)
- **Default field naming**: identity (both)
- **Option read**: missing field → `None` (both)
- **Sealed trait discrimination with discriminator field** (both)
- **Collection read/write** (both)
- **Value type (`AnyVal`) read/write** (both)
- **Map support** (includes non-`String` keys via `KeyReader`/`KeyWriter`) (both)
- **Empty case classes** (both)
- **Self-references / recursive types** (both)
- **Generic case classes** (both)

## Test Cases Worth Porting

The following reference tests cover edge cases we should also test. All are covered:

1. **`Optional` field with `BSONNull` and missing** — covered (`@NoneAsNull - explicit BSONNull` test)
2. **Recursive structure** (e.g., `Tree`) — covered (`recursive structure (Tree)` test)
3. **Generic case class** (`GenSeq`) — ported using `GenSeq[Option[SingleBigDecimal]]`
4. **Empty case class** — covered
5. **`@Key` / `@FieldName` annotation** — covered
6. **Sealed family with case objects** — covered
7. **Custom field naming** (SnakeCase, PascalCase) — covered
8. **Union types (ADT)** — partial coverage (sealed traits only; see limitation #6)
9. **Self-reference** — covered (recursive structure test)
10. **`@Ignore` skip** — covered (`@Ignore field is not serialized`)
11. **Non-String map keys** — covered (`Map[Locale, String]`, `Map[UUID, Int]`)

### Recursive Structure Limitation (resolved)

Recursive types (e.g., `sealed trait Tree; case class Node(left: Tree, right: Tree) extends Tree`) **now compile and work** with our implementation (see task 7 in `TASKS.md`).

**Root cause was**: The `setHelper` pattern eagerly evaluated the helper body, so recursive references to the cached handler didn't find it (the helper was being built, not yet in the cache).

**Fix applied**: `setHelper` now uses `cache.buildCachedWith` + `MIO.scoped` (jsoniter pattern) to store the helper MIO without evaluating it; the body is evaluated later (when the def is emitted), so recursive types can find the cached helper before its body is built.

## Reference Files

- `./ReactiveMongo-BSON/api/src/main/scala/MacroConfiguration.scala` — config defaults
- `./ReactiveMongo-BSON/api/src/main/scala/MacroOptions.scala` — compile-time options
- `./ReactiveMongo-BSON/api/src/main/scala/MacroAnnotations.scala` — annotations
- `./ReactiveMongo-BSON/api/src/main/scala-3/MacroImpl.scala` — Scala 3 macro
- `./ReactiveMongo-BSON/api/src/main/scala-2/MacroImpl.scala` — Scala 2 macro
- `./ReactiveMongo-BSON/api/src/test/scala/MacroSpec.scala` — comprehensive test suite
