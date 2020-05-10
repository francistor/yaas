package yaas.handlers

import org.json4s.JValue
import org.json4s.JsonAST.{JArray, JField, JNothing, JObject, JString}
import yaas.coding.RadiusConversions._
import yaas.coding.{RadiusAVP, RadiusPacket}
import yaas.config.ConfigManager._

object RadiusConfigUtils {

  /**
   *
   *
   * "radiusAttrs":[
   * {"Service-Type": "Framed"}
   * ],
   */

  /**
   * Returns the list of radius attributes from the specified configuration object (jValue) for the specified key and
   * property (typically, radiusAttrs or nonOverridableRadiusAttrs).
   *
   * @param jValue      the JSON to use
   * @param key         name of the key to look for
   * @param propName    property in the key to look for
   * @param withDefault if true, the key "DEFAULT" is used if the one specified is not found
   * @return a list of Radius Attributes
   */
  def getRadiusAttrs(jValue: JValue, key: Option[String], propName: String, withDefault: Boolean = true): List[RadiusAVP[Any]] = {
    val nv = key match {
      case Some(v) => jValue.forKey(v, "DEFAULT")
      case None => jValue
    }

    for {
      JArray(attrs) <- nv \ propName
      JObject(attr) <- attrs
      JField(k, v) <- attr
    } yield JField2RadiusAVP((k, v))
  }
}

object RadiusPacketUtils {

  /**
   * Get all the values of the radius attribute <code>attrName</code> whose values are of the form
   * <code>keyName=value</code>, returning the values.
   * @param radiusPacket the radius packet
   * @param keyName the inner attribute name, like in <code>keyName=value</code>
   * @param attrName the outer attribute name (e.g. Cisco-AVPair)
   * @return a list of values
   */
  def getListFromPair(radiusPacket: RadiusPacket, keyName: String, attrName: String): List[String] = {
    
  (radiusPacket >>+ attrName).flatMap{attr =>
      val kv = attr.stringValue.split("""=""")
      if(kv.length > 1 && kv(0) == keyName ) List(kv(1)) else List()
    }
  }

  /**
   *
   * @param radiusPacket the radius packet
   * @param keyName the inner attribute name
   * @param attrName the outer attribute name
   * @return the first value for attrName and keyName, or None if does not exist
   */

  def getFromPair(radiusPacket: RadiusPacket, keyName: String, attrName: String): Option[String] = {
    getListFromPair(radiusPacket, keyName, attrName).headOption
  }

  /**
   * Extracts a single instance of the value of a Cisco-AVPair
   *
   * @param radiusPacket the radius packet
   * @param keyName the inner attribute name
   * @return String Option with the first value
   */
  def getFromCiscoAVPair(radiusPacket: RadiusPacket, keyName: String): Option[String] = getFromPair(radiusPacket, keyName, "Cisco-AVPair")

  /**
   * Extract a single instance of the value of a Class attribute coded as <code>keyName=value</code>
   *
   * @param radiusPacket the radius packet
   * @param keyName the inner attribute name
   * @return
   */
  def getFromClass(radiusPacket: RadiusPacket, keyName: String): Option[String] = getFromPair(radiusPacket, keyName, "Class")  /**
   * Like <code>getListFromPair</code> but getting only the first one or None.
   *
   * Gets the first value of the <code>attrName</code> with key <code>keyName</code> or None
   */

