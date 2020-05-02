package yaas.util

import org.json4s.JValue
import org.json4s.JsonAST.{JArray, JField, JObject}
import yaas.config.ConfigManager._
import yaas.coding.RadiusConversions.JField2RadiusAVP
import yaas.coding.RadiusAVP

object JSONConfig {

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

}
