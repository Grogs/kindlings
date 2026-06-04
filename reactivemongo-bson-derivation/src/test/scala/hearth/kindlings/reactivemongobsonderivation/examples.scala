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
