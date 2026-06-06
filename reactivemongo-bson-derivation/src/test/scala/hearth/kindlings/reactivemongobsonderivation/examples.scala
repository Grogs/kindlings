package hearth.kindlings.reactivemongobsonderivation

// Simple types
final case class Empty()
final case class Person(name: String, age: Int)
final case class Address(street: String, city: String)
final case class PersonWithAddress(name: String, address: Address)

// Option fields
final case class MaybeName(name: Option[String])
final case class MaybeNested(address: Option[Address])

// Default values
final case class WithDefault(name: String = "unknown")
final case class OptionalDefault(name: Option[String] = Some("unknown"))

// Collection fields
final case class WithList(names: List[String])
final case class WithSeq(scores: Seq[Int])
final case class WithVector(tags: Vector[String])
final case class WithSet(unique: Set[Int])
final case class WithMap(items: Map[String, Int])

// Value types (AnyVal)
final case class WrapperId(value: Int) extends AnyVal
final case class WithValueType(id: WrapperId, name: String)

// Field name annotation
import hearth.kindlings.reactivemongobsonderivation.annotations.fieldName
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
