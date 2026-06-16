package hearth.kindlings.reactivemongobsonderivation

// Field name annotation
import hearth.kindlings.reactivemongobsonderivation.annotations.fieldName
import hearth.kindlings.reactivemongobsonderivation.annotations.noneAsNull
import hearth.kindlings.reactivemongobsonderivation.annotations.defaultValue
import hearth.kindlings.reactivemongobsonderivation.annotations.reader
import hearth.kindlings.reactivemongobsonderivation.annotations.writer

// Simple types
final case class Empty()
final case class Person(name: String, age: Int)
final case class Address(street: String, city: String)
final case class PersonWithAddress(name: String, address: Address)

// Option fields
final case class MaybeName(name: Option[String])
final case class MaybeNested(address: Option[Address])
final case class MaybeAsNull(@noneAsNull name: Option[String])
final case class MaybeNestedAsNull(@noneAsNull address: Option[Address])

// Default values
final case class WithDefault(name: String = "unknown")
final case class OptionalDefault(name: Option[String] = Some("unknown"))
final case class WithAnnotatedDefaults(id: Int, @defaultValue("anon") name: String, @defaultValue(0) score: Int)

// Collection fields
final case class WithList(names: List[String])
final case class WithSeq(scores: Seq[Int])
final case class WithVector(tags: Vector[String])
final case class WithSet(unique: Set[Int])
final case class WithMap(items: Map[String, Int])
final case class WordLover(name: String, words: Seq[String])

// Single member case class
final case class SingleBigDecimal(value: BigDecimal)

// Flatten annotation
final case class Range(start: Int, end: Int)
final case class LabelledRange(
    name: String,
    @hearth.kindlings.reactivemongobsonderivation.annotations.flatten range: Range
)

final case class InnerFlatten(a: Int, b: Int)
final case class MiddleFlatten(
    @hearth.kindlings.reactivemongobsonderivation.annotations.flatten inner: InnerFlatten,
    c: String
)
final case class OuterFlatten(
    @hearth.kindlings.reactivemongobsonderivation.annotations.flatten middle: MiddleFlatten,
    d: String
)

// Value types (AnyVal)
final case class WrapperId(value: Int) extends AnyVal
final case class WithValueType(id: WrapperId, name: String)

// Field name annotation
final case class AnnotatedFields(
    @fieldName("first_name") firstName: String,
    @fieldName("years_old") age: Int
)

// Enum / sealed trait
sealed trait SimpleEnum
case object Foo extends SimpleEnum
case object Bar extends SimpleEnum

sealed trait Expr
case class Num(value: Int) extends Expr
case class Str(value: String) extends Expr
case object NoExpr extends Expr

// Field name mapping
final case class CamelCaseFields(firstName: String, lastName: String, ageInYears: Int)
final case class SnakeFields(first_name: String, last_name: String)

// Recursive structure
sealed trait Tree
final case class TreeNode(left: Tree, right: Tree) extends Tree
final case class TreeLeaf(data: String) extends Tree

// @reader / @writer annotations
final case class WithPerFieldIO(
    @reader(reactivemongo.api.bson.BSONStringHandler) id: String,
    @writer(reactivemongo.api.bson.BSONStringHandler) name: String
)
