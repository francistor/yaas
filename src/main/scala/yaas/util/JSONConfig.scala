package yaas.util

import org.json4s.JValue
import org.json4s.JsonAST.{JArray, JField, JObject}
import yaas.coding.RadiusConversions.JField2RadiusAVP
import yaas.coding.RadiusAVP

object JSONConfig {

  /**
 * Returns the list of radius attributes from the specified object (jValue) for the specified key and
 * property (typically, radiusAttrs or nonOverridableRadiusAttrs).
 *
 * "radiusAttrs":[
 *    {"Service-Type": "Framed"}
 *  ],
 */
  def getRadiusAttrs(jValue: JValue, key: Option[String], propName: String): List[RadiusAVP[Any]] = {
    val nv = key match {
      case Some(v) => jValue \ v
      case None => jValue
    }

    for {
      JArray(attrs) <- nv \ propName
      JObject(attr) <- attrs
      JField(k, v) <- attr
    } yield JField2RadiusAVP((k, v))
  }

}
