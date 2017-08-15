package diameterServer

import scala.collection.mutable.ListBuffer

import org.json4s._
import org.json4s.JsonDSL._
import scala.reflect.ManifestFactory.Int
import scala.reflect.ManifestFactory.classType

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
object AVPDictionaryItem {
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

// Base for all AVP
class AbstractAVPDictionaryItem(val code: Int, val name: String, val diameterType: Int){}

class AVPDictionaryItem(code: Int, name: String, diameterType: Int) extends AbstractAVPDictionaryItem(code, name, diameterType){
  override def toString() = {s"[code: $code, name: $name, type: $diameterType]"}
}

class GroupedProperties(mandatory: Option[Boolean], val minOccurs: Option[Int], val maxOccurs: Option[Int]){
  val m = mandatory.getOrElse(false)
  override def toString() = {s"{minOccurs: $minOccurs, maxOccurs: $maxOccurs, mandatory: $m}"}
}

class GroupedDictionaryItem(code: Int, name: String, diameterType: Int, groupedItems: Map[String, GroupedProperties]) extends AbstractAVPDictionaryItem(code, name, diameterType){
  override def toString() = {s"[code: $code, name: $name, type: $diameterType, groupedItems: $groupedItems]"}
}

class EnumeratedDictionaryItem(code: Int, name: String, diameterType: Int, values: Map[String, Int]) extends AbstractAVPDictionaryItem(code, name, diameterType){
  override def toString() = {s"[code: $code, name: $name, type: $diameterType, values: $values]"}
}

// Custom serializer
class AVPDictionaryItemSerializer extends CustomSerializer[AbstractAVPDictionaryItem](implicit formats => (
    {
      // Reads a JSON and returns a AVPDictionaryItem
      case jv: JValue => 
        val diameterType = (jv \ "type").extract[String]
        val code = (jv \ "code").extract[Int]
        val name = (jv \ "name").extract[String]
        if(diameterType == "OctetString") new AVPDictionaryItem(code, name, AVPDictionaryItem.OCTETSTRING)
        else if(diameterType == "Integer32") new AVPDictionaryItem(code, name, AVPDictionaryItem.INTEGER_32)
        else if(diameterType == "Integer64") new AVPDictionaryItem(code, name, AVPDictionaryItem.INTEGER_64)
        else if(diameterType == "Unsigned32") new AVPDictionaryItem(code, name, AVPDictionaryItem.UNSIGNED_32)       
        else if(diameterType == "Unsigned64") new AVPDictionaryItem(code, name, AVPDictionaryItem.UNSIGNED_64)      
        else if(diameterType == "Float32") new AVPDictionaryItem(code, name, AVPDictionaryItem.FLOAT_32) 
        else if(diameterType == "Float64") new AVPDictionaryItem(code, name, AVPDictionaryItem.FLOAT_64)
        else if(diameterType == "Address") new AVPDictionaryItem(code, name, AVPDictionaryItem.ADDRESS)
        else if(diameterType == "Time") new AVPDictionaryItem(code, name, AVPDictionaryItem.TIME)
        else if(diameterType == "UTF8String") new AVPDictionaryItem(code, name, AVPDictionaryItem.UTF8STRING)
        else if(diameterType == "DiamIdent") new AVPDictionaryItem(code, name, AVPDictionaryItem.DIAMETERIDENTITY)
        else if(diameterType == "DiameterURI") new AVPDictionaryItem(code, name, AVPDictionaryItem.DIAMETERURI)
        else if(diameterType == "IPFilterRule") new AVPDictionaryItem(code, name, AVPDictionaryItem.IPFILTERRULE)
        else if(diameterType == "Enumerated") new EnumeratedDictionaryItem(code, name, AVPDictionaryItem.ENUMERATED, (jv \"enumValues").extract[Map[String, Int]])
        else if(diameterType == "Grouped") new GroupedDictionaryItem(code, name, AVPDictionaryItem.GROUPED, (jv \"group").extract[Map[String, GroupedProperties]])
        else if(diameterType == "IPv4Address") new AVPDictionaryItem(code, name, AVPDictionaryItem.RADIUS_IPV4ADDRESS)
        else if(diameterType == "IPv6Address") new AVPDictionaryItem(code, name, AVPDictionaryItem.RADIUS_IPV6ADDRESS)
        else if(diameterType == "IPv6Prefix") new AVPDictionaryItem(code, name, AVPDictionaryItem.RADIUS_IPV6PREFIX)
        else throw new java.text.ParseException("Invalid diameter type " + diameterType, 0)
    },
    {
      // Reads a AVPDictionaryItem and returns a JSON
      case v : AbstractAVPDictionaryItem => 
        // Not used
        JObject()
    },
))

// Applications
class AVPNameWithBounds(val name: String, val bounds: GroupedProperties)
// Request or Response
class RoR(val avpList: List[AVPNameWithBounds])
class Request(avpList: List[AVPNameWithBounds]) extends RoR(avpList)
class Response(avpList: List[AVPNameWithBounds]) extends RoR(avpList)
class Command(val code: Int, val name: String, val request: Request, val response: Response)
class Application(val code: Int, val name: String, val commands: List[Command])

// A RequestOrResponse is a JSON object with attribute names as properties, and
// Bounds as values
class RoRSerializer extends CustomSerializer[RoR](implicit formats => (
    {
      case jv: JValue =>
        val l = for {
          (name, bounds) <- jv.extract[Map[String, JValue]]
        } yield new AVPNameWithBounds(name, bounds.extract[GroupedProperties])
        
        new RoR(l.toList)
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
  
  implicit val jsonFormats = DefaultFormats + new AVPDictionaryItemSerializer + new RoRSerializer
  
  // Holds a map (vendorId, code --> DictionaryItem)
  val avpMapByCode = for {
    (vendorId, vendorDictItems) <- (dictionaryJson \ "avp").extract[Map[String, JArray]]
    jVendorDictItem <- vendorDictItems.arr
    vendorDictItem = jVendorDictItem.extract[AbstractAVPDictionaryItem]
  } yield ((vendorId.toInt, vendorDictItem.code) -> vendorDictItem)
    
  // Holds a map (avpName --> DictionaryItem)
  val avpMapByName = for {
    (vendorId, vendorDictItems) <- (dictionaryJson \ "avp").extract[Map[String, JArray]]
    jVendorDictItem <- vendorDictItems.arr
    vendorDictItem = jVendorDictItem.extract[AbstractAVPDictionaryItem]
    vendorName = if(vendorId=="0") "" else (dictionaryJson \ "vendor" \ vendorId.toString)+"-"
  } yield (vendorName + vendorDictItem.name -> vendorDictItem)
  
  val j = ((dictionaryJson \ "applications")).extract[List[Application]]
 
  // Holds a map (appName --> Application)
  val appMapByCode = for {
    application <- (dictionaryJson \ "applications").extract[List[Application]]
  } yield (application.code -> application)
  
  def show() = println(appMapByCode) 

}

