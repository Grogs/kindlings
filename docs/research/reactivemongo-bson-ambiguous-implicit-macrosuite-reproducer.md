# Scala 2 `MacroSuite.compileErrors` cannot capture ambiguous BSON field codecs

## Status

This is a candidate upstream Hearth issue. The shared ReactiveMongo BSON derivation suite cannot currently assert the
diagnostic for an ambiguous user-provided `BSONReader` or `BSONWriter` on Scala 2.13. `MacroSuite.compileErrors`
receives a source string, but Scala 2 reports the ambiguous implicit while typechecking the nested source before the
suite can turn that diagnostic into its assertion result.

This is a test-harness limitation, not a derivation result: ordinary Scala compilation correctly rejects the
ambiguity. Separate reader and writer regression tests are retained in the Scala 3 suite, where `compileErrors` can
capture the diagnostics.

## Minimal shared source

```scala
import hearth.kindlings.reactivemongobsonderivation.KindlingsBsonDocumentReader
import reactivemongo.api.bson.BSONReader

final class AmbiguousSecret
final case class AmbiguousEnvelope(secret: AmbiguousSecret)
implicit val firstReader: BSONReader[AmbiguousSecret] = BSONReader.from(_ => scala.util.Success(new AmbiguousSecret))
implicit val secondReader: BSONReader[AmbiguousSecret] = BSONReader.from(_ => scala.util.Success(new AmbiguousSecret))
KindlingsBsonDocumentReader.derived[AmbiguousEnvelope]
```

Placed in the shared `BsonDocumentHandlerSpec` as:

```scala
compileErrors("""...source above...""").check("ambiguous implicit values")
```

Scala 2.13 fails compilation of the test suite itself with:

```text
<macro>:4:18: ambiguous implicit values:
 both value secondReader of type reactivemongo.api.bson.BSONReader[AmbiguousSecret]
 and value firstReader of type reactivemongo.api.bson.BSONReader[AmbiguousSecret]
 match expected type reactivemongo.api.bson.BSONReader[AmbiguousSecret]
```

The location points at the nested source processed by `compileErrors`; no MUnit assertion is run.

## Proposed Hearth issue

### Suggested title

Scala 2 `MacroSuite.compileErrors` does not capture ambiguous implicit diagnostics from nested source

### Expected behavior

`MacroSuite.compileErrors(source)` should return the ambiguous-implicit diagnostic so that `.check(...)` can assert
it, as it does on Scala 3.

### Actual behavior

On Scala 2.13, the ambiguous implicit is reported while the nested source is being typechecked. It escapes the
diagnostic-capturing boundary and fails compilation of the test suite itself, so no MUnit assertion runs.

### Expected fix direction

The Scala 2 implementation of Hearth's `MacroSuite.compileErrors` should compile the supplied source under a
diagnostic-capturing boundary that includes implicit search and typechecking, not only macro-abort diagnostics. The
minimal source above should become a Hearth regression test without any Kindlings or ReactiveMongo dependency; plain
local type classes and two competing implicit values are sufficient.

## Kindlings follow-up

Once a fixed Hearth version is available, add separate ambiguous-reader and ambiguous-writer assertions to the shared
`BsonDocumentHandlerSpec` and remove their Scala-3-only counterparts. Until then:

- ordinary compilation rejects both ambiguities correctly;
- Scala 3 has executable regression coverage for both directions;
- the shared suite covers reader-only and writer-only missing-codec failures; and
- this document preserves the exact Scala 2 reproducer and the material for the upstream issue.
