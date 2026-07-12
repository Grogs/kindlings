# ReactiveMongo BSON derivation: reference-comparison claims

Research date: 2026-07-11. Reference source: ReactiveMongo/ReactiveMongo-BSON commit
[`0447b636b749600e7a35a986c98a46c8a307b017`](https://github.com/ReactiveMongo/ReactiveMongo-BSON/tree/0447b636b749600e7a35a986c98a46c8a307b017).

## Claims safe to document

### Sealed hierarchies need less setup

Kindlings derives supported sealed hierarchies directly:

```scala
val shapeHandler = KindlingsBsonDocumentHandler.derived[Shape]
```

The user guide demonstrates that API in the `Sealed traits and collections` section. The implementation tests cover
case-object and case-class variants, including recursive ADTs.

The reference implementation supports sealed families, but its default approach requires handlers for every member.
Its `AutomaticMaterialization` option can materialize member handlers, but is deliberately not the default because the
reference warns that it can lead to recursive-type problems
([source](https://github.com/ReactiveMongo/ReactiveMongo-BSON/blob/0447b636b749600e7a35a986c98a46c8a307b017/api/src/main/scala/MacroOptions.scala#L99-L121)).
Its own recursive-tree test additionally states that handlers must be explicitly defined and type-annotated because of
compiler limitations, and constructs the family handler through `UnionType[Node \/ Leaf]`
([source](https://github.com/ReactiveMongo/ReactiveMongo-BSON/blob/0447b636b749600e7a35a986c98a46c8a307b017/api/src/test/scala/MacroTest.scala#L141-L186)).

**Suggested wording:** “Derive supported sealed hierarchies, including recursive ADTs, from one handler declaration;
no per-subtype handlers or `MacroOptions.UnionType` plumbing is required.”

Do not claim that the reference cannot derive sealed traits: it can, with the described configuration/boilerplate.

### Scala 3 `derives` syntax

Kindlings exposes a distinct subtype, `KindlingsBsonDocumentHandler[A]`, and a Scala 3 `derived` given. Consequently:

```scala
case class Person(name: String) derives KindlingsBsonDocumentHandler
```

works and produces a value usable wherever ReactiveMongo expects a `BSONDocumentHandler[A]`. The reference exposes
macro entry points such as `Macros.handler[A]` and `Macros.handlerOpts[A, Opts]`
([source](https://github.com/ReactiveMongo/ReactiveMongo-BSON/blob/0447b636b749600e7a35a986c98a46c8a307b017/api/src/main/scala-3/Macros.scala#L220-L255)),
not a derivable type-class companion.

**Suggested wording:** “Scala 3 supports `derives KindlingsBsonDocumentHandler`, while retaining
`BSONDocumentHandler` interoperability.”

### Write-only inline serialization

Kindlings provides:

```scala
KindlingsBsonDocumentHandler.write(value)
```

This derives/emits only the BSON-document write path and does not allocate a final handler instance. The reference API
provides `Macros.handler`/`writer` macro entry points, so applications normally materialize a handler/writer before
writing.

**Suggested wording:** “For one-off serialization, `KindlingsBsonDocumentHandler.write(value)` emits only the write
path rather than creating a document handler.”

Avoid stronger performance comparisons (for example, “faster”) without a benchmark against the same ReactiveMongo
version and workload.

### Standalone reader and writer derivation

Kindlings now exposes independent document-codec entry points:

```scala
KindlingsBsonDocumentReader.derived[A]
KindlingsBsonDocumentWriter.derived[A]
```

They are assignable to ReactiveMongo's `BSONDocumentReader[A]` and `BSONDocumentWriter[A]`, respectively. The reader
path does not summon or validate writers, and the writer path does not summon or validate readers. A combined handler
composes both directional derivations and aggregates independent diagnostics.

This is feature parity with the reference's `Macros.reader` and `Macros.writer`, rather than a performance claim. The
useful Kindlings-specific guarantee to document is directional independence: a read-only model can use a nested
`BSONReader` with no corresponding writer, and conversely for a write-only model.

## Named tuples: a verified Kindlings advantage

Kindlings has Scala 3 regression tests for single- and multi-element named tuples. A temporary, isolated checkout of
the reference project at the recorded commit was tested with:

```scala
val handler = Macros.handler[(name: String, age: Int)]
val value: (name: String, age: Int) = ("Alice", 42)
```

against the reference `api` test project. Its current Scala 3.4.3 build fails before macro expansion because that
compiler predates named tuples. The reference build pins its sole Scala 3 cross-version to 3.4.3
([source](https://github.com/ReactiveMongo/ReactiveMongo-BSON/blob/0447b636b749600e7a35a986c98a46c8a307b017/compiler.sbt#L1-L10)).

To distinguish a version issue from a macro limitation, the same isolated checkout was then built under Scala 3.8.3
with only temporary build-compatibility changes (removing the obsolete Java-8 release flag and fatal warnings). The
reference macro then failed on `Macros.handler[(name: String, age: Int)]` with:

```text
Instance not found: Conversion[scala.NamedTuple.NamedTuple[…], _ <: Product]
```

The macro requires a `Conversion[A, Product]` for the named tuple, which Scala does not provide. Thus the reference
would require macro/library changes beyond a Scala version upgrade.

**Suggested wording:** “Scala 3 named tuples, including one-element tuples, are supported; ReactiveMongo-BSON does not
derive BSON handlers for named tuples.”

## Further feature comparison

### Inline `AnyVal` fields: a verified Kindlings advantage

The reference explicitly supports value classes through separate `Macros.valueReader`, `valueWriter`, and
`valueHandler` entry points
([source](https://github.com/ReactiveMongo/ReactiveMongo-BSON/blob/0447b636b749600e7a35a986c98a46c8a307b017/api/src/main/scala-3/Macros.scala#L70-L110)).
That is not equivalent to Kindlings' recursive handling of an `AnyVal` field while deriving its enclosing document.

A reference-project probe declared:

```scala
final class Id(val value: Int) extends AnyVal
final case class Holder(id: Id)
val handler = Macros.handler[Holder]
```

It failed with `No implicit found for 'Holder.id': BSONWriter[Id]`. A separately declared value handler is required.
Kindlings' existing `WithValueType(WrapperId(42), "test")` regression test derives only the enclosing handler and emits
`BSONDocument("id" -> 42, "name" -> "test")`.

**Suggested wording:** “Value-class fields are unwrapped automatically during enclosing document derivation; no
separate `Macros.valueHandler` declaration is needed.”

The same setup advantage is verified for Scala 3 opaque aliases. The reference exposes dedicated
`Macros.valueReader`, `valueWriter`, and `valueHandler` APIs for opaque aliases. Kindlings' Scala 3 regression test
instead derives only a parent `User(id: UserId, name: String)` handler and verifies that opaque `UserId = Int` is
encoded directly as `"id" -> 42` and reconstructed on read.

### Defaults: a smaller-configuration Kindlings advantage

Both implementations support field-level default annotations. Both can also read Scala constructor defaults, but the
reference requires opting into `MacroOptions.ReadDefaultValues`; its own tests invoke `.using[ReadDefaultValues]` or
provide an appropriately typed `MacroConfiguration` before a missing field uses a constructor default
([source](https://github.com/ReactiveMongo/ReactiveMongo-BSON/blob/0447b636b749600e7a35a986c98a46c8a307b017/api/src/test/scala/MacroSpec.scala#L1088-L1123)).
Kindlings' normal `derived` handler uses constructor defaults without a policy/configuration option, as covered by
`BsonDocumentHandlerSpec`'s `default values from Scala-level defaults` test.

**Suggested wording:** “Scala constructor defaults are honored automatically when BSON fields are absent; no
`MacroOptions.ReadDefaultValues` configuration is required.”

### Nested flattening: a verified Kindlings setup advantage

The reference supports `@Flatten`, including normal one-level flattening. A direct two-level probe, however, failed:

```scala
final case class Coordinates(x: Int, y: Int)
final case class Address(city: String, @Flatten coordinates: Coordinates)
final case class Person(name: String, @Flatten address: Address)
val handler = Macros.handler[Person]
```

The error was `No implicit found for 'Person.address': BSONWriter[Address]`: deriving the root does not recursively
materialize the flattened child's document handler by default. Kindlings' `@Flatten works with nested flattening` test
derives only the root handler and verifies the fully flattened wire document. This is another consequence of
Kindlings' recursive root derivation rather than evidence that the reference's `@Flatten` mechanism cannot nest when
all required child handlers are supplied.

**Suggested wording:** “Nested flattened case classes can be derived from the root handler without separately
materializing handlers for each flattened child.”

### Confirmed parity; do not present as advantages

- **Scala 2/3 availability:** both projects have separate Scala 2 and Scala 3 macro implementations. The reference
  additionally cross-builds for Scala 2.11 and 2.12, whereas Kindlings targets 2.13 and 3.
- **Naming and discriminators:** the reference's `MacroConfiguration` exposes field naming, type naming, and a custom
  discriminator, corresponding to Kindlings' configuration concepts.
- **Non-string map keys and wire representation:** both use ReactiveMongo's `KeyReader[K]` and `KeyWriter[K]` and encode
  maps as BSON documents whose element names are the encoded keys. The reference has direct Locale and custom-key
  macro tests (`MacroSpec.scala`, `be generated for Map property`), while `KeyReader.scala` and `KeyWriter.scala`
  define built-ins including numeric, Locale, and UUID keys. Kindlings uses the same two type classes and BSON document
  representation. This is interoperability/parity, not an advantage.
- **Field annotations:** both support equivalent key rename, ignore, `NoneAsNull`, `DefaultValue`, `Reader`, `Writer`,
  and `Flatten` mechanisms. The reference definitions are in
  [`MacroAnnotations.scala`](https://github.com/ReactiveMongo/ReactiveMongo-BSON/blob/0447b636b749600e7a35a986c98a46c8a307b017/api/src/main/scala/MacroAnnotations.scala),
  with positive and invalid-type tests in `MacroSpec.scala`. Promote only the reduced-setup behaviors described above,
  not the mere existence of these annotations.

## Documentation recommendation

Add a concise “Why Kindlings?” callout to the ReactiveMongo BSON user guide. The strongest verified themes are:

1. one root declaration recursively derives ordinary and sealed/recursive structures;
2. inline handling of value-class fields and nested flattening avoids child-handler declarations;
3. Scala constructor defaults work without an opt-in macro option;
4. Scala 3 supports `derives` and named tuples; and
5. one-off serialization can emit only the write path.

Document standalone reader and writer derivation as API parity, with the directional-independence guarantee, rather
than as a comparative performance advantage.

Keep the migration table factual, and avoid implying advantages for map-key encoding, naming configuration, or the
mere availability of field annotations, where the implementations are at parity.
