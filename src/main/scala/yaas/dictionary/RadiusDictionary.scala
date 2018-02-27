package yaas.dictionary

// https://freeradius.org/radiusd/man/dictionary.html

import yaas.config.ConfigManager

import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

object RadiusTypes {
  val NONE: Int = 0
	val STRING: Int = 1
	val OCTETS: Int = 2
	val ADDRESS: Int = 3
	val INTEGER: Int = 4
	val TIME: Int = 5
	val IPV6ADDR: Int = 6
	val IPV6PREFIX: Int = 7
	val IFID: Int = 8
	val INTEGER64 = 9
}

case class RadiusAVPAttributes(code: Int, name: String, radiusType: String, hasTag: Boolean, enumValues: Option[Map[String, Int]])
class RadiusAVPAttributesSerializer extends CustomSerializer[RadiusAVPAttributes](implicit formats => (
  {
    case jv: JValue =>
      RadiusAVPAttributes(
         (jv \ "code").extract[Int],
         (jv \ "name").extract[String],
         (jv \ "type").extract[String],
         jv \ "hasTag" match {
           case JBool(true) => true
           case _ => false
         },
         (jv \ "enumValues") match {
           case JNothing => None
           case JObject(items) =>
             Some((for {
               (k, v) <- items
             } yield (k, v.extract[Int])).toMap)
           case _ => None
         }
      )
  },
  {
    case v : RadiusAVPDictItem =>
      JObject()
  }))
  
case class RadiusAVPDictItem(code: Int, vendorId: Int, name: String, radiusType: Int, hasTag: Boolean, enumValues: Option[Map[String, Int]], enumNames: Option[Map[Int, String]])

// Holds the parsed diameter dictionary with utility functions to use
object RadiusDictionary {
	val dictionaryJson = ConfigManager.getConfigObject("radiusDictionary.json")

	implicit var jsonFormats = DefaultFormats + new RadiusAVPAttributesSerializer
	
	private def getDictionaryItemFromAttributes(avpAttributes: RadiusAVPAttributes, vendorId: String, vendorMap: Map[String, String]) = {
	  val vendorPrefix = if(vendorId == "0") "" else vendorMap(vendorId) + "-"
	  RadiusAVPDictItem(
	      avpAttributes.code, 
	      vendorId.toInt, 
	      vendorPrefix + avpAttributes.name, 
	      avpAttributes.radiusType match {
	        case "String" => RadiusTypes.STRING
	        case "Octets" => RadiusTypes.OCTETS
	        case "Address" => RadiusTypes.ADDRESS
	        case "Integer" => RadiusTypes.INTEGER
	        case "Time" => RadiusTypes.TIME
	        case "IPv6Addr" => RadiusTypes.IPV6ADDR
	        case "IPv6Prefix" => RadiusTypes.IPV6PREFIX
	        case "IfId" => RadiusTypes.IFID
	        case "Integer64" => RadiusTypes.INTEGER64
	        case _ => throw new java.text.ParseException("Invalid radius type " + avpAttributes.radiusType, 0)
	      },
	      avpAttributes.hasTag, 
	      avpAttributes.enumValues,
	      avpAttributes.enumValues.map(_.map(_.swap))
	   )
	}

	// Holds a map vendorId -> vendorName
	val vendorNames = for {
		(vendorId, vendorName) <- (dictionaryJson \ "vendor").extract[Map[String, String]]
	} yield (vendorId -> vendorName)

	// Holds a map ((vendorId, code) -> AVPDictItem)
	val avpMapByCode = for {
		(vendor, avps) <- (dictionaryJson \ "avp").extract[Map[String, JArray]]
		jAttrs <- avps.arr
		attrs = jAttrs.extract[RadiusAVPAttributes]
	} yield ((vendor.toInt, attrs.code) -> getDictionaryItemFromAttributes(attrs, vendor, vendorNames))

	// Holds a map (name -> AVPDitcitem)
	val avpMapByName = for {
		(vendor, avps) <- (dictionaryJson \ "avp").extract[Map[String, JArray]]
    jAttrs <- avps.arr
    attrs = jAttrs.extract[RadiusAVPAttributes]
    vendorName = if(vendor == "0") "" else vendorNames(vendor) + "-"
	} yield ((vendorName + attrs.name) -> getDictionaryItemFromAttributes(attrs, vendor, vendorNames))
}

object rd extends App {
  println(RadiusDictionary.avpMapByName("Service-Type"))
}
