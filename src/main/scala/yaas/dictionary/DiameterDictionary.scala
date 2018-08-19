package yaas.dictionary

import scala.collection.mutable.ListBuffer

import org.json4s._
import org.json4s.JsonDSL._

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


// Companion object
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

case class GroupedProperties(mandatory: Option[Boolean], val minOccurs: Option[Int], val maxOccurs: Option[Int]){
	val isMandatory = mandatory.getOrElse(false)
	override def toString() = {s"{minOccurs: $minOccurs, maxOccurs: $maxOccurs, mandatory: $isMandatory}"}
}

abstract class DiameterAVPDictItem {
	def code: Long
	def vendorId: Long
	def name: String
	def diameterType: Int
}
case class BasicAVPDictItem(code: Long, vendorId: Long, name: String, diameterType: Int) extends DiameterAVPDictItem
case class GroupedAVPDictItem(code: Long, vendorId: Long, name: String, diameterType: Int, groupedItems: Map[String, GroupedProperties]) extends DiameterAVPDictItem
case class EnumeratedAVPDictItem(code: Long, vendorId: Long, name: String, diameterType: Int, values: Map[String, Int], codes: Map[Int, String]) extends DiameterAVPDictItem

// Request or Response
case class RoRDictItem(val avpNameMap: Map[String, GroupedProperties], val avpCodeMap: Map[(Long, Long), GroupedProperties])
case class CommandDictItem(val code: Int, val name: String, val request: RoRDictItem, val response: RoRDictItem)
case class ApplicationDictItem(val code: Long, val name: String, val appType: Option[String], val commandMapByName: Map[String, CommandDictItem], val commandMapByCode: Map[Int, CommandDictItem])

/*
 * Helper classes for encoding from/to JSON
 */
case class JAVP(code: Long, name: String, `type`: String, group: Option[Map[String, GroupedProperties]], enumValues: Option[Map[String, Int]])
case class JCommand(code: Int, name: String, request: Map[String, GroupedProperties], response: Map[String, GroupedProperties])
case class JApplication(name: String, code: Long, appType: Option[String], commands: List[JCommand])

    
// Holds the parsed diameter dictionary with utility functions to use
object DiameterDictionary {
  
  def AVPDictItemFromJAVP(jAVP: JAVP, vendorId: String) : DiameterAVPDictItem = {
    val vendor = vendorId.toLong
    jAVP.`type` match {
      case "None" => BasicAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.OCTETSTRING)
      case "OctetString" => BasicAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.OCTETSTRING)
      case "Integer32" => BasicAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.INTEGER_32)
      case "Integer64" => BasicAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.INTEGER_64)
      case "Unsigned32" => BasicAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.UNSIGNED_32) 
      case "Unsigned64" => BasicAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.UNSIGNED_64) 
      case "Float32" => BasicAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.FLOAT_32)
      case "Float64" => BasicAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.FLOAT_64)
      case "Address" => BasicAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.ADDRESS)
      case "Time" => BasicAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.TIME)
      case "UTF8String" => BasicAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.UTF8STRING)
      case "DiamIdent" => BasicAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.DIAMETERIDENTITY)
      case "DiameterURI" => BasicAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.DIAMETERURI)
      case "IPFilterRule" => BasicAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.IPFILTERRULE)
      case "Enumerated" => EnumeratedAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.ENUMERATED, jAVP.enumValues.get, jAVP.enumValues.get.map(_.swap))
      case "Grouped" => GroupedAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.GROUPED, jAVP.group.get)
      case "IPv4Address" => BasicAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.RADIUS_IPV4ADDRESS)
      case "IPv6Address" => BasicAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.RADIUS_IPV6ADDRESS)
      case "IPv6Prefix" => BasicAVPDictItem(jAVP.code, vendor, jAVP.name, DiameterTypes.RADIUS_IPV6PREFIX)
      case _ => throw new java.text.ParseException(s"Invalid diameter type in $jAVP " + jAVP.`type`, 0)
    }
  }
  
	val dictionaryJson = ConfigManager.getConfigObject("diameterDictionary.json")

  implicit val jsonFormats = DefaultFormats
  
  // Read JSON
	val jAVPMap = (dictionaryJson \ "avp").extract[Map[String, List[JAVP]]]
	val jApplicationMap = (dictionaryJson \ "applications").extract[List[JApplication]]
  
	// Holds a map vendorId -> vendorName
	val vendorNames = for {
		(vendorId, vendorName) <- (dictionaryJson \ "vendor").extract[Map[String, String]]
	} yield (vendorId.toLong -> vendorName)
	
	// Holds a map ((vendorId, code) -> AVPDictItem)
	val avpMapByCode = (for {
	  (vendorId, avps) <- jAVPMap
	  avp <- avps
	} yield ((vendorId.toLong, avp.code) -> AVPDictItemFromJAVP(avp, vendorId))).toMap
	
	// Holds a map (avpName -> DictionaryItem)
	val avpMapByName = (for {
	  ((vendorId, _), dictItem) <- avpMapByCode
	  vendorName = if(vendorId == 0) "" else vendorNames(vendorId) + "-"
	} yield (vendorName + dictItem.name -> dictItem)).toMap
	
	// Helper function
	def RoRDictItemFromMap(avpMap: Map[String, GroupedProperties]) = {
    val avpCodeMap = for {
      (avpName, gp) <- avpMap
    } yield ((avpMapByName(avpName).vendorId, avpMapByName(avpName).code) -> gp)
    
    RoRDictItem(avpMap, avpCodeMap)
  }
  
	// Helper function
  def ApplicationDictItemFromJApplication(jApp: JApplication) = {
    val commandsByName = for {
      command <- jApp.commands
    } yield (command.name -> CommandDictItem(command.code, command.name, RoRDictItemFromMap(command.request), RoRDictItemFromMap(command.response)))
        
    val commandsByCode = for {
      (commandName, commandDictItem) <- commandsByName
    } yield (commandDictItem.code -> commandDictItem)
        
    ApplicationDictItem(jApp.code, jApp.name, jApp.appType, commandsByName.toMap, commandsByCode.toMap)
  }
	
	// Holds a map (appCode -> ApplicationDictItem)
	val appMapByCode = (for {
	  application <- jApplicationMap
	} yield (application.code -> ApplicationDictItemFromJApplication(application))).toMap
	
	val appMapByName = (for {
	  (appName, applicationDictItem) <- appMapByCode
	} yield (applicationDictItem.name-> applicationDictItem)).toMap
  
	
	def show() = println(appMapByCode) 
}

