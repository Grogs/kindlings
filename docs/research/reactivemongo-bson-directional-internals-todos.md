# ReactiveMongo BSON directional internals TODOs

This file tracks deliberate follow-up work introduced while implementing
`reactivemongo-bson-directional-internals-split-handoff.md`.

## Active implementation

- [x] Complete copied directional enum reader dispatch.
- [x] Complete copied directional enum writer dispatch.
- [x] Verify singleton sealed children independently.
- [x] Verify recursive sealed children use only their directional cache.

## Refactoring debt

- [x] Extract immutable enum discriminator metadata shared by reader and writer builders.
- [x] Move directional contexts and body builders out of `BsonDocumentHandlerMacrosImpl.scala`.
- [x] Replace duplicated record planning with a direction-parameterized structural planner where it remains readable.
- [x] Keep mutable `ValDefsCache` state strictly separate between directions.

## Remaining handoff scope

- [x] Standalone `@Flatten` reader and writer derivation.
- [x] Remaining annotation/config parity (`NoneAsNull`, defaults, unexpected fields, custom naming).
- [x] Directional collection/map readers and writers, including directional map-key codecs.
- [x] Combined handler composition from the two lower-level directional algebras.
  - Structural composition is selected only after standard extensions load, preserving the legacy path for `Option`,
    `Map`, collections, value types, named tuples, and explicit root handlers.
- [x] `parTuple` aggregation for independent combined failures.
- [x] Recursive ADT, policy, and asymmetric compile-error coverage.
- [ ] Ambiguous implicit compile-error coverage.
  - Scala 2 reports an ambiguous `BSONReader` directly while `MacroSuite.compileErrors` is typechecking its nested
    source, before the suite can capture the diagnostic. Keep any eventual regression shared; do not move it to a
    Scala-3-only source set.
  - Minimal shared reproducer: `reactivemongo-bson-ambiguous-implicit-macrosuite-reproducer.md`.
- [x] User guide, feature parity, and research-document updates.
- [x] Full JVM matrix, snippet tests, final code review, and cleanup.
