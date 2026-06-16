# Tasks: reactivemongo-bson-derivation Improvements

## 📋 Current Session: Feature parity with reference (annotations & helpers)

**Date**: 2026-06-07  
**Status**: In progress — see task 8 below

---

## High Priority

### 1. Factory instance pattern [DONE]
**Impact**: 2.5-3.2x faster instance creation, no per-type `.class` files
**Effort**: Easy

The macro currently uses `new KindlingsBsonDocumentHandler[A]` directly in 2 places:
- Line 319: Value type handler (in `HandleAsValueTypeRule`)
- Line 616: Option handler (in `HandleAsOptionRule`)

**Fix**: Replace with `BsonDocumentHandlerFactories.handlerInstance[A](readFn, writeFn)` factory calls

**Reference**: `kindlings-factory-instance` skill

**Status**:
- [x] Fix value type handler (line 319)
- [x] Fix Option handler (line 616)
- [x] Clean compile
- [x] Run tests
- [x] Verify no anonymous classes in bytecode

---

### 2. Config wiring [DONE]
**Impact**: Allow users to customize discriminator field name and field name mapping
**Effort**: Medium

Current state:
- `BsonDocumentHandlerConfig` exists with `discriminatorFieldName`, `fieldNameMapper`, `skipUnexpectedFields`
- Single `derived[A](using config)` entry point (no separate `derivedConfig`)
- Discriminator field name wired through `DerivationCtx` with compile-time + runtime fallback
- Default discriminator aligned with ReactiveMongo-BSON: `"className"`
- **semiEval intentionally disabled** (`evaluatedConfig = None`) — configs with function fields can't be evaluated at compile time. Runtime splice is the path.

**Remaining**:
1. Wire `fieldNameMapper` through field name resolution (currently uses `resolveFieldName`)
2. Wire `skipUnexpectedFields` through case class decoding (currently hardcoded to true)

**Reference**: circe-derivation `KindlingsDecoderCompanionCompat`

**Status**:
- [x] Add `derived` entry point with implicit config
- [x] Update macro signature to accept config
- [x] Wire discriminator field name from config (compile-time + runtime)
- [x] Align default discriminator with ReactiveMongo-BSON (`"className"`)
- [x] Extract magic strings/numbers to constants (`BsonDocumentHandlerConfig.defaultDiscriminatorFieldName`)
- [x] Add test for custom discriminator (passes)
- [x] Wire fieldNameMapper from config
- [x] Add test for fieldNameMapper (snake_case, passes)
- [x] Wire skipUnexpectedFields from config
- [x] Add test for skipUnexpectedFields=true and =false (both pass)

---

## Medium Priority

### 3. Runtime performance optimizations [PENDING]
**Impact**: Bring performance in line with jsoniter/circe
**Effort**: Hard

Apply patterns from `kindlings-runtime-perf` skill:

1. **`semiEval` for config**: Eliminate runtime `config.fieldNameMapper(name)` calls
2. **Typed local variables**: Use `ValDefs.createVar` instead of `Array[Any]` boxing
3. **Sentinel loop pattern**: Avoid dispatch duplication in collection loops
4. **Inline built-in encoding/decoding**: Direct `reader.readInt()` / `writer.writeVal()` instead of def indirection
5. **Inline collection loops**: Direct `while` loop instead of runtime helpers
6. **Position-based record access**: `record.put(index, value)` instead of string-keyed lookups
7. **`writeNonEscapedAsciiKey`**: For known ASCII field names

**Reference**: `docs/research/jsoniter-codegen-techniques.md`, `docs/research/perf-regression-analysis.md`

**Status**:
- [ ] Apply semiEval to config evaluation
- [ ] Add typed vars for primitives
- [ ] Optimize case class decoding with position-based access
- [ ] Inline built-in type encoding/decoding
- [ ] Inline collection loops
- [ ] Benchmark before/after

---

## Low Priority

### 4. Scala 2.13 cross-compilation [PENDING]
**Impact**: Support Scala 2.13 projects
**Effort**: Medium

Current state: Scala 3 only

**Fix**:
1. Create `AnnotationSupportScala2` in `scala-2/` directory
2. Use cross-platform `Expr.quote`/`splice` patterns
3. Update `build.sbt` to include Scala 2.13 JVM target
4. Add Scala 2 test cases

**Note**: `reactivemongo-bson-api` is JVM-only, so Scala.js/Native not applicable

**Status**:
- [ ] Create Scala 2 AnnotationSupport
- [ ] Fix any Scala 2-specific macro issues
- [ ] Update build.sbt
- [ ] Add Scala 2 tests
- [ ] Verify cross-compilation works

---

### 5. Documentation [DONE]
**Impact**: Help users understand and use the module
**Effort**: Low

Created `docs/user-guide/reactivemongo-bson-derivation.md` with:
- Installation
- Quick start
- Supported types table
- Configuration (all three config fields with examples)
- `@fieldName` annotation
- Examples (sealed trait, collections/options, default values)
- Limitations

