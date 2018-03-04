package yaas.radius.coding

import java.nio.ByteOrder
import akka.util.{ByteString, ByteStringBuilder, ByteIterator}
import scala.collection.immutable.Queue

import yaas.util.UByteString
import yaas.dictionary._


object RadiusAVP {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN 
    
  val ipv6PrefixRegex = """(.+)/([0-9]+)""".r
    
  def apply(bytes: ByteString) : RadiusAVP[Any] = {
    // AVP Header is
    //    code: 1 byte
    //    length: 1 byte
    //    value
    //    If code == 26
    //      vendorId: 4 bytes
    //      code: 1 byte
    //      length: 1 byte
    //      value
        
    val it = bytes.iterator
        
    // Header
    var code = UByteString.fromUnsignedByte(it.getByte)
    var lastIndex = UByteString.fromUnsignedByte(it.getByte)
    val vendorId = if(code == 26) UByteString.getUnsigned32(it).toInt else 0
    val dataOffset = if (vendorId == 0) 2 else 8 
    if(vendorId != 0) {
      code = UByteString.fromUnsignedByte(it.getByte)
      lastIndex = UByteString.fromUnsignedByte(it.getByte) + 8
    }
    
    // Value
    val data = bytes.slice(dataOffset, lastIndex)
    RadiusDictionary.avpMapByCode.get(vendorId, code).map(_.radiusType).getOrElse(RadiusTypes.NONE) match {
      case RadiusTypes.STRING => new StringRadiusAVP(code, vendorId, data)
      case RadiusTypes.OCTETS => new OctetsRadiusAVP(code, vendorId, data)
      case RadiusTypes.INTEGER => new IntegerRadiusAVP(code, vendorId, data)
      case RadiusTypes.TIME=> new TimeRadiusAVP(code, vendorId, data)
      case RadiusTypes.ADDRESS => new AddressRadiusAVP(code, vendorId, data)
      case RadiusTypes.IPV6ADDR => new IPv6AddressRadiusAVP(code, vendorId, data)
      case RadiusTypes.IPV6PREFIX => new IPv6PrefixRadiusAVP(code, vendorId, data)
      case RadiusTypes.IFID => new InterfaceIdRadiusAVP(code, vendorId, data)
      case RadiusTypes.INTEGER64 => new Integer64RadiusAVP(code, vendorId, data)
      case RadiusTypes.NONE => new UnknownRadiusAVP(code, vendorId, data)
      }
   }
}

abstract class RadiusAVP[+A](val code: Int, val vendorId: Int, val value: A){
  
  implicit val byteOrder = RadiusAVP.byteOrder 
  
  def getBytes: ByteString = {
    // AVP Header is
    //    code: 1 byte
    //    length: 1 byte
    //    value
    //    If code == 26
    //      vendorId: 4 bytes
    //      code: 1 byte
    //      length: 1 byte
    //      value
    
    val builder = new ByteStringBuilder()
    // Need to do this first
    val payloadBytes = getPayloadBytes
    
    if(vendorId == 0){
      // Code
      builder.putByte(UByteString.toUnsignedByte(code))
      // Length
      UByteString.putUnsignedByte(builder, 2 + payloadBytes.length)
      // Value
      builder.append(payloadBytes)
    }
    else {
      // Code
      builder.putByte(UByteString.toUnsignedByte(26))
      // Length
      UByteString.putUnsignedByte(builder, 8 + payloadBytes.length)
      // VendorId
      UByteString.putUnsigned32(builder, vendorId)
      // Code
      builder.putByte(UByteString.toUnsignedByte(code))
      // Length
      UByteString.putUnsignedByte(builder, 2 + payloadBytes.length)
      // Value
      builder.append(payloadBytes)
    }
    
    builder.result
  }
  
  // Serializes the payload only
  def getPayloadBytes: ByteString

	// To be overriden in concrete classes
	def stringValue = value.toString
	
	override def equals(other: Any): Boolean = {
    other match {
      case x: RadiusAVP[Any] =>
        if(x.code != code || x.vendorId != vendorId || !x.value.equals(value)) false else true
      case _ => false
    }
  }
  
  def pretty: String = {
    val dictItem = RadiusDictionary.avpMapByCode.getOrElse((vendorId, code), RadiusAVPDictItem(0, 0, "UNKNOWN", RadiusTypes.NONE, false, None, None))
    val attrName = dictItem.name
    val attrValue = stringValue
    
    s"[$attrName = $attrValue]"
  }
}

class UnknownRadiusAVP(code: Int, vendorId: Int, value: List[Byte]) extends RadiusAVP[List[Byte]](code, vendorId, value) {
	// Secondary constructor from bytes
	def this(code: Int, vendorId: Int, bytes: ByteString){
		this(code, vendorId, bytes.toList)
	}

	def getPayloadBytes = {
			ByteString.fromArray(value.toArray)
	}

	override def stringValue = {
			new String(value.toArray, "UTF-8")
	}
}

