package yaas.radius.coding

import java.nio.ByteOrder
import akka.util.{ByteString, ByteStringBuilder, ByteIterator}
import scala.collection.immutable.Queue

import yaas.util.UByteString
import yaas.dictionary.RadiusDictionary
import yaas.dictionary.RadiusTypes


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
    var length = UByteString.fromUnsignedByte(it.getByte)
    val vendorId = if(code == 26) it.getInt else 0
    val dataOffset = if (vendorId == 0) 2 else 8 
    if(vendorId != 0) {
      code = UByteString.fromUnsignedByte(it.getByte)
      length = UByteString.fromUnsignedByte(it.getByte)
    }
    
    // Value
    val data = bytes.slice(dataOffset, length)
    RadiusDictionary.avpMapByCode.get(vendorId, code).map(_.radiusType).getOrElse(RadiusTypes.NONE) match {
      case RadiusTypes.STRING => new StringRadiusAVP(code, vendorId, bytes)
      case RadiusTypes.OCTETS => new OctetsRadiusAVP(code, vendorId, bytes)
      case RadiusTypes.INTEGER => new IntegerRadiusAVP(code, vendorId, bytes)
      case RadiusTypes.TIME=> new TimeRadiusAVP(code, vendorId, bytes)
      case RadiusTypes.ADDRESS => new AddressRadiusAVP(code, vendorId, bytes)
      case RadiusTypes.IPV6ADDR => new IPv6AddressRadiusAVP(code, vendorId, bytes)
      case RadiusTypes.IPV6PREFIX => new IPv6PrefixRadiusAVP(code, vendorId, bytes)
      case RadiusTypes.IFID => new InterfaceIdRadiusAVP(code, vendorId, bytes)
      case RadiusTypes.INTEGER64 => new Integer64RadiusAVP(code, vendorId, bytes)
      case RadiusTypes.NONE => new UnknownRadiusAVP(code, vendorId, bytes)
      }
   }
}

abstract class RadiusAVP[+A](val code: Int, val vendorId: Int, val value: A){
  
  implicit val byteOrder = RadiusAVP.byteOrder 
  
  val payloadBytes = getPayloadBytes
  
  // Serializes the payload only
  def getPayloadBytes: ByteString

	// To be overriden in concrete classes
	def stringValue = value.toString
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
    val sdf = new java.text.SimpleDateFormat("yyyy-MM-ddThh:mm:ss")
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
      val prefixLen = it.drop(1).getByte // Ignore the first byte (reserved) and read the second, which is the prefix length
      val prefix = java.net.InetAddress.getByAddress(it.getBytes(it.len).padTo[Byte, Array[Byte]](16, 0))
      prefix.getHostAddress + "/" + prefixLen
    })
  }
  
  def getPayloadBytes = {
    val builder = new ByteStringBuilder
    builder.putByte(0)
    for(m <- RadiusAVP.ipv6PrefixRegex.findFirstMatchIn(value)){
      builder.putByte(m.group(2).toByte);
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
    
    val it = bytes.iterator
    val code = UByteString.getUnsignedByte(it)
    val identifier = UByteString.getUnsignedByte(it)
    val length = UByteString.getUnsignedShort(it)
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
				it.drop(length)
				
				appendAVPsFromByteIterator(acc :+ RadiusAVP(clonedIt.getByteString(length)))
  		}
    }
    
    new RadiusPacket(code, identifier, authenticator, appendAVPsFromByteIterator(Queue()))
  }
  
  def dencrypt1(authenticator: Array[Byte], secret: String, value: Array[Byte]){
    
    val aSecret = secret.getBytes("UTF-8").toArray
    val aValue = value.padTo[Byte, Array[Byte]](16, 0)
    val aAuthenticator = authenticator
    
    appendChunk(Array(), aAuthenticator, aSecret, aValue)
    
    def appendChunk(prevChunk: Array[Byte], authenticator: Array[Byte], secret: Array[Byte], v: Array[Byte]) : Array[Byte] = {
      if(prevChunk.length == v.length) prevChunk // If have already encrypted all bytes, we are finished
      else {
        val b = if(prevChunk.length == 0) md5(secret ++ authenticator) else prevChunk.slice(prevChunk.length - 16, prevChunk.length - 1) // Last 16 bytes
        val p = v.slice(prevChunk.length, prevChunk.length + 15)
        
        // Next byte of the output is the xor of b and the last added chunk, and call appendChunk again
        appendChunk(prevChunk ++ b.zip(p).map{case (x, y) => (x ^ y).toByte}, authenticator, secret, v)
      }
    }
    
    
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
  
  def newAuthenticator = (Math.random() * 2147483647).toInt
}

class RadiusPacket(code: Int, identifier: Int, authenticator: Array[Byte], avps: Queue[RadiusAVP[Any]]){
  
  implicit val byteOrder = ByteOrder.BIG_ENDIAN  
  
  def getBytes : ByteString = {
    // code: 1 byte
    // identifier: 1 byte
    // length: 2: 2 byte
    // authtenticator: 16 octets
    
    val builder = new ByteStringBuilder()
    
    UByteString.putUnsignedByte(builder, code)
    UByteString.putUnsignedByte(builder, identifier)
    // length will be patched later
    builder.putShort(0)
    builder.putBytes(authenticator)
    for(avp <- avps) builder.append(avp.getPayloadBytes)
    
    val result = builder.result
    // Write length now   
    result.patch(2, new ByteStringBuilder().putShort(result.length).result, 2)
    
    result
  }
}

