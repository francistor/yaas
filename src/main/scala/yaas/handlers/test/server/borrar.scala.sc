import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.JsonDSL._

val a = ("name", "joe") ~ ("age", true) merge ("name", "joe") ~ ("age", false)