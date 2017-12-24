package diameterServer.dictionary

import scala.collection.mutable.ListBuffer

import org.json4s._
import org.json4s.JsonDSL._
import scala.reflect.ManifestFactory.Int
import scala.reflect.ManifestFactory.classType

import diameterServer.ConfigManager


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

class GroupedProperties(mandatory: Option[Boolean], val minOccurs: Option[Int], val maxOccurs: Option[Int]){
  val m = mandatory.getOrElse(false)
  override def toString() = {s"{minOccurs: $minOccurs, maxOccurs: $maxOccurs, mandatory: $m}"}
}

/*
 * Helper classes for encoding from/to JSON
 */
abstract class AVPAttributes {
  def code: Int
  def name: String
  def diameterType: Int
}

case class SimpleAVPAttributes(code: Int, name: String, diameterType: Int) extends AVPAttributes {
  override def toString() = {s"[code: $code, name: $name, type: $diameterType]"}
}

case class GroupedAVPAttributes(code: Int, name: String, diameterType: Int, val groupedItems: Map[String, GroupedProperties]) extends AVPAttributes{
  override def toString() = {s"[code: $code, name: $name, type: $diameterType, groupedItems: $groupedItems]"}
}

case class EnumeratedAVPAttributes(code: Int, name: String, diameterType: Int, val values: Map[String, Int]) extends AVPAttributes {
  override def toString() = {s"[code: $code, name: $name, type: $diameterType, values: $values]"}
}

/*
 * Dictionary item classes
 */
abstract class AVPDictItem {
  def code: Int
  def vendorId: Int
  def name: String
  def diameterType: Int
}
case class BasicAVPDictItem(code: Int, vendorId: Int, name: String, diameterType: Int) extends AVPDictItem
case class GroupedAVPDictItem(code: Int, vendorId: Int, name: String, diameterType: Int, groupedItems: Map[String, GroupedProperties]) extends AVPDictItem
case class EnumeratedAVPDictItem(code: Int, vendorId: Int, name: String, diameterType: Int, values: Map[String, Int]) extends AVPDictItem

// Custom serializer
class AVPAttributesSerializer extends CustomSerializer[AVPAttributes](implicit formats => (
    {
      // Reads a JSON and returns a AVPDictionaryItem
      case jv: JValue => 
        val diameterType = (jv \ "type").extract[String]
        val code = (jv \ "code").extract[Int]
        val name = (jv \ "name").extract[String]
        if(diameterType == "OctetString") new SimpleAVPAttributes(code, name, DiameterTypes.OCTETSTRING)
        else if(diameterType == "Integer32") new SimpleAVPAttributes(code, name, DiameterTypes.INTEGER_32)
        else if(diameterType == "Integer64") new SimpleAVPAttributes(code, name, DiameterTypes.INTEGER_64)
        else if(diameterType == "Unsigned32") new SimpleAVPAttributes(code, name, DiameterTypes.UNSIGNED_32)       
        else if(diameterType == "Unsigned64") new SimpleAVPAttributes(code, name, DiameterTypes.UNSIGNED_64)      
        else if(diameterType == "Float32") new SimpleAVPAttributes(code, name, DiameterTypes.FLOAT_32) 
        else if(diameterType == "Float64") new SimpleAVPAttributes(code, name, DiameterTypes.FLOAT_64)
        else if(diameterType == "Address") new SimpleAVPAttributes(code, name, DiameterTypes.ADDRESS)
        else if(diameterType == "Time") new SimpleAVPAttributes(code, name, DiameterTypes.TIME)
        else if(diameterType == "UTF8String") new SimpleAVPAttributes(code, name, DiameterTypes.UTF8STRING)
        else if(diameterType == "DiamIdent") new SimpleAVPAttributes(code, name, DiameterTypes.DIAMETERIDENTITY)
        else if(diameterType == "DiameterURI") new SimpleAVPAttributes(code, name, DiameterTypes.DIAMETERURI)
        else if(diameterType == "IPFilterRule") new SimpleAVPAttributes(code, name, DiameterTypes.IPFILTERRULE)
        else if(diameterType == "Enumerated") new EnumeratedAVPAttributes(code, name, DiameterTypes.ENUMERATED, (jv \"enumValues").extract[Map[String, Int]])
        else if(diameterType == "Grouped") new GroupedAVPAttributes(code, name, DiameterTypes.GROUPED, (jv \"group").extract[Map[String, GroupedProperties]])
        else if(diameterType == "IPv4Address") new SimpleAVPAttributes(code, name, DiameterTypes.RADIUS_IPV4ADDRESS)
        else if(diameterType == "IPv6Address") new SimpleAVPAttributes(code, name, DiameterTypes.RADIUS_IPV6ADDRESS)
        else if(diameterType == "IPv6Prefix") new SimpleAVPAttributes(code, name, DiameterTypes.RADIUS_IPV6PREFIX)
        else throw new java.text.ParseException("Invalid diameter type " + diameterType, 0)
    },
    {
      // Reads a AVPDictionaryItem and returns a JSON
      case v : AVPAttributes => 
        // Not used
        JObject()
    },
))

