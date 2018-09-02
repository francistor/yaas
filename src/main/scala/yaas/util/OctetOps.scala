package yaas.util

object OctetOps {
  
	def octetsToString(octets: List[Byte]): String = {
    octets.map{b =>  "%02X".format(b)}.mkString("")
  }                                              
  
  def stringToOctets(str: String): List[Byte] = {
    (for {
      i <- 0 to str.length - 1 if i % 2 == 0
      v = Integer.parseInt(str.substring(i, i+2), 16)
    } yield v.toByte).toList
  } 
  
  def octetsToUTF8String(octets: List[Byte]): String = {
    new String(octets.toArray, "UTF-8")
  }      
  
  def UTF8StringToBytes(str: String): List[Byte] = {
    str.getBytes("UTF-8").toList
  }
  
  def fromUTF8ToHex(str: String) : String = octetsToString(UTF8StringToBytes(str))
  
  def fromHexToUTF8(str: String) : String = octetsToUTF8String(stringToOctets(str))
  
}