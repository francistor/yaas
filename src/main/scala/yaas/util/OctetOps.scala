package yaas.util

object OctetOps {
  
  // Given a List of bytes, returns a string of the form 0x(hex chars)
	def octetsToString(octets: List[Byte]): String = {
    "0x" +  octets.map{b =>  "%02X".format(b)}.mkString("")
  }
	
	// Given a List of bytes, returns a string, parsing as UTF8
	def octetsToUTF8String(octets: List[Byte]): String = {
    new String(octets.toArray, "UTF-8")
  } 
  
	// If the format is 0x(hex chars), parses as hexadecimal values (input must be even number of chars)
	// otherwise tries to parse it as a UTF-8 string
  def stringToOctets(str: String): List[Byte] = {
    if(str.startsWith("0x")) UTF8StringToOctets(str)
    else (for {
      i <- 0 to str.length - 1 if i % 2 == 0
      v = Integer.parseInt(str.substring(i, i+2), 16)
    } yield v.toByte).toList
  }      
  
  // To be used to force the input format to UTF-8
  def UTF8StringToOctets(str: String): List[Byte] = {
    str.getBytes("UTF-8").toList
  }
  
  
  def fromUTF8ToHex(str: String) : String = octetsToString(UTF8StringToOctets(str))
  
  def fromHexToUTF8(str: String) : String = octetsToUTF8String(stringToOctets(str))
  
}