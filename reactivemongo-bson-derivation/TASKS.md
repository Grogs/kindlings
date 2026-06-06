# Tasks: reactivemongo-bson-derivation Improvements

## 📋 Current Session: Compare Against Reference Implementation

**Date**: 2026-06-06  
**Status**: Starting — see task 6 below

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
- [ ] Copy/adapt test cases from reference (deferred — many would fail due to limitations)
- [ ] Fix recursive structure limitation (requires setHelper refactor)
- [ ] Add `@NoneAsNull` annotation support (future feature)

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
