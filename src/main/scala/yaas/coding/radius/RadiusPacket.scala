package yaas.coding.radius

import java.nio.ByteOrder
import akka.util.{ByteString, ByteStringBuilder, ByteIterator}
import scala.collection.immutable.Queue

import yaas.util.UByteString
import yaas.dictionary._


object RadiusAVP {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN 
    
  val ipv6PrefixRegex = """(.+)/([0-9]+)""".r
    
  def apply(bytes: ByteString, authenticator: Array[Byte], secret: String) : RadiusAVP[Any] = {
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
    val dictItem =  RadiusDictionary.avpMapByCode.get(vendorId, code)
    val radiusType = dictItem.map(_.radiusType).getOrElse(RadiusTypes.NONE)
    radiusType match {
      case RadiusTypes.STRING => new StringRadiusAVP(code, vendorId, data)
      case RadiusTypes.OCTETS => 
        if(dictItem.map(_.encrypt).getOrElse(0) == 1) 
          new OctetsRadiusAVP(code, vendorId, RadiusPacket.decrypt1(authenticator, secret, data.toArray))
        else new OctetsRadiusAVP(code, vendorId, data)
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
  
  def getBytes(authenticator: Array[Byte], secret: String): ByteString = {
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
    
    // use avpMap to encrypt
    val avpMap = RadiusDictionary.avpMapByCode
    val payloadBytes = 
      if(avpMap.get(vendorId, code).map(_.encrypt) == Some(1))
        ByteString.fromArray(RadiusPacket.encrypt1(authenticator, secret, getPayloadBytes.toArray))
      else getPayloadBytes
    
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
    val dictItem = RadiusDictionary.avpMapByCode.getOrElse((vendorId, code), RadiusAVPDictItem(0, 0, "UNKNOWN", RadiusTypes.NONE, 0, false, None, None))
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
	
	def this(code: Int, vendorId: Int, bytes: Array[Byte]){
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
  
  val ACCESS_REQUEST = 1
  val ACCESS_ACCEPT = 2
  val ACCESS_REJECT = 3
  val ACCOUNTING_REQUEST = 4
  val ACCOUNTING_RESPONSE = 5
  val DISCONNECT_REQUEST = 40
  val DISCONNECT_ACK = 41
  val DISCONNECT_NAK = 42
  val COA_REQUEST = 43
  val COA_ACK = 44
  val COA_NAK = 45
  
  def apply(bytes: ByteString, secret: String) : RadiusPacket = {
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
				
				appendAVPsFromByteIterator(acc :+ RadiusAVP(clonedIt.getByteString(length), authenticator, secret))
  		}
    }
    
    new RadiusPacket(code, identifier, authenticator, appendAVPsFromByteIterator(Queue()))
  }
  
  // Generates a new radius packet with the specified code. The identifier will be replaced before being sent
  // and the authenticator is new
  def request(code: Int) = {
    new RadiusPacket(code, 0 /* to be replaced */, RadiusPacket.newAuthenticator, Queue[RadiusAVP[Any]]())
  }
  
  // Generates a new radius packet as response for the specified radius request
  def reply(radiusPacket: RadiusPacket, isSuccess : Boolean = true) = {
    val code = if(isSuccess) radiusPacket.code + 1 else radiusPacket.code + 2
    new RadiusPacket(code, radiusPacket.identifier, radiusPacket.authenticator, Queue[RadiusAVP[Any]]())
  }
  
  def encrypt1(authenticator: Array[Byte], secret: String, value: Array[Byte]) : Array[Byte] = {

    def appendChunk(encrypted: Array[Byte], ra: Array[Byte], s: Array[Byte], v: Array[Byte]) : Array[Byte] = {
      val encryptedLen = encrypted.length
      if(encryptedLen == v.length) encrypted // If have already encrypted all bytes, we are finished
      else {
        val b = if(encryptedLen == 0) md5(s ++ ra) else md5(s ++ encrypted.slice(encryptedLen - 16, encryptedLen)) // Last 16 bytes
        val p = v.slice(encryptedLen, encryptedLen + 16)
        val c = b.zip(p).map{case (x, y) => (x ^ y).toByte}
        // Next byte of the output is the xor of b and the last added chunk, and call appendChunk again
        appendChunk(encrypted ++ c, ra, s, v)
      }
    }
    
    val vLen = value.length
    val vLenPadded = if(vLen % 16 == 0) vLen else vLen + (16 - vLen % 16)
    appendChunk(Array(), authenticator, secret.getBytes("UTF-8").toArray, value.padTo[Byte, Array[Byte]](vLenPadded, 0)).slice(0, vLen)

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
  
  def decrypt1(authenticator: Array[Byte], secret: String, value: Array[Byte]) : Array[Byte] = {
    
    def prependChunk(decrypted: Array[Byte], ra: Array[Byte], s: Array[Byte], v: Array[Byte]) : Array[Byte] = {
      val decryptedLen = decrypted.length
      if(decryptedLen == v.length) decrypted // If have already encrypted all bytes, we are finished
      else {
        val chunkIndex = v.length - 16 - decryptedLen // Index of the first element of the v array to be decrypted
        val b = if(chunkIndex == 0) md5(s ++ ra) else md5(s ++ v.slice(chunkIndex - 16, chunkIndex)) 
        val p = v.slice(chunkIndex, chunkIndex + 16)
        val c = b.zip(p).map{case (x, y) => (x ^ y).toByte}
        
        // Next segment of the output is the xor of b and the last added chunk, and call prependChunk again
        prependChunk(c ++ decrypted, ra, s, v)
      }
    }
    
    val vLen = value.length
    val vLenPadded = if(vLen % 16 == 0) vLen else vLen + (16 - vLen % 16)
    prependChunk(Array(), authenticator, secret.getBytes("UTF-8").toArray, value.padTo[Byte, Array[Byte]](vLenPadded, 0)).slice(0, vLen)
  }
  
  def md5(v: Array[Byte]) = {
    java.security.MessageDigest.getInstance("MD5").digest(v)
  }
  
  def newAuthenticator = {
    val firstPart = (Math.random() * 9223372036854775807L).toLong
    val secondPart = (Math.random() * 9223372036854775807L).toLong
    new ByteStringBuilder().putLong(firstPart).putLong(secondPart).result.toArray
  }
  
  def checkAuthenticator(packet: ByteString, reqAuthenticator: Array[Byte], secret: String) = {
    val respAuthenticator = packet.slice(4, 20)
    val patchedPacket = packet.patch(4, reqAuthenticator, 16)
 
    RadiusPacket.md5(patchedPacket.concat(ByteString.fromString(secret, "UTF-8")).toArray).equals(respAuthenticator)
  }
}

class RadiusPacket(val code: Int, var identifier: Int, var authenticator: Array[Byte], var avps: Queue[RadiusAVP[Any]]){
  
  implicit val byteOrder = ByteOrder.BIG_ENDIAN  
  
  def getBytes(secret: String) : ByteString = {
    // code: 1 byte
    // identifier: 1 byte
    // length: 2: 2 byte
    // authtenticator: 16 octets
    
    //val avpMap = RadiusDictionary.avpMapByCode
    val builder = new ByteStringBuilder()
    
    UByteString.putUnsignedByte(builder, code)
    UByteString.putUnsignedByte(builder, identifier)
    // length will be patched later
    builder.putShort(0)
    // Authenticator
    builder.putBytes(authenticator)
    for(avp <- avps){
      builder.append(avp.getBytes(authenticator, secret))
    }
    
    val result = builder.result
    // Write length  
    result.patch(2, new ByteStringBuilder().putShort(result.length).result, 2)
  }
  
  // To be used to generate the radius response
  def getResponseBytes(secret: String): ByteString = {
    val responseBytes = getBytes(secret)
    val responseAuthenticator = RadiusPacket.md5(responseBytes.concat(ByteString.fromString(secret, "UTF-8")).toArray)
    
    // patch authenticator
    //responseBytes.patch(4, new ByteStringBuilder().putBytes(responseAuthenticator).result, 16)
    responseBytes.patch(4, responseAuthenticator, 16)
  }
  
  override def toString() = {
    val codeString = code match {
      case RadiusPacket.ACCESS_REQUEST => "Access-Request"
      case RadiusPacket.ACCESS_ACCEPT => "Access-Accept"
      case RadiusPacket.ACCESS_REJECT => "Access-Reject"
      case RadiusPacket.ACCOUNTING_REQUEST => "Accounting-Request"
      case RadiusPacket.ACCOUNTING_RESPONSE => "Accounting-Response"
      case RadiusPacket.COA_REQUEST => "CoA-Request"
      case RadiusPacket.COA_ACK => "CoA-ACK"
      case RadiusPacket.COA_NAK => "CoA-NAK"
    }
    
    val prettyAVPs = avps.foldRight("")((avp, acc) => acc + avp.pretty + "\n")
    
    s"\n$codeString\n$prettyAVPs"
  }
  
  override def equals(other: Any): Boolean = {
    other match {
      case x: RadiusPacket =>
        if( x.code != code || 
            x.identifier != identifier || 
            !x.authenticator.sameElements(authenticator) ||
            !x.avps.equals(avps)) false else true
      case _ => 
        false
    }
  }
}