class StringRadiusAVP(code: Int, vendorId: Int, value: String) extends RadiusAVP[String](code, vendorId, value) {
	// Secondary constructor from bytes
	def this(code: Int, vendorId: Int, bytes: ByteString){
		this(code, vendorId, bytes.decodeString("UTF-8"))
	}

	def getPayloadBytes = {
			ByteString.fromString(value, "UTF-8")
	}

	override def stringValue = {
			value
	}
}

class OctetsRadiusAVP(code: Int, vendorId: Int, value: List[Byte]) extends RadiusAVP[List[Byte]](code, vendorId, value) {
	// Secondary constructor from bytes
	def this(code: Int, vendorId: Int, bytes: ByteString){
		this(code, vendorId, bytes.toList)
	}

	def getPayloadBytes = {
			ByteString.fromArray(value.toArray)
	}

	override def stringValue = {
			new String(value.toArray, "UTF-8")
	}
}

class IntegerRadiusAVP(code: Int, vendorId: Int, value: Long) extends RadiusAVP[Long](code, vendorId, value) {
	// Secondary constructor from bytes
	def this(code: Int, vendorId: Int, bytes: ByteString)(implicit byteOrder: ByteOrder){
		this(code, vendorId, UByteString.getUnsigned32(bytes))
	}

	def getPayloadBytes = {
    UByteString.putUnsigned32(new ByteStringBuilder, value).result
	}

	override def stringValue = {
    value.toString
	}
}

class AddressRadiusAVP(code: Int, vendorId: Int, value: java.net.InetAddress) extends RadiusAVP[java.net.InetAddress](code, vendorId, value) {
	// Secondary constructor from bytes
	def this(code: Int, vendorId: Int, bytes: ByteString)(implicit byteOrder: ByteOrder){
		this(code, vendorId, java.net.InetAddress.getByAddress(bytes.toArray))
	}

	def getPayloadBytes = {
    new ByteStringBuilder().putBytes(value.getAddress).result
	}

	override def stringValue = {
    value.getHostAddress
	}
}

class TimeRadiusAVP(code: Int, vendorId: Int, value: java.util.Date) extends RadiusAVP[java.util.Date](code, vendorId, value) {
	// Secondary constructor from bytes
	def this(code: Int, vendorId: Int, bytes: ByteString)(implicit byteOrder: ByteOrder){
		this(code, vendorId, new java.util.Date(UByteString.getUnsigned32(bytes) * 1000))
	}

	def getPayloadBytes = {
    UByteString.putUnsigned32(new ByteStringBuilder, value.getTime / 1000).result
	}

	override def stringValue = {
    val sdf = new java.text.SimpleDateFormat("yyyy-MM-dd hh:mm:ss")
    sdf.format(value)
	}
}

class IPv6AddressRadiusAVP(code: Int, vendorId: Int, value: java.net.InetAddress) extends RadiusAVP[java.net.InetAddress](code, vendorId, value) {
	// Secondary constructor from bytes
	def this(code: Int, vendorId: Int, bytes: ByteString)(implicit byteOrder: ByteOrder){
		this(code, vendorId, java.net.InetAddress.getByAddress(bytes.toArray))
	}

	def getPayloadBytes = {
    new ByteStringBuilder().putBytes(value.getAddress).result
	}

	override def stringValue = {
    value.getHostAddress
	}
}

class IPv6PrefixRadiusAVP(code: Int, vendorId: Int, value: String) extends RadiusAVP[String](code, vendorId, value) {
  // Secondary constructor from bytes
  def this(code: Int, vendorId: Int, bytes: ByteString){
    this(code, vendorId, {
      // rfc3162
      val it = bytes.iterator
      val prefixLen = UByteString.getUnsignedByte(it.drop(1)) // Ignore the first byte (reserved) and read the second, which is the prefix length
      val prefix = java.net.InetAddress.getByAddress(it.getBytes(it.len).padTo[Byte, Array[Byte]](16, 0))
      prefix.getHostAddress + "/" + prefixLen
    })
  }
  
  def getPayloadBytes = {
    val builder = new ByteStringBuilder
    builder.putByte(0)
    for(m <- RadiusAVP.ipv6PrefixRegex.findFirstMatchIn(value)){
      UByteString.putUnsignedByte(builder, m.group(2).toInt)
      builder.putBytes(java.net.InetAddress.getByName(m.group(1)).getAddress);
    }
    builder.result
  }
  
  override def stringValue = {
    value
  }
}

// TODO: Check that the List has 8 bytes
class InterfaceIdRadiusAVP(code: Int, vendorId: Int, value: List[Byte]) extends RadiusAVP[List[Byte]](code, vendorId, value) {
	// Secondary constructor from bytes
	def this(code: Int, vendorId: Int, bytes: ByteString){
		this(code, vendorId, bytes.toList)
	}

	def getPayloadBytes = {
			ByteString.fromArray(value.toArray)
	}

	override def stringValue = {
			new String(value.toArray, "UTF-8")
	}
}

