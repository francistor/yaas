package yaas.dictionary

import org.json4s._

import yaas.config.ConfigManager

// dictionary = {
//      avp: {
//          <vendor_id>:[
//              {code:<code>, name:<name>, type:<type>},
//              {code:<code>, name:<name>, type:<type>}
//          ]
//      },
/*
 * 			applications: [
 * 				{
 * 					name: <name>,
 * 					code: <code>,
 * 					commands: [
 * 						{
 * 						name: <name>,
 * 						code: <code>,
 * 						request: [
 * 							{<attribute-name>: {mandatory: <true|false>, minOccurs: <number>, maxOccurs: <false>}
 * 						],
 * 						response: [
 * 							{<attribute-name>: {mandatory: <true|false>, minOccurs: <number>, maxOccurs: <false>}
 * 						]
 * 						}
 * 					]
 * 				}
 * 			]
 */
//
//      // Generated
//      avpCodeMap: {
//          <vendor_id>:{
//              <code>:<avpDef> --> Added enumCodes if type Enum
//          }
//      }
//      avpNameMap:{
//          <avp_name>:{        --> Name is <vendor_name>-<avp_name>
//          }
//      }
//      applicationCodeMap:{
//          <app_code>:<application_def>
//      }
//      applicationNameMap:{
//          <app_name>:<application_def>
//      }
//      commandCodeMap:{
//          <command_code>:<command_def>
//      }
//      commandNameMap:{
//          <command_name>:<command_def>
//      }
// }


/**
 * Hosts diameter types enumeration
 */
object DiameterTypes {
	val NONE: Int = 0
	val OCTETSTRING: Int = 1
	val INTEGER_32: Int = 2
	val INTEGER_64: Int = 3
	val UNSIGNED_32: Int = 4
	val UNSIGNED_64: Int = 5
	val FLOAT_32: Int = 6
	val FLOAT_64: Int = 7
	val GROUPED: Int = 8
	val ADDRESS: Int = 9
	val TIME: Int = 10
	val UTF8STRING: Int = 11
	val DIAMETERIDENTITY: Int = 12
	val DIAMETERURI: Int = 13
	val ENUMERATED: Int = 14
	val IPFILTERRULE: Int = 15

	// Radius types
	val RADIUS_IPV4ADDRESS: Int = 1001
	val RADIUS_IPV6ADDRESS: Int = 1002
	val RADIUS_IPV6PREFIX: Int = 1003
}

/**
 * Holder of the attributes of a grouped Diameter AVP
 * @param mandatory the attribute needs to be understood by the server. I guess it makes sense to define this but I don't get it
 * @param minOccurs as the name implies
 * @param maxOccurs as the name implies
 */
case class GroupedProperties(mandatory: Option[Boolean], minOccurs: Option[Int], maxOccurs: Option[Int]){
	val isMandatory: Boolean = mandatory.getOrElse(false)
	override def toString: String = {s"{minOccurs: $minOccurs, maxOccurs: $maxOccurs, mandatory: $isMandatory}"}
}

/**
 * Base class for Diameter Dictionary Items
 */
abstract class DiameterAVPDictItem {
	def code: Long
	def vendorId: Long
	def name: String
	def diameterType: Int
}

/**
 * A Basic Diameter Dictionary item
 * @param code the number
 * @param vendorId the vendor identifier
 * @param name the name with vendor prepended
 * @param diameterType one of the diameter types [[DiameterTypes]]
 */
case class BasicAVPDictItem(code: Long, vendorId: Long, name: String, diameterType: Int) extends DiameterAVPDictItem

/**
 * A Diameter Dictionary item for a Grouped AVP
 * @param code the number
 * @param vendorId the vendor identifier
 * @param name the name with vendor prepended
 * @param diameterType one of the diameter types [[DiameterTypes]]
 * @param groupedItems map to inside attribute names to their properties inside this AVP (mandatory, etc.)
 */
case class GroupedAVPDictItem(code: Long, vendorId: Long, name: String, diameterType: Int, groupedItems: Map[String, GroupedProperties]) extends DiameterAVPDictItem

