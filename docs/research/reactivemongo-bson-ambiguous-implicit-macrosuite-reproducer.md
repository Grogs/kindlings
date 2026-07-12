# Scala 2 `MacroSuite.compileErrors` cannot capture ambiguous BSON field codecs

## Summary

The shared ReactiveMongo BSON derivation suite cannot currently assert the diagnostic for an ambiguous user-provided
`BSONReader` (and, symmetrically, a `BSONWriter`) on Scala 2.13. `MacroSuite.compileErrors` receives a source string,
but Scala 2 reports the ambiguous implicit while typechecking the nested source before the suite can turn that
diagnostic into its assertion result.

This is a test-harness limitation, not a derivation result: ordinary Scala compilation correctly rejects the
ambiguity, and Scala 3 can capture it through `compileErrors`.

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

## Scope and expected fix direction

Keep this case cross-compiled. Moving it into `src/test/scala-3` would conceal the Scala 2 limitation rather than
testing it. The appropriate fix is in the Scala 2 implementation of Hearth's `MacroSuite.compileErrors`: it needs to
compile the supplied source under a diagnostic-capturing boundary that includes implicit search/typechecking, not only
macro-abort diagnostics.

Until that exists, the regular shared suite covers reader-only and writer-only derivation failures, while this
document preserves the exact failing shared regression input for Hearth/MacroSuite work.
