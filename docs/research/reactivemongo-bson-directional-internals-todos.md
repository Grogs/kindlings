# ReactiveMongo BSON directional internals TODOs

This file tracks deliberate follow-up work introduced while implementing
`reactivemongo-bson-directional-internals-split-handoff.md`.

## Active implementation

- [x] Complete copied directional enum reader dispatch.
- [x] Complete copied directional enum writer dispatch.
- [x] Verify singleton sealed children independently.
- [x] Verify recursive sealed children use only their directional cache.

## Refactoring debt

- [ ] Extract immutable enum discriminator metadata shared by reader and writer builders.
- [ ] Move directional contexts and body builders out of `BsonDocumentHandlerMacrosImpl.scala`.
- [ ] Replace duplicated record planning with a direction-parameterized structural planner where it remains readable.
- [ ] Keep mutable `ValDefsCache` state strictly separate between directions.

## Remaining handoff scope

- [x] Standalone `@Flatten` reader and writer derivation.
- [x] Remaining annotation/config parity (`NoneAsNull`, defaults, unexpected fields, custom naming).
- [x] Directional collection/map readers and writers, including directional map-key codecs.
- [x] Combined handler composition from the two lower-level directional algebras.
  - Structural composition is selected only after standard extensions load, preserving the legacy path for `Option`,
    `Map`, collections, value types, named tuples, and explicit root handlers.
- [x] `parTuple` aggregation for independent combined failures.
- [ ] Recursive ADT, ambiguity, policy, and asymmetric compile-error coverage.
- [ ] User guide, feature parity, and research-document updates.
- [ ] Full JVM matrix, snippet tests, final code review, and cleanup.