/**
 * A Diameter Dictionary item for an Enumerated AVP
 * @param code the number
 * @param vendorId the vendor identifier
 * @param name the name with vendor prepended
 * @param diameterType one of the diameter types [[DiameterTypes]]
 * @param values map from names to codes
 * @param codes map from codes to names
 */
case class EnumeratedAVPDictItem(code: Long, vendorId: Long, name: String, diameterType: Int, values: Map[String, Int], codes: Map[Int, String]) extends DiameterAVPDictItem

/**
 * Specification of the attributes in the request or response
 * @param avpNameMap indexed by name
 * @param avpCodeMap indexed by code
 */
case class RoRDictItem(avpNameMap: Map[String, GroupedProperties], avpCodeMap: Map[(Long, Long), GroupedProperties])

/**
 * Specification of a Diameter command
 * @param code the number
 * @param name the name
 * @param request attributes in the request [[RoRDictItem]]
 * @param response attributes in the response [[RoRDictItem]]
 */
case class CommandDictItem(code: Int, name: String, request: RoRDictItem, response: RoRDictItem)

/**
 * Specification of a Diameter application
 * @param code the number
 * @param name the name
 * @param appType auth or acct
 * @param commandMapByName map of commands by name
 * @param commandMapByCode map of commands by code
 */
case class ApplicationDictItem(code: Long, name: String, appType: Option[String], commandMapByName: Map[String, CommandDictItem], commandMapByCode: Map[Int, CommandDictItem])

/**
 * Holds the parsed diameter dictionary with utility functions to use
 */

object DiameterDictionary {

	/*
  * Helper classes for encoding from/to JSON. Member names as used in JSON
  */
	private case class JAVP(code: Long, name: String, `type`: String, group: Option[Map[String, GroupedProperties]], enumValues: Option[Map[String, Int]])
	private case class JCommand(code: Int, name: String, request: Map[String, GroupedProperties], response: Map[String, GroupedProperties])
	private case class JApplication(name: String, code: Long, appType: Option[String], commands: List[JCommand])
  
  private def AVPDictItemFromJAVP(jAVP: JAVP, vendorId: String) : DiameterAVPDictItem = {
    val vendor = vendorId.toLong
    val code = jAVP.code
    val name = (if(vendorId == "0") "" else vendorNames(vendorId.toLong) + "-") + jAVP.name
    jAVP.`type` match {
      case "None" => BasicAVPDictItem(code, vendor, name, DiameterTypes.OCTETSTRING)
      case "OctetString" => BasicAVPDictItem(code, vendor, name, DiameterTypes.OCTETSTRING)
      case "Integer32" => BasicAVPDictItem(code, vendor, name, DiameterTypes.INTEGER_32)
      case "Integer64" => BasicAVPDictItem(code, vendor, name, DiameterTypes.INTEGER_64)
      case "Unsigned32" => BasicAVPDictItem(code, vendor, name, DiameterTypes.UNSIGNED_32) 
      case "Unsigned64" => BasicAVPDictItem(code, vendor, name, DiameterTypes.UNSIGNED_64) 
      case "Float32" => BasicAVPDictItem(code, vendor, name, DiameterTypes.FLOAT_32)
      case "Float64" => BasicAVPDictItem(code, vendor, name, DiameterTypes.FLOAT_64)
      case "Address" => BasicAVPDictItem(code, vendor, name, DiameterTypes.ADDRESS)
      case "Time" => BasicAVPDictItem(code, vendor, name, DiameterTypes.TIME)
      case "UTF8String" => BasicAVPDictItem(code, vendor, name, DiameterTypes.UTF8STRING)
      case "DiamIdent" => BasicAVPDictItem(code, vendor, name, DiameterTypes.DIAMETERIDENTITY)
      case "DiameterURI" => BasicAVPDictItem(code, vendor, name, DiameterTypes.DIAMETERURI)
      case "IPFilterRule" => BasicAVPDictItem(code, vendor, name, DiameterTypes.IPFILTERRULE)
      case "Enumerated" => EnumeratedAVPDictItem(code, vendor, name, DiameterTypes.ENUMERATED, jAVP.enumValues.get, jAVP.enumValues.get.map(_.swap))
      case "Grouped" => GroupedAVPDictItem(code, vendor, name, DiameterTypes.GROUPED, jAVP.group.get)
      case "IPv4Address" => BasicAVPDictItem(code, vendor, name, DiameterTypes.RADIUS_IPV4ADDRESS)
      case "IPv6Address" => BasicAVPDictItem(code, vendor, name, DiameterTypes.RADIUS_IPV6ADDRESS)
      case "IPv6Prefix" => BasicAVPDictItem(code, vendor, name, DiameterTypes.RADIUS_IPV6PREFIX)
      case _ => throw new java.text.ParseException(s"Invalid diameter type in $jAVP " + jAVP.`type`, 0)
    }
  }
  
