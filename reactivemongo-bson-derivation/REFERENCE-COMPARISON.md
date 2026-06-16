# Reference Implementation Comparison

Comparison of `reactivemongo-bson-derivation` against the original `reactivemongo/bson` macro implementation (located in `./ReactiveMongo-BSON/`).

## Summary

Our implementation matches the reference on the core behavior: case class read/write, sealed trait discrimination with `"className"`, options, collections, value types. The main **intentional differences** are listed below.

## Behavioral Differences

### 1. Default values are always applied (not opt-in)

**Reference**: `ReadDefaultValues` is required to opt in:
```scala
Macros.using[MacroOptions.ReadDefaultValues].reader[Foo]
// or
implicit val cfg = MacroConfiguration[MacroOptions.ReadDefaultValues]()
Macros.reader[Foo]
```

**Ours**: Default values are always applied. No opt-in required.

This is a friendly difference — users get default values out of the box.

### 2. `@NoneAsNull` annotation not supported

**Reference**: An Option field annotated with `@NoneAsNull` writes `BSONNull` for `None`. Without it, `None` is omitted from the output.

**Ours**: `@noneAsNull` annotation supported. Option fields annotated with `@noneAsNull` write `BSONNull` for `None`; unannotated ones are omitted (same as before).

**Status**: **Done** (see task 8).

### 3. `BSONNull` is always treated as `None` on read

**Reference**: Without `@NoneAsNull`, a `BSONNull` value for an Option field would fail to read (the field is expected to be missing, not present as null).

**Ours**: We always treat `BSONNull` as `None` (more permissive).

**Status**: Intentional — more forgiving for users.

### 4. Discriminator value uses simple (short) class name

**Reference**: Default `TypeNaming` is `FullName`:
- `sealed trait Foo; case class Bar extends Foo` → discriminator value: `Bar` (in the default package)
- Nested: `object TreeModule { sealed trait Node; case class Leaf extends Node }` → discriminator value: `TreeModule.Leaf` (full name with outer objects joined by `.`)
- Configurable via `MacroConfiguration(typeNaming = TypeNaming.SimpleName)` to get just the simple name.

**Ours**: Discriminator value is always the short class name (e.g., `Foo`, `Leaf`).

**Status**: **Deferred** (see task 8). Adding `TypeNaming` support requires `Class[_]` plumbing in the macro that doesn't fit cleanly with how we build the discriminator dispatch from `Enum.exhaustiveChildren`.

### 5. Field naming strategies

**Reference**: `FieldNaming.Identity`, `SnakeCase`, `PascalCase`, `KebabCase`, plus a `FieldNaming` function for custom.

**Ours**: We provide both a structured `FieldNaming` trait (`Identity`, `SnakeCase`, `PascalCase`, `KebabCase`, `Custom`) and the underlying `String => String` function via `BsonDocumentHandlerConfig.fieldNameMapper`. `withFieldNaming(naming)` and the existing `withSnakeCaseFieldNames` / `withKebabCaseFieldNames` / `withPascalCaseFieldNames` helpers are available.

**Status**: **Done** (see task 8).

### 6. `UnionType` / non-sealed ADTs

**Reference**: Supports `UnionType[UA \/ UB]` for non-sealed trait unions with `AutomaticMaterialization`.

**Ours**: Not supported. Sealed traits only.

**Status**: **Deferred** (see task 8). Large feature; would require the user to explicitly enumerate subtypes and tie them together via scalaz's `\/` (or similar) type.

### 7. `@DefaultValue` annotation (per-field default override)

**Reference**: Has `@DefaultValue("default")` annotation that allows specifying a default for fields that don't have a Scala-level default value. Requires `ReadDefaultValues` opt-in.

**Ours**: `@defaultValue` annotation supported. Per-field default override works without opt-in (since we always apply defaults, see limitation #1). Accepts a value of the field's type.

**Status**: **Done** (see task 8).

### 8. `@Key` / `@Reader` / `@Writer` per-field annotations

**Reference**: Has `@Key("custom_name")` to override BSON key for a single field, `@Reader` and `@Writer` to provide custom handlers per field.

**Ours**: We have `@fieldName` (equivalent to `@Key`), `@reader`, and `@writer` annotations. `@reader`/`@writer` accept a `BSONReader[T]`/`BSONWriter[T]` instance that overrides the derived handler for a specific field.

**Status**: **Done** (see task 8).

### 9. `AutomaticMaterialization`

**Reference**: `AutomaticMaterialization` option auto-derives handlers for sealed-trait members. Default is OFF (must be explicit) to avoid infinite recursion.

**Ours**: We always auto-materialize handlers for sealed-trait members (similar to jsoniter's default).

**Status**: Different default. Ours is more convenient.

### 10. `DisableWarnings` and `Verbose` options

**Reference**: Compile-time options for controlling macro output and warnings.

**Ours**: No equivalent. We use `Environment.reportInfo` / `Environment.reportErrorAndAbort` unconditionally.

**Status**: **Not applicable** (see task 8). The reference's `DisableWarnings` is off by default; ours matches. We can add a config option to suppress macro logs if it ever becomes noisy.

### 11. `@Flatten` annotation

**Reference**: `@Flatten` on a field of a case class type flattens the inner case class's fields into the parent document, rather than nesting it as a sub-document.

**Ours**: Not supported. Fields of case class types are always nested as sub-documents.

**Status**: Feature gap. Would require the case-class read/write path to know about the annotation and merge fields instead of nesting.

## Same Behavior

- **Default discriminator field name**: `"className"` (both)
- **Default field naming**: identity (both)
- **Option read**: missing field → `None` (both)
- **Sealed trait discrimination with discriminator field** (both)
- **Collection read/write** (both)
- **Value type (`AnyVal`) read/write** (both)
- **Map support** (both)
- **Empty case classes** (both)
- **Self-references / recursive types** (both)
- **Generic case classes** (both)

## Test Cases Worth Porting

The following reference tests cover edge cases we should also test:

1. **`Optional` field with `BSONNull` and missing** — covered (see `@noneAsNull - explicit BSONNull` test)
2. **Recursive structure** (e.g., `Tree`) — **SUPPORTED** (see task 7, `recursive structure (Tree)` test)
3. **Generic case class** (`GenSeq`) — not currently tested
4. **Empty case class** — already covered
5. **`@Key` / `@fieldName` annotation** — already covered
6. **Sealed family with case objects** — already covered
7. **Custom field naming** (SnakeCase, PascalCase) — covered
8. **Union types (ADT)** — partial coverage (sealed traits only; see limitation #6)
9. **Self-reference** — covered (recursive structure test)

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
