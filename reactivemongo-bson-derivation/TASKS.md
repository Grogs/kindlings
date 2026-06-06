# Tasks: reactivemongo-bson-derivation Improvements

## 📋 Handoff: Config Wiring (Completed)

**Date**: 2026-06-06  
**Status**: ✅ **DONE** — 23/23 tests pass

### What Was Done

1. **Wired `BsonDocumentHandlerConfig` through `DerivationCtx`** with both `config: Expr[BsonDocumentHandlerConfig]` (for runtime splice) and `evaluatedConfig: Option[BsonDocumentHandlerConfig]` (for compile-time optimization)
2. **Fixed root cause of NPE**: Scala companion object initialization order bug. `implicit val default` was defined **before** `val defaultDiscriminatorFieldName`, causing the default config to be initialized with `null` for `discriminatorFieldName`. Fix: moved `defaultDiscriminatorFieldName` before `default`.
3. **Enum handler uses both paths**: compile-time constant when `evaluatedConfig` has the discriminator, runtime splice with local val capture as fallback
4. **23/23 tests pass** including the custom discriminator test

### Root Cause (for future reference)

The `NullPointerException` at `BsonDocumentHandlerConfig.discriminatorFieldName()` was **not** a semiEval issue. It was a case class field initialization order bug in the companion object:

```scala
// BEFORE (broken)
implicit val default: BsonDocumentHandlerConfig = BsonDocumentHandlerConfig()
val defaultDiscriminatorFieldName: Option[String] = Some("className")

// AFTER (fixed)
val defaultDiscriminatorFieldName: Option[String] = Some("className")
implicit val default: BsonDocumentHandlerConfig = BsonDocumentHandlerConfig()
```

When `default` was evaluated first, `defaultDiscriminatorFieldName` was `null`, so the case class default value was `null` instead of `Some("className")`. At runtime, `.discriminatorFieldName()` returned `null` (Java-level null, not `None`).

Debug technique: a `System.err.println` at the splice point revealed `discriminatorFieldName=null` (not `None`, not `Some("className")`).

### Files Modified (This Session)

- `BsonDocumentHandlerConfig.scala`: Reordered companion object so `defaultDiscriminatorFieldName` comes before `default`
- `BsonDocumentHandlerMacrosImpl.scala`:
  - Added `config` and `evaluatedConfig` to `DerivationCtx`
  - Updated `DerivationCtx.from` and `DerivationCtx.nest` to thread both fields
  - Updated `deriveTypeClass` to accept and pass `configExpr`
  - Enum handler uses `evaluatedConfig` for compile-time constant, runtime splice as fallback
- Committed: `7ce7d59` "fix: wire BsonDocumentHandlerConfig discriminator field name through DerivationCtx"

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
- [ ] Wire fieldNameMapper from config
- [ ] Wire skipUnexpectedFields from config
- [ ] Add test for fieldNameMapper
- [ ] Add test for skipUnexpectedFields

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

### 5. Documentation [PENDING]
**Impact**: Help users understand and use the module
**Effort**: Low

Create user guide documentation:

1. **Overview**: What types are supported (case classes, sealed traits, collections, maps, value types, options)
2. **Quick start**: Basic usage with `KindlingsBsonDocumentHandler.derived[A]`
3. **Configuration**: Using `BsonDocumentHandlerConfig` for custom discriminator names, field name mapping, skipUnexpectedFields
4. **Annotations**: Using `@fieldName` for field name overrides
5. **Examples**: Common patterns (nested types, collections, enums)
6. **Limitations**: What's not supported yet
7. **Migration**: Differences from reactivemongo's built-in derivation

**Status**:
- [ ] Create `docs/user-guide/reactivemongo-bson.md`
- [ ] Add overview and quick start
- [ ] Document configuration options
- [ ] Add examples
- [ ] Document limitations
- [ ] Add to mkdocs.yml navigation

---

### 6. Compare against reference implementation [PENDING]
**Impact**: Ensure behavioral parity with ReactiveMongo-BSON's macro derivation
**Effort**: Medium

ReactiveMongo-BSON provides a reference implementation of macro-based BSON handler derivation. We should compare our implementation against it to ensure we match behavior, edge cases, and defaults.

**Steps**:
1. Extract and review ReactiveMongo-BSON's macro derivation source (from `./ReactiveMongo-BSON/` local copy)
2. Identify test cases from ReactiveMongo-BSON's test suite
3. Compare:
   - Default discriminator field name (should be `"className"`)
   - Field naming strategies (`FieldNaming` options)
   - Type naming strategies (short name vs full name)
   - Option handling (None encoding/decoding)
   - Sealed trait/enum encoding (discriminator vs wrapper style)
   - Nested type handling
   - Error messages and edge cases
4. Copy/adapt relevant test cases from ReactiveMongo-BSON into our test suite
5. Document any intentional behavioral differences

**Reference**: `./ReactiveMongo-BSON/api/src/main/scala-2/MacroImpl.scala`, `./ReactiveMongo-BSON/api/src/main/scala-2/MacroConfiguration.scala`

**Status**:
- [ ] Extract and review ReactiveMongo-BSON macro source
- [ ] Identify key behavioral differences
- [ ] Copy/adapt test cases from reference
- [ ] Fix any behavioral mismatches
- [ ] Document intentional differences

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
