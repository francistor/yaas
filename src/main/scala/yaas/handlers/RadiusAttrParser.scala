package yaas.handlers

import org.json4s.JValue
import org.json4s.JsonAST.{JArray, JField, JObject}
import yaas.coding.RadiusConversions.JField2RadiusAVP
import yaas.coding.{RadiusAVP, RadiusPacket}
import yaas.config.ConfigManager._

object RadiusAttrParser {

  /**

   *
   * "radiusAttrs":[
   *    {"Service-Type": "Framed"}
   *  ],
   */

  /**
   * Returns the list of radius attributes from the specified object (jValue) for the specified key and
   * property (typically, radiusAttrs or nonOverridableRadiusAttrs).
   *
   * @param jValue the JSON to use
   * @param key name of the key to look for
   * @param propName property in the key to look for
   * @param withDefault if true, the key "DEFAULT" is used if the one specified is not found
   * @return
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
  
  /**
   * Gets all the values of the "attrName"
   */
  def getListFromPair(radiusPacket: RadiusPacket, keyName: String, attrName: String): List[String] = {
    
    val startOfAttr = keyName + "="
    val avPairAttrs = radiusPacket >>+ attrName
    avPairAttrs.flatMap{attr => 
      val kv = attr.stringValue.split("=")
      val key = kv(0)
      if(key == keyName && kv.length > 1) List(kv(1)) else List()
    }
  }
  
  /**
   * Gets the first value of the "attrName" or None
   */
  def getFromPair(radiusPacket: RadiusPacket, keyName: String, attrName: String): Option[String] = {
    val values = getListFromPair(radiusPacket, keyName, attrName)
    if(values.isEmpty) None else Some(values.head)
  }
  
  def getFromCiscoAVPair(radiusPacket: RadiusPacket, keyName: String) = getFromPair(radiusPacket, keyName, "Cisco-AVPair")
  def getFromClass(radiusPacket: RadiusPacket, keyName: String) = getFromPair(radiusPacket, keyName, "Class")
  
}