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

**Ours**: All Option fields are omitted on write for `None`. We don't have a `@NoneAsNull` annotation.

**Status**: Feature gap. Could be added by checking for the annotation and writing `BSONNull` instead.

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

**Status**: Intentional. We don't have a `TypeNaming` concept. The user can override with `@fieldName` if needed.

### 5. Field naming strategies

**Reference**: `FieldNaming.Identity`, `SnakeCase`, `PascalCase`, `KebabCase`, plus a `FieldNaming` function for custom.

**Ours**: User supplies a `String => String` function via `BsonDocumentHandlerConfig.fieldNameMapper`. We provide `withSnakeCaseFieldNames` and `withKebabCaseFieldNames` helpers. No `PascalCase` helper, no arbitrary `FieldNaming` trait.

**Status**: Our API is more flexible (any function) but less structured. Could add `withPascalCaseFieldNames` helper.

### 6. `UnionType` / non-sealed ADTs

**Reference**: Supports `UnionType[UA \/ UB]` for non-sealed trait unions with `AutomaticMaterialization`.

**Ours**: Not supported. Sealed traits only.

**Status**: Future work. Would require the user to explicitly enumerate subtypes.

### 7. `@DefaultValue` annotation (per-field default override)

**Reference**: Has `@DefaultValue("default")` annotation that allows specifying a default for fields that don't have a Scala-level default value. Requires `ReadDefaultValues` opt-in.

**Ours**: Only Scala-level default values (`name: String = "default"`) are supported. No `@DefaultValue` annotation.

**Status**: Feature gap. Low priority — Scala-level defaults cover most cases.

### 8. `@Key` / `@Reader` / `@Writer` per-field annotations

**Reference**: Has `@Key("custom_name")` to override BSON key for a single field, `@Reader` and `@Writer` to provide custom handlers per field.

**Ours**: We have `@fieldName` (equivalent to `@Key`). No `@Reader` or `@Writer` (user must derive a sub-handler manually and reference it via the standard `KindlingsBsonDocumentHandler` implicit).

**Status**: Partial. `@Reader` / `@Writer` would be a useful addition.

### 9. `AutomaticMaterialization`

**Reference**: `AutomaticMaterialization` option auto-derives handlers for sealed-trait members. Default is OFF (must be explicit) to avoid infinite recursion.

**Ours**: We always auto-materialize handlers for sealed-trait members (similar to jsoniter's default).

**Status**: Different default. Ours is more convenient.

### 10. `DisableWarnings` and `Verbose` options

**Reference**: Compile-time options for controlling macro output and warnings.

**Ours**: No equivalent. We use `Environment.reportInfo` / `Environment.reportErrorAndAbort` unconditionally.

**Status**: Low priority.

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

1. **`Optional` field with `BSONNull` and missing** — already partially covered
2. **Recursive structure** (e.g., `Tree`) — **NOT SUPPORTED** (see limitations below)
3. **Generic case class** (`GenSeq`) — not currently tested
4. **Empty case class** — already covered
5. **`@Key` / `@fieldName` annotation** — already covered
6. **Sealed family with case objects** — already covered
7. **Custom field naming** (SnakeCase, PascalCase) — snake_case already covered
8. **Union types (ADT)** — partial coverage
9. **Self-reference** — not currently tested

### Recursive Structure Limitation

Recursive types (e.g., `sealed trait Tree; case class Node(left: Tree, right: Tree) extends Tree`) **fail to compile** with our current implementation. The error is:
```
Cannot derive field reader/writer for Tree: No BSONReader found
```

**Root cause**: Our `setHelper` pattern eagerly evaluates the helper body, so recursive references to the cached handler don't find it (the helper is being built, not yet in the cache).

**Reference approach** (and jsoniter): `setHelper` takes a **function** `(value, writer, config) => ...` that is invoked LATER, after the helper is registered. Recursive references then find the cached handler.

**Fix**: Refactor `setHelper` to follow the jsoniter pattern (see `jsoniter-derivation/.../CodecMacrosImpl.scala` `deriveEncoderRecursively`). This is a non-trivial refactor.

## Reference Files

- `./ReactiveMongo-BSON/api/src/main/scala/MacroConfiguration.scala` — config defaults
- `./ReactiveMongo-BSON/api/src/main/scala/MacroOptions.scala` — compile-time options
- `./ReactiveMongo-BSON/api/src/main/scala/MacroAnnotations.scala` — annotations
- `./ReactiveMongo-BSON/api/src/main/scala-3/MacroImpl.scala` — Scala 3 macro
- `./ReactiveMongo-BSON/api/src/main/scala-2/MacroImpl.scala` — Scala 2 macro
- `./ReactiveMongo-BSON/api/src/test/scala/MacroSpec.scala` — comprehensive test suite