Added to `docs/mkdocs.yml` nav.

**Status**:
- [x] Create `docs/user-guide/reactivemongo-bson-derivation.md`
- [x] Add overview and quick start
- [x] Document configuration options
- [x] Add examples
- [x] Document limitations
- [x] Add to mkdocs.yml navigation

---

### 6. Compare against reference implementation [DONE]
**Impact**: Documented 10 intentional behavioral differences and identified a recursive-structure limitation
**Effort**: Medium

**Deliverable**: `reactivemongo-bson-derivation/REFERENCE-COMPARISON.md`

**Key findings**:

1. **Default values are always applied** (not opt-in like reference's `ReadDefaultValues`)
2. **`@NoneAsNull` annotation not supported** (feature gap)
3. **`BSONNull` always decoded as `None`** (more permissive than reference)
4. **Discriminator uses short class name** (reference uses full name by default)
5. **Field naming is `String => String` function** (reference uses structured `FieldNaming` trait)
6. **No `UnionType` / non-sealed ADT support** (sealed traits only)
7. **No `@DefaultValue` annotation** (only Scala-level defaults)
8. **No `@Reader`/`@Writer` per-field annotations** (only `@fieldName`)
9. **`AutomaticMaterialization` always on** (reference requires opt-in)
10. **No `DisableWarnings`/`Verbose` options**

**Same behavior**: default discriminator `"className"`, default identity field naming, options read, sealed trait discrimination, collections, value types, maps, empty case classes.

**Identified limitation**: Recursive types (e.g., `Tree`) **do not compile** with the current `setHelper` pattern. Reference uses a function-based approach (also used by jsoniter) to break the recursive cycle. Fix requires refactoring `setHelper` to follow the jsoniter pattern.

**Status**:
- [x] Extract and review ReactiveMongo-BSON macro source
- [x] Identify key behavioral differences
- [x] Document intentional differences in `REFERENCE-COMPARISON.md`
- [x] Copy/adapt test cases from reference (see task 8) — Seq[String], single-member case class, @defaultValue, @reader, @writer, @noneAsNull
- [x] Fix recursive structure limitation (requires setHelper refactor) — see task 7
- [x] Add `@NoneAsNull` annotation support — see task 8

---

### 7. Recursive type derivation [DONE]
**Impact**: Support recursive ADTs (e.g., `sealed trait Tree; case class Node(left: Tree, right: Tree)`)
**Effort**: Medium

**Problem**: The original `setHelper` evaluated the helper MIO immediately, so for a recursive type the inner summon of the same type failed because the helper was not yet registered.

**Fix** (in `BsonDocumentHandlerMacrosImpl.scala`):

1. **`setHelper` refactor** (line 308): Replaced eager `defBuilder.traverse(_ => helper)` + `buildCached` with `cache.buildCachedWith` wrapped in `MIO.scoped { runSafe => ... }`. This stores the helper MIO without evaluating it, so the body is evaluated later (when the def is emitted) — allowing recursive types to find the cached helper before its body is built.

2. **`deriveResultRecursively` always uses helper** (line 359): Removed the `tryInlineLeafType` shortcut in the non-leaf path; all non-leaf types now go through `setHelper`/`getHelper`.

3. **Trait-level helpers** (line 232): Moved `isCaseClassOrEnum`, `resolveBsonReader`, `resolveBsonWriter` to the trait level so they can be shared between `HandleAsCaseClassRule` and `HandleAsCollectionRule`.

4. **Removed `HandleAsBuiltInRule`**: `KindlingsBsonDocumentHandler[A]` extends `BSONDocumentHandler[A]` whose `readTry` is final and only accepts `BSONDocument`. A document handler cannot serve value-level reads inside collections (e.g., reading a `BSONString` for a `List[String]` element). Primitive/scalar types are now handled via direct `BSONReader`/`BSONWriter` summoning in the collection/map handlers.

5. **`deriveCollectionHandler` dual-path** (line 535): Uses `resolveBsonReader`/`resolveBsonWriter` so case classes/enums are derived recursively and other types (including primitives) summon their `BSONReader`/`BSONWriter` directly.

6. **`deriveMapHandler` dual-path** (line 618): Same pattern for map values.

7. **Test added**: `recursive structure (Tree)` in `BsonDocumentHandlerSpec.scala`.

**Result**: All 27 tests pass, including `List[String]`, `Set[Int]`, `Map[String, Int]`, and `Tree`.

**Status**:
- [x] Refactor setHelper to use buildCachedWith
- [x] Move helpers to trait level
- [x] Remove HandleAsBuiltInRule
- [x] Update deriveCollectionHandler to dual-path
- [x] Update deriveMapHandler to dual-path
- [x] Add Tree recursive test
- [x] All 27 tests pass

---

### 8. Feature parity with reference (annotations & helpers) [IN PROGRESS]
**Impact**: Closer feature parity with ReactiveMongo-BSON's macro; users can switch with less friction
**Effort**: Medium

Reference implementation supports several annotations and config options that ours doesn't (see `REFERENCE-COMPARISON.md` for the full list). Tackled in order of user value, smallest first.

**Status**:
- [x] Limitation #2: `@noneAsNull` annotation — `None` writes as `BSONNull`
- [x] Limitation #5: structured `FieldNaming` trait + helpers (SnakeCase, PascalCase, KebabCase, Custom)
- [x] Limitation #7: `@defaultValue` annotation — per-field default override
- [x] Limitation #8: `@reader` / `@writer` annotations — per-field custom handlers
- [x] Limitation #11: `@flatten` annotation — merge inner case class fields into parent document
- [x] Port reference tests: `Seq[String]`, single-member case class

**Deferred limitations** (with reason for deferral):

- [ ] **Limitation #4: `TypeNaming` (full vs short class name discriminator)** — Requires `Class[_]` plumbing in the macro. The reference exposes `TypeNaming` as a `Class[_] => String` function, but in our macro the discriminator dispatch is built at compile time from `Enum.exhaustiveChildren` (whose case names are already the simple class names), and Hearth's `Type[X]` for the existential types from `child.Underlying` doesn't give a direct `Class` handle (and `classOf[CT]` is rejected at macro compile time because the alias is an existential, not a class type). To fully support this we'd need either (a) a way to lift Hearth's `Type[X]` to a `Class[_]` at runtime, or (b) a sealed-trait `TypeNaming` with compile-time-known variants (`SimpleName`, `FullName`) and a custom-function escape hatch for the rest. Effort: medium-hard.
- [x] **Limitation #5 (rest): structured `FieldNaming` trait** — Done. Added `FieldNaming` sealed trait with `Identity`, `SnakeCase`, `PascalCase`, `KebabCase`, and `Custom` variants. `BsonDocumentHandlerConfig` still stores a `String => String` internally for backward compatibility; `withFieldNaming(naming)` converts to it.
- [ ] **Limitation #6: `UnionType` for non-sealed ADTs** — Large feature. The reference supports `UnionType[UA \/ UB]` (scalaz `\/` either) for non-sealed trait unions with `AutomaticMaterialization`. Requires the user to explicitly enumerate subtypes and tie them together via the `\/` type. Effort: large. Likely not worth it unless users ask for it.
- [x] **Limitation #11: `@Flatten` annotation** — Done. Added `@flatten` annotation. A flattened field is read/written by deriving a handler for the inner type and applying it directly to the parent document. Nested flattening works recursively. Caveat: conflicting inner field names are not detected at compile time.
- [ ] **Limitation #10: `DisableWarnings` / `Verbose` options** — Not applicable. We use `Environment.reportInfo` / `Environment.reportErrorAndAbort` unconditionally (the same as the reference's default). If we ever need to suppress noisy macro logs, we can add a config option.

**Test count**: 40 tests passing (was 27 before this task).

---

## Completed

### ✅ Dead code removal
Deleted 6 unused runtime helpers from `BsonDocumentHandlerFactories`:
- `readField`
- `readOptionField`
- `readFieldWithDefault`
- `writeField`
- `writeOptionField`
- `sequenceOptionTries`

### ✅ Collection/map handler fixes
- Collection handler now uses `isCollection.factory` + `isCollection.build` with full `CtorLikeOf` support
- Map handler now uses `isMap.Key`, `isMap.Value`, `isMap.pair`, `isMap.factory`, `isMap.build`
- Added `collectBuildResult` helper for all 5 `CtorLikeOf` variants
- Added `Map[String, Int]` test
- **22 tests passing**

### ✅ Recursive type derivation (Task 7)
- `setHelper` refactored to use `buildCachedWith` + `MIO.scoped` (jsoniter pattern)
- Trait-level `isCaseClassOrEnum`, `resolveBsonReader`, `resolveBsonWriter` shared between rules
- `HandleAsBuiltInRule` removed (architecturally wrong: `BSONDocumentHandler.readTry` is final and document-only)
- `deriveCollectionHandler` and `deriveMapHandler` use dual-path resolution
- Added `Tree` recursive test
- **27 tests passing**

### ✅ Feature parity with reference (Task 8 partial)
- `@noneAsNull` annotation (REFERENCE-COMPARISON #2)
- `FieldNaming` structured trait + helpers (REFERENCE-COMPARISON #5)
- `@defaultValue` annotation (REFERENCE-COMPARISON #7)
- `@reader` / `@writer` annotations (REFERENCE-COMPARISON #8)
- `@flatten` annotation (REFERENCE-COMPARISON #11)
- Ported `Seq[String]` and single-member case class tests
- **40 tests passing** (was 27)