// Values bigger than 2^63 are coded / decoded incorrectly
class Integer64RadiusAVP(code: Int, vendorId: Int, value: Long) extends RadiusAVP[Long](code, vendorId, value) {
	// Secondary constructor from bytes
	def this(code: Int, vendorId: Int, bytes: ByteString)(implicit byteOrder: ByteOrder){
		this(code, vendorId, UByteString.getUnsigned64(bytes))
	}

	def getPayloadBytes = {
    UByteString.putUnsigned64(new ByteStringBuilder, value).result
	}

	override def stringValue = {
    value.toString
	}
}

object RadiusPacket {
  implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN
  
  val AUTH_REQUEST = 1
  val AUTH_ACCEPT = 2
  val AUTH_REJECT = 3
  val ACCT_REQUEST = 4
  val ACCT_RESPONSE = 5
  
  def apply(bytes: ByteString) : RadiusPacket = {
    // code: 1 byte
    // identifier: 1 byte
    // length: 2 byte
    // authtenticator: 16 octets
    // AVPs
    
    val it = bytes.iterator
    val code = UByteString.getUnsignedByte(it)
    val identifier = UByteString.getUnsignedByte(it)
    UByteString.getUnsignedShort(it) // length. No use
    val authenticator = it.getBytes(16)
    
    def appendAVPsFromByteIterator(acc: Queue[RadiusAVP[Any]]) : Queue[RadiusAVP[Any]] = {
  		if(it.isEmpty) acc
  		else {
  		  // Iterator to get the bytes of the AVP
  			val clonedIt = it.clone()
  			// Get AVP length, discarding the previous bytes
  			it.getByte  // code
				val length = UByteString.getUnsignedByte(it)
				// Skip until next AVP, with padding
				it.drop(length - 2)
				
				appendAVPsFromByteIterator(acc :+ RadiusAVP(clonedIt.getByteString(length)))
  		}
    }
    
    new RadiusPacket(code, identifier, authenticator, appendAVPsFromByteIterator(Queue()))
  }
  
  def dencrypt1(authenticator: Array[Byte], secret: String, value: Array[Byte]) : Array[Byte] = {
    
    val valueLength = value.length
    val aSecret = secret.getBytes("UTF-8").toArray
    val aValue = value.padTo[Byte, Array[Byte]](16, 0)
    val aAuthenticator = authenticator
    
    def appendChunk(prevChunk: Array[Byte], authenticator: Array[Byte], secret: Array[Byte], v: Array[Byte]) : Array[Byte] = {
      if(prevChunk.length == v.length) prevChunk // If have already encrypted all bytes, we are finished
      else {
        val b = if(prevChunk.length == 0) md5(secret ++ authenticator) else md5(secret ++ prevChunk.slice(prevChunk.length - 16, prevChunk.length)) // Last 16 bytes
        val p = v.slice(prevChunk.length, prevChunk.length + 16)
        
        // Next byte of the output is the xor of b and the last added chunk, and call appendChunk again
        appendChunk(prevChunk ++ b.zip(p).map{case (x, y) => (x ^ y).toByte}, authenticator, secret, v)
      }
    }
    
    appendChunk(Array(), aAuthenticator, aSecret, aValue).slice(0, valueLength)
    
    /** RFC 2685
		b1 = MD5(S + RA)       c(1) = p1 xor b1
		b2 = MD5(S + c(1))     c(2) = p2 xor b2
                .                       .
                .                       .
		bi = MD5(S + c(i-1))   c(i) = pi xor bi

		The String will contain c(1)+c(2)+...+c(i) where + denotes
		concatenation.
     
     * */
  }
  
  def md5(v: Array[Byte]) = {
    java.security.MessageDigest.getInstance("MD5").digest(v)
  }
  
  def newAuthenticator = {
    val firstPart = (Math.random() * 9223372036854775807L).toLong
    val secondPart = (Math.random() * 9223372036854775807L).toLong
    new ByteStringBuilder().putLong(firstPart).putLong(secondPart).result.toArray
  }
}

class RadiusPacket(val code: Int, val identifier: Int, val authenticator: Array[Byte], var avps: Queue[RadiusAVP[Any]]){
  
  implicit val byteOrder = ByteOrder.BIG_ENDIAN  
  
  def getBytes : ByteString = {
    // code: 1 byte
    // identifier: 1 byte
    // length: 2: 2 byte
    // authtenticator: 16 octets
    
    val avpMap = RadiusDictionary.avpMapByCode
    val builder = new ByteStringBuilder()
    
    UByteString.putUnsignedByte(builder, code)
    UByteString.putUnsignedByte(builder, identifier)
    // length will be patched later
    builder.putShort(0)
    // Authenticator
    builder.putBytes(authenticator)
    for(avp <- avps){
      // use avpMap to encrypt
      builder.append(avp.getPayloadBytes)
    }
    
    val result = builder.result
    // Write length  
    result.patch(2, new ByteStringBuilder().putShort(result.length).result, 2)
  }
  
  def getResponseBytes: ByteString = {
    --
  }
}