	private val dictionaryJson = ConfigManager.getConfigObjectAsJson("diameterDictionary.json")

  private implicit val jsonFormats: DefaultFormats.type = DefaultFormats
  
  /**
   * Holds a map vendorId -> vendorName
   */
	val vendorNames: Map[Long, String] = for {
		(vendorId, vendorName) <- (dictionaryJson \ "vendor").extract[Map[String, String]]
	} yield vendorId.toLong -> vendorName
	
	/**
	 * Holds a map ((vendorId, code) -> DiameterAVPDictItem)
	 */
	val avpMapByCode: Map[(Long, Long), DiameterAVPDictItem] = for {
	  (vendorId, avps) <- (dictionaryJson \ "avp").extract[Map[String, List[JAVP]]]
	  avp <- avps
	} yield (vendorId.toLong, avp.code) -> AVPDictItemFromJAVP(avp, vendorId)
	
	/**
	 * Holds a map (avpName -> DiameterAVPDictItem)
	 */
	val avpMapByName: Map[String, DiameterAVPDictItem] = for {
	  (_, dictItem) <- avpMapByCode
	} yield dictItem.name -> dictItem
	
	// Helper function
	private def RoRDictItemFromMap(avpMap: Map[String, GroupedProperties]) = {
    val avpCodeMap = for {
      (avpName, gp) <- avpMap
    } yield (avpMapByName(avpName).vendorId, avpMapByName(avpName).code) -> gp
    
    RoRDictItem(avpMap, avpCodeMap)
  }
  
	// Helper function
  private def ApplicationDictItemFromJApplication(jApp: JApplication) = {
    val commandsByName = for {
      command <- jApp.commands
    } yield command.name -> CommandDictItem(command.code, command.name, RoRDictItemFromMap(command.request), RoRDictItemFromMap(command.response))
        
    val commandsByCode = for {
      (_, commandDictItem) <- commandsByName
    } yield commandDictItem.code -> commandDictItem
        
    ApplicationDictItem(jApp.code, jApp.name, jApp.appType, commandsByName.toMap, commandsByCode.toMap)
  }
	
	/**
	 * Holds a map (appCode -> ApplicationDictItem)
	 */
	val appMapByCode: Map[Long, ApplicationDictItem] = (for {
		application <- (dictionaryJson \ "applications").extract[List[JApplication]]
	} yield application.code -> ApplicationDictItemFromJApplication(application)).toMap

	/**
	 * Holds a map (appName -> ApplicationDictItem)
	 */
	val appMapByName: Map[String, ApplicationDictItem] = for {
	  (appName, applicationDictItem) <- appMapByCode
	} yield applicationDictItem.name-> applicationDictItem

	/**
	 * Gets the code of the AVP in an Option or None if name not in dictionary
	 */
	def getAttrCodeFromName(name: String): Option[(Long, Long)] = {
		avpMapByName.get(name).map(di => (di.vendorId, di.code))
	}

	val unknownDiameterDictionaryItem: BasicAVPDictItem = BasicAVPDictItem(0, 0, "Unknown", DiameterTypes.NONE)
  
	// For debugging
	def show(): Unit = println(appMapByCode)
}