  /**
   * Evaluates the specified filter.
   *
   * The first element of the array may be a Radius Attribute or a Cookie name
   *
   * {
   * "and": [
   *    ["NAS-IP-Address", "present"],
   *    ["NAS-IP-Address", "contains", "1.1"],
   *    ["NAS-IP-Address", "isNot", "10.0.0.0"],
   *    ["NAS-IP-Address", "matches", ".+"],
   *    ["NAS-Port", "isNot", 2],
   *    ["Framed-IP-Address", "notPresent"]
   * ],
   * "or":[
   *    ["Class", "contains", "not-the-class"],
   *    ["Class", "is", "class!"],
   *    ["NAS-Port", "is", 2]
   * ]
   * }
   *
   * @param radiusPacket the radius packet
   * @param checkSpec the filter to apply
   * @param operation "and" or "or". Implicit "and" if not specified
   * @return true or false
   */
  def checkRadiusPacket(radiusPacket: RadiusPacket, checkSpec: Option[JValue], operation: String = "and"): Boolean = {
    checkSpec match {
      case None => true

      // Evaluate the inner filter objects
      case Some(JObject(propValues)) => propValues.map { case (op, f) => checkRadiusPacket(radiusPacket, Some(f), op) }.reduce {
        (r1, r2) => if (operation == "and") r1 && r2 else r1 || r2
      }
      // Evaluate the specification items
      case Some(JArray(arr)) => (for {
        JArray(elems) <- arr
        attrNameOrCookie = elems.head.extract[String]
        packetCookieValue = radiusPacket.getCookie(attrNameOrCookie)
        targetValue = elems.last.extract[String]
        res = elems(1) match {
          case JString("present") => (radiusPacket >> attrNameOrCookie).nonEmpty || packetCookieValue.nonEmpty
          case JString("notPresent") => (radiusPacket >> attrNameOrCookie).isEmpty || packetCookieValue.isEmpty
          case JString("is") => (radiusPacket >>* attrNameOrCookie) == targetValue || packetCookieValue.contains(targetValue)
          case JString("isNot") => (radiusPacket >>* attrNameOrCookie) != targetValue || ! packetCookieValue.contains(targetValue)
          case JString("contains") => (radiusPacket >>* attrNameOrCookie).contains(targetValue) || packetCookieValue.contains(targetValue)
          case JString("matches") => (radiusPacket >>* attrNameOrCookie).matches(targetValue) || packetCookieValue.exists(_.matches(targetValue))
          case _ => false
        }
      } yield res).reduce {
        (r1, r2) => if (operation == "and") r1 && r2 else r1 || r2
      }

      case _ => false
    }
  }

  /**
   * Does the manipulations specified in the JSON map in the radius attributes.
   *
   * Sections may be "allow", "remove" and "force".
   * If allow is specified, "remove" makes no sense but will be executed anyway.
   *
   * {
   *    "allow": ["NAS-IP-Address", "NAS-Port", "Class"],
   *    "remove": ["Framed-IP-Address", "NAS-Port"],
   *    "force": [
   * ["NAS-IP-Address", "1.1.1.1"]
   * ]
   * }
   *
   * @param radiusPacket the radius packet. It will be modified, not copied
   * @param mapOption map to apply
   * @return the modified radius packet
   */
  def filterAttributes(radiusPacket: RadiusPacket, mapOption: Option[JValue]): RadiusPacket = {

    mapOption match {
      case None =>
      case Some(map) =>
        // Allow section
        if(map \ "allow" != JNothing) {
          val allowedAttributes = for {
            JArray(allowAttrs) <- map \ "allow"
            JString(allowAttr) <- allowAttrs
          } yield allowAttr

          radiusPacket.avps.map(_.getName).filter(!allowedAttributes.contains(_)).map(radiusPacket.removeAll)
        }

        // Remove section
        val removeAttributes =  for {
          JArray(removeAttrs) <- map \ "remove"
          JString(removeAttr) <- removeAttrs
        } yield removeAttr

        removeAttributes.map(radiusPacket.removeAll)

        // Force Section
        for {
          JArray(forceSpecs) <- map \ "force"
          JArray(forceSpec) <- forceSpecs
          JString(attrName) = forceSpec(0)
          JString(attrValue) = forceSpec(1)
        } radiusPacket.removeAll(attrName).put((attrName, attrValue))
    }

    radiusPacket
  }

  case class CopyTarget(targetName: String, radiusProxyGroupName: String, checker: Option[String], filter: Option[String])
  
}