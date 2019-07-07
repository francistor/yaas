package yaas.handlers

import yaas.coding.RadiusPacket

object RadiusAttrParser {
  
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