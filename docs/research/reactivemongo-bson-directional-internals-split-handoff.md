# Handoff: split ReactiveMongo BSON derivation into independent directional internals

## Objective

Implement genuine independent derivation for:

```scala
KindlingsBsonDocumentReader.derived[A]
KindlingsBsonDocumentWriter.derived[A]
KindlingsBsonDocumentHandler.derived[A]
```

The reader must never derive, summon, validate, cache, or generate the write path. The writer must never derive, summon,
validate, cache, or generate the read path. The combined handler must compose the two lower-level directional
implementations and aggregate independent failures.

This document supplements `docs/research/reactivemongo-bson-standalone-reader-writer-handoff.md`, which contains the
full feature requirements, test matrix, documentation requirements, and scope boundaries.

## Current branch state

Branch: `reactivemongo-bson-derivation`

Important commits:

- `7206415` — reverted the earlier mode-based implementation completely.
- `1e8f13c` — added only the safe public/runtime foundations described below.

At `1e8f13c`, all existing tests pass:

- Scala 2.13: 90 tests
- Scala 3: 94 tests

No standalone macro entry points exist yet.

### Foundations already present

Public traits:

```scala
trait KindlingsBsonDocumentReader[A] extends BSONDocumentReader[A]
trait KindlingsBsonDocumentWriter[A] extends BSONDocumentWriter[A]
```

The combined trait now has this hierarchy:

```scala
trait KindlingsBsonDocumentHandler[A]
    extends BSONDocumentHandler[A]
    with KindlingsBsonDocumentReader[A]
    with KindlingsBsonDocumentWriter[A]
```

`internal/runtime/BsonDocumentHandlerFactories.scala` contains:

```scala
def readerInstance[A](readFn: BSONDocument => Try[A]): KindlingsBsonDocumentReader[A]
def writerInstance[A](writeFn: A => Try[BSONDocument]): KindlingsBsonDocumentWriter[A]
def handlerInstance[A](readFn: BSONDocument => Try[A], writeFn: A => Try[BSONDocument]): KindlingsBsonDocumentHandler[A]
```

### Explicitly rejected implementation

An earlier implementation threaded `readOnly` and `writeOnly` booleans through the existing combined context, kept all
rules typed as `Expr[KindlingsBsonDocumentHandler[A]]`, and installed failing inactive methods internally. It was
behaviorally promising but did not satisfy the architecture or the no-placeholder requirement. It was reverted by
`7206415`.

Do not restore that approach. In particular:

- no `readOnly` / `writeOnly` booleans;
- no `UnsupportedOperationException("inactive reader/writer")` methods;
- no standalone API implemented by deriving a combined handler and narrowing/wrapping it;
- no shared mutable `ValDefsCache` between parallel read and write derivation.

## Agreed design

Use a **generic directional rule algebra**, not wholesale duplication of every structural rule.

Required properties:

1. Reader and writer have distinct contexts and `ValDefsCache` instances.
2. Reader and writer rule results have direction-specific types.
3. Shared code is limited to immutable structural metadata and direction-neutral utilities.
4. Standalone entry points invoke only their directional algebra.
5. Combined derivation invokes both lower-level directional algebras directly, using separate caches.
6. Combined derivation uses `parTuple` to aggregate independent errors.
7. Policy enforcement occurs once per public expansion, before structural branches, while user-provided/cached instances
   retain precedence.
8. `cache.toValDefs.use` wraps the complete final instance expression.
9. `LambdaBuilder` remains limited to collection/optional iteration callbacks.

### Suggested core types

Exact names may change, but the implementation should converge on shapes like:

```scala
final case class ReaderCtx[A](
    tpe: Type[A],
    cache: MLocal[ValDefsCache],
    derivedType: Option[??],
    config: Expr[BsonDocumentHandlerConfig],
    evaluatedConfig: Option[BsonDocumentHandlerConfig],
    flattenStack: List[String]
)

final case class WriterCtx[A](
    tpe: Type[A],
    cache: MLocal[ValDefsCache],
    derivedType: Option[??],
    config: Expr[BsonDocumentHandlerConfig],
    evaluatedConfig: Option[BsonDocumentHandlerConfig],
    flattenStack: List[String]
)

final case class ReaderBody[A](run: Expr[BSONDocument] => Expr[Try[A]])
final case class WriterBody[A](run: Expr[A] => Expr[Try[BSONDocument]])
```

Prefer named cached helper calls over storing reusable expressions across sibling Scala 3 splices. Follow
`docs/contributing/hearth-def-caching/SKILL.md` exactly.

A shared algebra may abstract over direction, but its abstraction must not reintroduce optional opposite-direction
expressions. If generic types become harder to verify than two small directional wrappers around shared metadata,
prefer the wrappers.

## Existing code to refactor

The current combined implementation is split across:

- `internal/compiletime/BsonDocumentHandlerMacrosImpl.scala`
- `internal/compiletime/BsonCodecResolution.scala`
- `internal/compiletime/BsonCaseClassDerivation.scala`
- `internal/compiletime/BsonCollectionDerivation.scala`
- `internal/compiletime/BsonEnumDerivation.scala`

Current load-bearing behavior includes:

- recursive helper forward declaration;
- cached read/write bodies;
- field defaults and typed local vars;
- annotations (`Reader`, `Writer`, `Flatten`, `Ignore`, `DefaultValue`, `NoneAsNull`, `FieldName`);
- map `KeyReader` / `KeyWriter` resolution;
- recursive sealed ADTs;
- named tuples before value types;
- maps before collections;
- inline write derivation;
- policy and timeout integration;
- user-provided handler precedence;
- Scala 3 self-reference protection for `derives`.

Preserve these behaviors while moving them into directional paths.

## Recommended implementation sequence

Work in vertical slices and commit each green slice.

### 1. Reader-only case-class tracer bullet

Add public companion APIs and cross-version macro bridges for the reader only. Add a shared test where a nested custom
type has only `BSONReader`, with deliberately no writer.

Implement:

- `ReaderCtx` and reader cache keys;
- reader implicit precedence;
- reader case-class body derivation;
- `readerInstance` final construction;
- policy and timeout integration.

Do not add a writer placeholder to make this compile.

### 2. Writer-only case-class tracer bullet

Mirror the minimal directional infrastructure for writer derivation. Test a nested type with only `BSONWriter`.
Preserve the existing inline-write behavior, ideally by routing it through the same lower-level writer body derivation.

### 3. Combined handler composition

Replace combined case-class derivation with direct composition of reader and writer bodies:

- separate caches;
- `parTuple` for independent errors;
- one `handlerInstance` factory call;
- both cache snapshots emitted around the complete final instance.

Add a compile-error test where reader and writer fail for different fields and both diagnostics are present.

### 4. Remaining structural rules

Port in this order:

1. `Option`
2. value/opaque types (guard named tuples first)
3. named tuples
4. collections
5. maps (`KeyReader` only vs `KeyWriter` only)
6. case-class annotations and flattening
7. sealed and recursive ADTs

For every slice, add one asymmetric test before implementation.

### 5. Implicit precedence and recursion

Test and implement:

- root `BSONDocumentReader[A]` override;
- root `BSONDocumentWriter[A]` override;
- nested `BSONReader[A]` / `BSONWriter[A]` override;
- recursive reader-only ADTs;
- recursive writer-only ADTs;
- Scala 3 `derives` without self-initialization recursion;
- all three companion auto-derivation methods in `summonExprIgnoring` lists.

### 6. Policy and documentation

Add policy integration coverage for reader, writer, and handler on Scala 2.13 and Scala 3. Then update:

- `docs/user-guide/reactivemongo-bson-derivation.md`
- `docs/user-guide/feature-parity.md`
- `docs/research/reactivemongo-bson-derivation-reference-advantages.md`

Do not restore documentation from reverted commit `4ce4358` blindly; update it only after the genuine split passes.

## Test seams already agreed with the user

1. `KindlingsBsonDocumentReader.derived[A]`
   - requires only read capabilities;
   - assignable to upstream `BSONDocumentReader[A]`.
2. `KindlingsBsonDocumentWriter.derived[A]`
   - requires only write capabilities;
   - assignable to upstream `BSONDocumentWriter[A]`.
3. `KindlingsBsonDocumentHandler.derived[A]`
   - requires and aggregates both directions.
4. Direction-specific maps, annotations, flattening, recursive ADTs, policy, and user overrides.
5. Scala 3 `derives` for both standalone types.
6. Ambiguity regression coverage where combined and directional values are reachable.

## Verification commands

Metals MCP is unavailable in the current harness; the user explicitly authorized SBT as the feedback loop for this
work. Always use `sbt --client` and redirect output.

Focused module verification:

```bash
sbt --client \
  "reactivemongoBsonDerivation2_13/clean ; reactivemongoBsonDerivation/clean ; reactivemongoBsonDerivation2_13/test ; reactivemongoBsonDerivation/test" \
  2>&1 | tee /tmp/sbt-output.txt
```

Policy tests:

```bash
sbt --client \
  "reactivemongoBsonDerivationPolicyTests2_13/clean ; reactivemongoBsonDerivationPolicyTests/clean ; reactivemongoBsonDerivationPolicyTests2_13/test ; reactivemongoBsonDerivationPolicyTests/test" \
  2>&1 | tee /tmp/sbt-policy-output.txt
```

Final checks:

```bash
sbt --client "test-jvm-2_13 ; test-jvm-3" 2>&1 | tee /tmp/sbt-output.txt
cd docs && TMPDIR=/tmp just test-snippets
cd .. && git diff --check
```

ReactiveMongo BSON is JVM-only; JS and Native do not apply.

The previous documentation-snippet attempt published modules successfully but timed out while Scala CLI downloaded
Bloop dependencies. A later attempt should benefit from the populated cache.

## Compile-time performance follow-up

Correctness comes first. After the split is green, measure rather than speculate about:

- repeated immutable structural parsing in combined derivation;
- separate cache traversal and helper counts;
- `parTuple` wall-clock behavior;
- generated tree size;
- compilation time for representative nested and recursive models.

Do not merge reader/writer mutable caches merely to optimize compile time. If metadata parsing is material, share only
immutable parsed metadata or memoized direction-neutral analysis.

## Working-tree caution

The following paths were untracked before this work and must not be modified accidentally:

- `.pi/`
- `ReactiveMongo-BSON/`
- `docs/research/reactivemongo-bson-standalone-reader-writer-handoff.md`

The new handoff file itself is also untracked until committed.

When the worktree is finished, suggest:

```bash
sbt --client shutdown
```