// Applications
class AVPNameWithBounds(val name: String, val bounds: GroupedProperties)
// Request or Response
class RoRDictItem(val avpList: List[AVPNameWithBounds])
class RequestDictItem(avpList: List[AVPNameWithBounds]) extends RoRDictItem(avpList)
class ResponseDictItem(avpList: List[AVPNameWithBounds]) extends RoRDictItem(avpList)
class CommandDictItem(val code: Int, val name: String, val request: RequestDictItem, val response: ResponseDictItem)
class ApplicationDictItem(val code: Int, val name: String, val commands: List[CommandDictItem])

// A RequestOrResponse is a JSON object with attribute names as properties, and
// Bounds as values
class RoRDictItemSerializer extends CustomSerializer[RoRDictItem](implicit formats => (
    {
      case jv: JValue =>
        val l = for {
          (name, bounds) <- jv.extract[Map[String, JValue]]
        } yield new AVPNameWithBounds(name, bounds.extract[GroupedProperties])
        
        new RoRDictItem(l.toList)
    },
    {
      case v: AVPNameWithBounds =>
        // Not used
        JObject()
    }
))

// Holds the parsed diameter dictionary with utility functions to use
object DiameterDictionary {
  val dictionaryJson = ConfigManager.getConfigObject("diameterDictionary.json")
  
  implicit val jsonFormats = DefaultFormats + new AVPAttributesSerializer + new RoRDictItemSerializer
  
  def getDictionaryItemFromAttributes(dictItem: AVPAttributes, vendorId: String, vendorMap: Map[String, String]) : AVPDictItem = {
    val vendorPrefix = if(vendorId == "0") "" else vendorMap(vendorId)+"-"
    dictItem match {
        case SimpleAVPAttributes(code, name, diameterType) => BasicAVPDictItem(code, vendorId.toInt, vendorPrefix + name, diameterType)
        case GroupedAVPAttributes(code, name, diameterType, groupedAttributes) => GroupedAVPDictItem(code, vendorId.toInt, vendorPrefix + name, diameterType, groupedAttributes)
        case EnumeratedAVPAttributes(code, name, diameterType, values) => EnumeratedAVPDictItem(code, vendorId.toInt, vendorPrefix + name, diameterType, values)
      }
  }
  
  // Holds a map vendorId -> vendorName
  val vendorNames = for {
    (vendorId, vendorName) <- (dictionaryJson \ "vendor").extract[Map[String, String]]
  } yield (vendorId -> vendorName)
  
  // Holds a map ((vendorId, code) -> AVPDictItem)
  val avpMapByCode = for {
    (vendorId, vendorDictItems) <- (dictionaryJson \ "avp").extract[Map[String, JArray]]
    jVendorDictItem <- vendorDictItems.arr
    vendorDictItem = jVendorDictItem.extract[AVPAttributes]
  } yield ((vendorId.toInt, vendorDictItem.code) -> getDictionaryItemFromAttributes(vendorDictItem, vendorId, vendorNames))
    
  // Holds a map (avpName -> DictionaryItem)
  val avpMapByName = for {
    (vendorId, vendorDictItems) <- (dictionaryJson \ "avp").extract[Map[String, JArray]]
    jVendorDictItem <- vendorDictItems.arr
    vendorDictItem = jVendorDictItem.extract[AVPAttributes]
    vendorName = if(vendorId=="0") "" else ((dictionaryJson \ "vendor" \ vendorId).extract[String] + "-")
  } yield (vendorName + vendorDictItem.name -> getDictionaryItemFromAttributes(vendorDictItem, vendorId, vendorNames))
 
  // Holds a map (appName -> Application)
  val appMapByCode = for {
    application <- (dictionaryJson \ "applications").extract[List[ApplicationDictItem]]
  } yield (application.code -> application)
  
  def show() = println(appMapByCode) 
}

