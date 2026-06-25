package hearth.kindlings.reactivemongobsonderivation

// Field name annotation
import hearth.kindlings.reactivemongobsonderivation.annotations.FieldName
import hearth.kindlings.reactivemongobsonderivation.annotations.NoneAsNull
import hearth.kindlings.reactivemongobsonderivation.annotations.DefaultValue
import hearth.kindlings.reactivemongobsonderivation.annotations.Reader
import hearth.kindlings.reactivemongobsonderivation.annotations.Writer

// Simple types
final case class Empty()
final case class Person(name: String, age: Int)
final case class Address(street: String, city: String)
final case class PersonWithAddress(name: String, address: Address)

// Option fields
final case class MaybeName(name: Option[String])
final case class MaybeNested(address: Option[Address])
final case class MaybeAsNull(@NoneAsNull name: Option[String])
final case class MaybeNestedAsNull(@NoneAsNull address: Option[Address])

// Default values
final case class WithDefault(name: String = "unknown")
final case class OptionalDefault(name: Option[String] = Some("unknown"))
final case class WithAnnotatedDefaults(id: Int, @DefaultValue("anon") name: String, @DefaultValue(0) score: Int)

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
    @hearth.kindlings.reactivemongobsonderivation.annotations.Flatten range: Range
)

final case class InnerFlatten(a: Int, b: Int)
final case class MiddleFlatten(
    @hearth.kindlings.reactivemongobsonderivation.annotations.Flatten inner: InnerFlatten,
    c: String
)
final case class OuterFlatten(
    @hearth.kindlings.reactivemongobsonderivation.annotations.Flatten middle: MiddleFlatten,
    d: String
)

// TypeNaming
object TreeModule {
  sealed trait Node
  final case class Leaf(data: String) extends Node
  final case class Branch(left: Node, right: Node) extends Node
}

sealed trait Status
case object Active extends Status
case object Inactive extends Status

// Value types (AnyVal)
final case class WrapperId(value: Int) extends AnyVal
final case class WithValueType(id: WrapperId, name: String)

// Field name annotation
final case class AnnotatedFields(
    @FieldName("first_name") firstName: String,
    @FieldName("years_old") age: Int
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
    @Reader(reactivemongo.api.bson.BSONStringHandler) id: String,
    @Writer(reactivemongo.api.bson.BSONStringHandler) name: String
)

// Reference-ported test data
final case class Pet(name: String, owner: Person)

final case class Primitives(dbl: Double, str: String, bl: Boolean, int: Int, long: Long)

final case class Optional(name: String, value: Option[String])
final case class OptionalAsNull(name: String, @NoneAsNull value: Option[String])
final case class OptionalSingle(value: Option[String])
final case class OptionalGeneric[T](v: Int, opt: Option[T])

final case class Foo[T](bar: T, lorem: String)
final case class Bar(name: String, next: Option[Bar])

final case class GenSeq[A](items: Seq[A], count: Int)

final case class OverloadedApply(string: String)
object OverloadedApply {
  val apply: Int => Unit = _ => ()
  def apply(seq: Seq[String]): OverloadedApply = OverloadedApply(seq.mkString(" "))
}

final case class OverloadedApply2(string: String, number: Int)
object OverloadedApply2 {
  def apply(string: String): OverloadedApply2 = OverloadedApply2(string, 0)
}

final case class OverloadedApply3(string: String, number: Int)
object OverloadedApply3 {
  def apply(): OverloadedApply3 = OverloadedApply3("", 0)
}

object NestModule {
  case class Nested(name: String)
}

final case class RenamedId(@FieldName("_id") myID: reactivemongo.api.bson.BSONObjectID, value: String)

final case class WithDefaultValues1(
    id: Int,
    title: String = "default1",
    score: Option[Float] = Some(1.23f),
    range: Range = Range(3, 5)
)

final case class WithDefaultValues2(
    id: Int,
    @DefaultValue("default2") title: String,
    @DefaultValue(Some(45.6f)) score: Option[Float],
    @DefaultValue(Range(7, 11)) range: Range
)

final case class WithMap1(name: String, localizedDescription: Map[String, String])
final case class WithLocaleMap(name: String, localizedDescription: Map[java.util.Locale, String])
final case class WithUUIDMap(items: Map[java.util.UUID, Int])

final class FooVal(val v: Int) extends AnyVal
final case class Item(name: String, number: FooVal)
final case class Person2(name: String, age: Int, phoneNum: Long, itemList: Seq[Item], list: Seq[Int])

final case class WithValueClass(value: Int) extends AnyVal
final case class WithValueTypeField(name: String, id: WithValueClass)

final case class WithIgnoredField(
    id: Int,
    @hearth.kindlings.reactivemongobsonderivation.annotations.Ignore visible: Boolean = true,
    name: String
)
