package yaas.dictionary

// https://freeradius.org/radiusd/man/dictionary.html

import yaas.config.ConfigManager

import org.json4s._

/**
 * Hosts radius types enumeration
 */
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

// Used for JSON encoding of the dictionary
private case class RadiusAVPAttributes(code: Int, name: String, radiusType: String, encrypt: Int, hasTag: Boolean, enumValues: Option[Map[String, Int]])

// CustomSerializer is parametrized with a function that receives one value of type Format and returns two partial functions
private class RadiusAVPAttributesSerializer extends CustomSerializer[RadiusAVPAttributes](implicit formats => (
  {
    case jv: JValue =>
			(jv \ "hasTag").extract[Option[Boolean]].getOrElse(false)
      RadiusAVPAttributes(
         (jv \ "code").extract[Int],
         (jv \ "name").extract[String],
         (jv \ "type").extract[String],
         (jv \ "encrypt").extract[Option[Int]].getOrElse(0),
				 (jv \ "hasTag").extract[Option[Boolean]].getOrElse(false),
				 jv \ "enumValues" match {
           case JObject(items) =>
						 Some(items.map{ case (k, v) => (k, v.extract[Int])}.toMap)
           case _ => None
         }
      )
  },
  {
		// This is never used
    case _ =>
      JObject()
  }))

/**
 * Represents an entry in the Radius Dictionary
 * @param code number for the code
 * @param vendorId number for the vendor id
 * @param name attribute name including vendor prefix
 * @param radiusType one of the valid types in [[RadiusTypes]]
 * @param encrypt encryption type. 0 for no encription
 * @param hasTag true or false
 * @param enumValues map from enumerated string constants to its corresponding numbers
 * @param enumNames map from the enumerated contstants to its string representation
 */
case class RadiusAVPDictItem(code: Int, vendorId: Int, name: String, radiusType: Int, encrypt: Int, hasTag: Boolean, enumValues: Option[Map[String, Int]], enumNames: Option[Map[Int, String]])

/**
 * Holds the parsed Radius dictionary with utility functions to use
 */

object RadiusDictionary {
	private val dictionaryJson = ConfigManager.getConfigObjectAsJson("radiusDictionary.json")

	private implicit val jsonFormats: Formats = DefaultFormats + new RadiusAVPAttributesSerializer
	
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
	        case "IPv6Address" => RadiusTypes.IPV6ADDR
	        case "IPv6Prefix" => RadiusTypes.IPV6PREFIX
	        case "InterfaceId" => RadiusTypes.IFID
	        case "Integer64" => RadiusTypes.INTEGER64
	        case _ => throw new java.text.ParseException("Invalid radius type " + avpAttributes.radiusType, 0)
	      },
	      avpAttributes.encrypt,
	      avpAttributes.hasTag, 
	      avpAttributes.enumValues,
	      avpAttributes.enumValues.map(_.map(_.swap))
	   )
	}

	/**
	 * Holds a map vendorId -> vendorName
	 */
	val vendorNames: Map[String, String] = for {
		(vendorId, vendorName) <- (dictionaryJson \ "vendor").extract[Map[String, String]]
	} yield vendorId -> vendorName

	/**
	 * Holds a map ((vendorId, code) -> RadiusAVPDictItem)
	 */
	val avpMapByCode: Map[(Int, Int), RadiusAVPDictItem] = for {
		(vendor, avps) <- (dictionaryJson \ "avp").extract[Map[String, JArray]]
		jAttrs <- avps.arr
		attrs = jAttrs.extract[RadiusAVPAttributes]
	} yield (vendor.toInt, attrs.code) -> getDictionaryItemFromAttributes(attrs, vendor, vendorNames)

	/** 
	 *  Holds a map (name -> RadiusAVPDictItem)
	 */
	val avpMapByName: Map[String, RadiusAVPDictItem] = for {
		(vendor, avps) <- (dictionaryJson \ "avp").extract[Map[String, JArray]]
    jAttrs <- avps.arr
    attrs = jAttrs.extract[RadiusAVPAttributes]
    vendorName = if(vendor == "0") "" else vendorNames(vendor) + "-"
	} yield (vendorName + attrs.name) -> getDictionaryItemFromAttributes(attrs, vendor, vendorNames)

	/**
	 * Gets the code of the AVP in an Option or None if name not in dictionary
	 */
	def getAttrCodeFromName(name: String): Option[(Long, Long)] = {
		avpMapByName.get(name).map(di => (di.vendorId, di.code))
	}

	val unknownRadiusDictionaryItem: RadiusAVPDictItem = RadiusAVPDictItem(0, 0, "UNKNOWN", RadiusTypes.NONE, 0, hasTag = false, None, None)
}

