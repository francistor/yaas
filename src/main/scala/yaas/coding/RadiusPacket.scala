package yaas.coding

import java.nio.ByteOrder
import akka.util.{ByteString, ByteStringBuilder, ByteIterator}

import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

import yaas.util.UByteString
import yaas.util.OctetOps
import yaas.dictionary._

class RadiusCodingException(val msg: String) extends java.lang.Exception(msg: String)

/**
 * Builder of Radius AVP from Bytes.
 */
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
    val iCode = UByteString.fromUnsignedByte(it.getByte)
    val iLastIndex = UByteString.fromUnsignedByte(it.getByte)
    val vendorId = if(iCode == 26) UByteString.getUnsigned32(it).toInt else 0
    val dataOffset = if (vendorId == 0) 2 else 8
    val code = if(vendorId == 0) iCode else UByteString.fromUnsignedByte(it.getByte)
    val lastIndex = if(vendorId == 0) iLastIndex else UByteString.fromUnsignedByte(it.getByte) + 6 // TODO: Check this. 8 or 6
    
    // Value
    val data = bytes.slice(dataOffset, lastIndex)
    val dictItem =  RadiusDictionary.avpMapByCode.get(vendorId, code)
    val radiusType = dictItem.map(_.radiusType).getOrElse(RadiusTypes.NONE)
    radiusType match {
      case RadiusTypes.STRING => new StringRadiusAVP(code, vendorId, data)
      case RadiusTypes.OCTETS => 
        if(dictItem.map(_.encrypt).getOrElse(0) == 1) new OctetsRadiusAVP(code, vendorId, RadiusPacket.decrypt1(authenticator, secret, data.toArray))
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

/**
 * Base class for all Radius AVP
 */
abstract class RadiusAVP[+A](val code: Int, val vendorId: Int, val value: A){
  
  implicit val byteOrder = RadiusAVP.byteOrder 
  
  /**
   * Gets the bytes of the serialized attribute.
   * 
   * If the dictionary specifies that the attribute is encripted, the RFC algorithm is applied, which requires the passing 
   * of <code>authenticator</code> and <code>secret</code>.
   * 
   * Relies on the <code>getPayloadBytes</code> method in the concrete classes.
   */
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
      if(avpMap.get(vendorId, code).map(_.encrypt) == Some(1)) ByteString.fromArray(RadiusPacket.encrypt1(authenticator, secret, getPayloadBytes.toArray))
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
  
  /**
   * Serializes the payload only.
   * 
   * To be implemented in concrete classes
   */
  def getPayloadBytes: ByteString

	/**
	 * To be implemented in concrete classes
	 */
	def stringValue = value.toString
	
	/**
	 * Want the stringified AVP be the value, so toString reports only the value
	 */
  override def toString = stringValue
	
  /**
   * To RadiusAVP are equal if the code, vendorId and value are the same.
   */
	override def equals(other: Any): Boolean = {
    other match {
      case x: RadiusAVP[Any] =>
        if(x.code != code || x.vendorId != vendorId || !x.value.equals(value)) false else true
      case _ => false
    }
  }
  
  /**
   * Pretty toString to be used for printing the full RadiusPacket
   */
  def pretty: String = {
    val dictItem = RadiusDictionary.avpMapByCode.getOrElse((vendorId, code), RadiusAVPDictItem(0, 0, "UNKNOWN", RadiusTypes.NONE, 0, false, None, None))
    val attrName = dictItem.name
    val attrValue = stringValue
    
    s"[$attrName = $attrValue]"
  }
  
  /**
   * Returns the name of the attribute as defined in the Radius dictionary
   */
  def getName = {
    RadiusDictionary.avpMapByCode.get((vendorId, code)).map(_.name).getOrElse("UNKNOWN")
  }
  
  /**
   * Returns the type of the attribute as defined in the Radius dictionary.
   */
  def getType = {
    RadiusDictionary.avpMapByCode.get((vendorId, code)).map(_.radiusType).getOrElse(RadiusTypes.NONE)
  }
  
  /**
   * Copies this AVP to a new one.
   */
  def copy = {
    this match {
      case r: UnknownRadiusAVP => new UnknownRadiusAVP(r.code, r.vendorId, r.value)
      case r: OctetsRadiusAVP => new OctetsRadiusAVP(r.code, r.vendorId, r.value)
      case r: StringRadiusAVP => new StringRadiusAVP(r.code, r.vendorId, r.value)
      case r: IntegerRadiusAVP => new IntegerRadiusAVP(r.code, r.vendorId, r.value)
      case r: TimeRadiusAVP => new TimeRadiusAVP(r.code, r.vendorId, r.value)
      case r: AddressRadiusAVP => new AddressRadiusAVP(r.code, r.vendorId, r.value)
      case r: IPv6AddressRadiusAVP => new IPv6AddressRadiusAVP(r.code, r.vendorId, r.value)
      case r: IPv6PrefixRadiusAVP =>  new IPv6PrefixRadiusAVP(r.code, r.vendorId, r.value)
      case r: InterfaceIdRadiusAVP =>  new InterfaceIdRadiusAVP(r.code, r.vendorId, r.value)
      case r: Integer64RadiusAVP => new Integer64RadiusAVP(r.code, r.vendorId, r.value)
    }
  }
}

/**
 * For AVP not found in the dictionary.
 */
class UnknownRadiusAVP(code: Int, vendorId: Int, value: List[Byte]) extends RadiusAVP[List[Byte]](code, vendorId, value) {
	// Secondary constructor from bytes
	def this(code: Int, vendorId: Int, bytes: ByteString){
		this(code, vendorId, bytes.toList)
	}

	def getPayloadBytes = {
    ByteString(value.toArray)
	}

	override def stringValue = {
    OctetOps.octetsToString(value)
	}
}

/**
 * Radius type String UTF-8 encoded
 */
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

/**
 * Radius type OctetString.
 * 
 * The string representation is 0x[Hex encoding]. If a UTF-8 encoded representation is required, use <code>OctetOps.fromHexToUTF8</code>
 */
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
	  OctetOps.octetsToString(value)
	}
}

/**
 * Radius type Integer32
 */
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

/**
 * Radius Type IP Address.
 */
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

/**
 * Radius Type Time (encoded as seconds since the Epoch UTC)
 */
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

/**
 * Radius Type IPv6 address, encoded as 128 bits.
 */
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

/**
 * Radius Type IPv6 prefix. Encoded as 1 byte prefix length, and 16 bytes with prefix.
 * 
 * The value must be provided as <ipv6 bytes>/<prefix-lenght>. <ipv6> in the format required by java <code>InetAddress.getByName</code>, such
 * as 2001:cafe:0:0:0:0:0:0/128
 */
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
/**
 * Radius Type InterfaceId
 */
class InterfaceIdRadiusAVP(code: Int, vendorId: Int, value: List[Byte]) extends RadiusAVP[List[Byte]](code, vendorId, value) {
	// Secondary constructor from bytes
	def this(code: Int, vendorId: Int, bytes: ByteString){
		this(code, vendorId, bytes.toList)
	}

	def getPayloadBytes = {
			ByteString.fromArray(value.toArray)
	}

	override def stringValue = {
			OctetOps.octetsToString(value)
	}
}

/**
 * Radius Type Integer64. 
 * 
 * Values bigger than 2^63 are coded / decoded incorrectly
 */
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

/**
 * Constructor and helper functions for RadiusPackets.
 *   
 */

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
  
  /**
   * Creates a RadiusPacket from the bytes.
   * 
   * If this is a request packet, the authenticator is taken from the bytes and, along with the secret, 
   * allows the decryption of the AVPs. <code>requestAuthenticator</code> is <code>None</code>.
   * 
   * If this is a response packet, the authenticator required for decryption is that of the request, and 
   * <code>requestAuthenticator</code> has a value.
   */
  def apply(bytes: ByteString, requestAuthenticator: Option[Array[Byte]], secret: String): RadiusPacket = {
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
    
    def appendAVPsFromByteIterator(acc: List[RadiusAVP[Any]]) : List[RadiusAVP[Any]] = {
  		if(it.isEmpty) acc
  		else {
  		  // Iterator to get the bytes of the AVP
  			val clonedIt = it.clone()
  			// Get AVP length, discarding the previous bytes
  			it.getByte  // code
				val length = UByteString.getUnsignedByte(it)
				// Skip until next AVP, with padding
				it.drop(length - 2)
				
				appendAVPsFromByteIterator(acc :+ RadiusAVP(clonedIt.getByteString(length), requestAuthenticator.getOrElse(authenticator), secret))
  		}
    }
    
    new RadiusPacket(code, identifier, authenticator, appendAVPsFromByteIterator(List()))
  }
  
  // Generates a new radius packet with the specified code. The identifier and authenticator will be replaced
  // before sending the packet (prepare Method)
  /**
   * Generates an empty request packet with the specified code.
   * 
   * The authenticator will be generated when serializing to bytes.
   * The id is generated just before being sent to the destination
   */
  def request(code: Int) = {
    new RadiusPacket(code, 0 /* to be replaced */, Array[Byte](16) /* To be generated later */, List[RadiusAVP[Any]]())
  }
    
  /**
   * Generates a new radius packet as response for the specified radius request.
   * 
   * The identifier and authenticator are that of the request at this stage (the value in the wired will be calculated according
   * to the rules for the response authenticator).
   * 
   * If isSuccess is true, the code will be added one, and two otherwise
   */
  def response(requestPacket: RadiusPacket, isSuccess : Boolean = true) = {
    val code = if(isSuccess) requestPacket.code + 1 else requestPacket.code + 2
    new RadiusPacket(code, requestPacket.identifier, requestPacket.authenticator, List[RadiusAVP[Any]]())
  }
  
  /**
   * Generates a new failure response radius packet for the specified radius request.
   */
  def responseFailure(requestPacket: RadiusPacket) = response(requestPacket, false)
  
  /**
   * Creates a request packet which is a copy of the received packet.
   * 
   * Id and authenticator will be generated when sending to the wire.
   */
  def proxyRequest(requestPacket: RadiusPacket) = {
    new RadiusPacket(requestPacket.code, 0, Array[Byte](16) /* To be generated later */, requestPacket.avps.map(attr => attr.copy))
  }
  
  /**
   * Creates a response packet for the specified request packet (id and authenticator), but copying the attributes from the
   * response packet in the argument.
   * 
   */
  def proxyResponse(responsePacket: RadiusPacket, requestPacket: RadiusPacket) = {
    new RadiusPacket(responsePacket.code, requestPacket.identifier, requestPacket.authenticator, responsePacket.avps.map(attr => attr.copy))
  }
  
  /**
   * Encrypts the specified value according to the rules in rfc2865, item 5.2.
   */
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
  
  /**
   * Decrypts the specified value according to the rules in rfc2865, item 5.2.
   */
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
  
  /**
   * Wrapper for MD5 generation
   */
  def md5(v: Array[Byte]) = {
    java.security.MessageDigest.getInstance("MD5").digest(v)
  }
  
  /**
   * Generates a new random value to be used as authenticator.
   */
  def newAuthenticator = {
    val firstPart = System.currentTimeMillis()
    val secondPart = (Math.random() * 9223372036854775807L).toLong
    new ByteStringBuilder().putLong(firstPart).putLong(secondPart).result.toArray
  }
  
  /**
   * Check that the authenticator received in the response packet is correct (that is, contains the hash of the 
   * sent values with the request authenticator and secret).
   */
  def checkAuthenticator(packet: ByteString, reqAuthenticator: Array[Byte], secret: String) = {
    val respAuthenticator = packet.slice(4, 20)
    val patchedPacket = packet.patch(4, reqAuthenticator, 16) 
    RadiusPacket.md5(patchedPacket.concat(ByteString.fromString(secret, "UTF-8")).toArray).sameElements(respAuthenticator)
  }
}


/**
 * Represents a radius packet.
 * 
 * In a request packet, identifier and authenticator are calculated when sending to the wire, and thus the values
 * are irrelevant. In a response packet, contain the values for the request.
 * 
 */
class RadiusPacket(val code: Int, var identifier: Int, var authenticator: Array[Byte], var avps: List[RadiusAVP[Any]]){

  implicit val byteOrder = ByteOrder.BIG_ENDIAN  
  
  /**
   * Builds the radius packet from the bytes.
   */
  private def getBytes(secret: String) : ByteString = {
    // code: 1 byte
    // identifier: 1 byte
    // length: 2: 2 byte
    // authtenticator: 16 octets
    
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
  
  /**
   * Gets the bytes to be sent to the wire, patching id and authenticator.
	 *
   * If access request, authenticator is created new and the AVP are encrypted using this value
   * If accounting request, no encryption can take place (!! used 0 as the authenticator), and the request authenticator is calculated as a md5 hash
   * 
   */
  def getRequestBytes(secret: String, id: Int) : ByteString = {
    
    identifier = id
    code match {
      case RadiusPacket.ACCOUNTING_REQUEST =>
        // Just in case it was filled
        authenticator = List.fill[Byte](16)(0).toArray
        val bytes = getBytes(secret)
        
        // Authenticator is md5(code+identifier+zeroed authenticator+request attributes+secret)
        // patch authenticator
        bytes.patch(4, RadiusPacket.md5(bytes.concat(ByteString.fromString(secret, "UTF-8")).toArray), 16)
        
      case _ => 
        authenticator = RadiusPacket.newAuthenticator
        getBytes(secret)
    }
  }

  /**
   * Generates a new radius packet as response for this request packet.
   */
  def response(isSuccess : Boolean = true) = RadiusPacket.response(this, isSuccess)
  
  /**
   * Generates a new failure response radius packet for this radius request .
   * 
   */
  def responseFailure = RadiusPacket.response(this, false)
  
  /**
   * Creates a new request packet with the same attributes as this packet
   */
  def proxyRequest = RadiusPacket.proxyRequest(this)
  
  /**
   * Creates a response packet for this request with the same attributes as the packet passed as parameter.
   */
  def proxyResponse(responsePacket: RadiusPacket) = RadiusPacket.proxyResponse(responsePacket, this)
  
  /**
   * Gets the bytes to be sent to the wire, patching the authenticator.
	 *
	 * The authenticator is calculated as the hash of the attributes sent, with the request authenticator, plus the secret 
 	 */
  def getResponseBytes(secret: String): ByteString = {
    val responseBytes = getBytes(secret)
    val responseAuthenticator = RadiusPacket.md5(responseBytes.concat(ByteString.fromString(secret, "UTF-8")).toArray)
    
    // patch authenticator
    responseBytes.patch(4, responseAuthenticator, 16)
  }
  
  /**
   * Insert AVP in message
   */ 
  def put(avp: RadiusAVP[Any]) : RadiusPacket = << (avp: RadiusAVP[Any])
  
  /**
  * Insert AVP in message.
  * 
  * Same as <code>put</code>
  */
  def << (avp: RadiusAVP[Any]) : RadiusPacket = {
    avps :+= avp
    this
  }
  
  // Versions with Option. Do nothing if the Option is empty
  /**
   * Insert AVP in message
   */ 
  def put(avpOption: Option[RadiusAVP[Any]]) : RadiusPacket = << (avpOption: Option[RadiusAVP[Any]])
  
  /**
  * Insert AVP in message.
  * 
  * Same as <code>put</code>
  */
  def << (avpOption: Option[RadiusAVP[Any]]) : RadiusPacket = {
    avpOption match {
      case Some(avp) =>     
        avps :+= avp
      case None =>
    }
    this
  }
  
  /**
   * Adds a list of Radius AVPs to the message.
   */
  def putAll(mavp : List[RadiusAVP[Any]]) : RadiusPacket = << (mavp : List[RadiusAVP[Any]]) 
  
  /**
   * Adds a list of Diameter AVPs to the message.
   * 
   * Same as <code>putAll</code>
   */
  def << (mavp : List[RadiusAVP[Any]]) : RadiusPacket = {
    avps = avps ++ mavp
    this
  }
  
  /**
   * Extracts the first AVP with the specified name from message.
   */
  def get(attributeName: String): Option[RadiusAVP[Any]] = >> (attributeName: String)
  
  /**
   * Extracts the first AVP with the specified name from message.
   * 
   * Same as <code>get</code>
   */
  def >> (attributeName: String) : Option[RadiusAVP[Any]] = {
    RadiusDictionary.avpMapByName.get(attributeName).map(_.code) match {
      case Some(code) => avps.find(avp => avp.code == code)
      case None => None
    }
  }
  
  /**
   * Extract all the AVPs with the specified name from message.
   */
  def getAll(attributeName: String): List[RadiusAVP[Any]] = >>+ (attributeName: String)
  
  /**
   * Extracts all the AVPs with the specified name from message.
   * 
   * Same as <code>getAll</code>
   */
  def >>+ (attributeName: String) : List[RadiusAVP[Any]] = {
    RadiusDictionary.avpMapByName.get(attributeName).map(_.code) match {
      case Some(code) => avps.filter(avp => avp.code == code)
      case None => List[RadiusAVP[Any]]()
    }
  }
  
  /**
   * Extracts AVP from message and force conversion to string. If multivalue, returns comma separated list
   */
  def getAsString(attributeName: String): String = >>++ (attributeName: String)
  
  /**
   * Extracts AVP from message and force conversion to string. If multivalue, returns comma separated list
   */
  def >>++ (attributeName: String): String = {
    RadiusDictionary.avpMapByName.get(attributeName).map(_.code) match {
      case Some(code) => avps.filter(avp => avp.code == code).map(_.toString).mkString(",")
      case None => ""
    }
  }
  
  /**
   * Deletes all the attributes with the specified name from the packet.
   */
  def removeAll(attributeName: String): RadiusPacket = {
    val a = RadiusDictionary.avpMapByName.get(attributeName).map(_.code) match {
      case Some(code) => avps = avps.filter(avp => avp.code != code)
      case None =>
    }
    this
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
  
  /**
   * Two Radius packets are equal if have the same code, identifier, authenticator and avps
   */
  override def equals(other: Any): Boolean = {
    other match {
      case x: RadiusPacket =>
        if( x.code != code || 
            x.identifier != identifier || 
            !x.authenticator.sameElements(authenticator) ||
            !x.avps.sameElements(avps)) false else true
      case _ => 
        false
    }
  }
}

/**
 * Implicit conversions for Radius
 */
object RadiusConversions {
  
  implicit var jsonFormats = DefaultFormats + new RadiusPacketSerializer
  
  /**
   * Radius AVP to String (value)
   */
  implicit def RadiusAVP2String(avp: Option[RadiusAVP[Any]]) : String = {
    avp match {
      case Some(v) => 
        v match {
          case iAVP: IntegerRadiusAVP => 
            // If enumNames has content, get the string corresponding to the code with default the code
            RadiusDictionary.avpMapByCode((v.vendorId, v.code)).enumNames.map(_.getOrElse(iAVP.value.toInt, v.value.toString)).getOrElse(v.value.toString)
          case avp: RadiusAVP[Any] => avp.stringValue
        }
      case None => ""
    }
  }
  
  /**
   * Radius AVP from tuple (name, value)
   */
  implicit def Tuple2RadiusAVP(tuple : (String, String)) : RadiusAVP[Any] = {
    val (attrName, attrValue) = tuple
    
    val dictItem = RadiusDictionary.avpMapByName(attrName)
    val code = dictItem.code
    val isVendorSpecific = dictItem.vendorId != 0
    val vendorId = dictItem.vendorId
    
    dictItem.radiusType match {
      case RadiusTypes.OCTETS => new OctetsRadiusAVP(code, vendorId, OctetOps.stringToOctets(attrValue))
      case RadiusTypes.STRING => new StringRadiusAVP(code, vendorId, attrValue)
      case RadiusTypes.INTEGER => new IntegerRadiusAVP(code, vendorId, dictItem.enumValues.get(attrValue))
      case RadiusTypes.TIME => new TimeRadiusAVP(code, vendorId, new java.text.SimpleDateFormat("yyyy-MM-ddThh:mm:ss").parse(attrValue))
      case RadiusTypes.ADDRESS => new AddressRadiusAVP(code, vendorId, java.net.InetAddress.getByName(attrValue))
      case RadiusTypes.IPV6ADDR => new IPv6AddressRadiusAVP(code, vendorId, java.net.InetAddress.getByName(attrValue))
      case RadiusTypes.IPV6PREFIX => new IPv6PrefixRadiusAVP(code, vendorId, attrValue)
      case RadiusTypes.IFID => new InterfaceIdRadiusAVP(code, vendorId, OctetOps.stringToOctets(attrValue))
      case RadiusTypes.INTEGER64 => new Integer64RadiusAVP(code, vendorId, attrValue.toLong)
    }
  }
  
  /**
   * Radius AVP from tuple (name, value), where the value is an Int
   */
  implicit def TupleInt2RadiusAVP(tuple : (String, Int)) : RadiusAVP[Any] = {
    val (attrName, attrValue) = tuple
    TupleLong2RadiusAVP((attrName, attrValue.toLong))
  }
  
  /**
   * Radius AVP from tuple (name, value), where the value is a Long
   */
  implicit def TupleLong2RadiusAVP(tuple : (String, Long)) : RadiusAVP[Any] = {
    val (attrName, attrValue) = tuple
    
    val dictItem = RadiusDictionary.avpMapByName(attrName)
    val code = dictItem.code
    val isVendorSpecific = dictItem.vendorId != 0
    val vendorId = dictItem.vendorId
    
    dictItem.radiusType match {
      case RadiusTypes.OCTETS => throw new RadiusCodingException(s"Invalid value $attrValue for attribute $attrName")
      case RadiusTypes.STRING => new StringRadiusAVP(code, vendorId, attrValue.toString)
      case RadiusTypes.INTEGER => new IntegerRadiusAVP(code, vendorId, attrValue.toInt)
      case RadiusTypes.TIME=> throw new RadiusCodingException(s"Invalid value $attrValue for attribute $attrName")
      case RadiusTypes.ADDRESS => throw new RadiusCodingException(s"Invalid value $attrValue for attribute $attrName")
      case RadiusTypes.IPV6ADDR => throw new RadiusCodingException(s"Invalid value $attrValue for attribute $attrName")
      case RadiusTypes.IPV6PREFIX => throw new RadiusCodingException(s"Invalid value $attrValue for attribute $attrName")
      case RadiusTypes.IFID => throw new RadiusCodingException(s"Invalid value $attrValue for attribute $attrName")
      case RadiusTypes.INTEGER64 => new Integer64RadiusAVP(code, vendorId, attrValue.toLong)
    }
  }

  /**
   * Helper for custom RadiusPacket Serializer.
   * 
   * Useful for handling types correctly
   */
  def TupleJson2RadiusAVP(tuple: (String, JValue)): RadiusAVP[Any] = {
    val (attrName, attrValue) = tuple
    
    val dictItem = RadiusDictionary.avpMapByName(attrName)
    val code = dictItem.code
    val isVendorSpecific = dictItem.vendorId != 0
    val vendorId = dictItem.vendorId
    
    dictItem.radiusType match {
      case RadiusTypes.OCTETS => new OctetsRadiusAVP(code, vendorId, OctetOps.stringToOctets(attrValue.extract[String]))
      case RadiusTypes.STRING => new StringRadiusAVP(code, vendorId, attrValue.extract[String])
      case RadiusTypes.INTEGER => new IntegerRadiusAVP(code, vendorId, attrValue.extract[Int])
      case RadiusTypes.TIME => new TimeRadiusAVP(code, vendorId, new java.text.SimpleDateFormat("yyyy-MM-ddThh:mm:ss").parse(attrValue.extract[String]))
      case RadiusTypes.ADDRESS => new AddressRadiusAVP(code, vendorId, java.net.InetAddress.getByName(attrValue.extract[String]))
      case RadiusTypes.IPV6ADDR => new IPv6AddressRadiusAVP(code, vendorId, java.net.InetAddress.getByName(attrValue.extract[String]))
      case RadiusTypes.IPV6PREFIX => new IPv6PrefixRadiusAVP(code, vendorId, attrValue.extract[String])
      case RadiusTypes.IFID => new InterfaceIdRadiusAVP(code, vendorId, OctetOps.stringToOctets(attrValue.extract[String]))
      case RadiusTypes.INTEGER64 => new Integer64RadiusAVP(code, vendorId, attrValue.extract[Long])
    }
  }
  
  /**
   * Helper for custom RadiusAVPList Serializer.
   */
  def RadiusAVPToJField(avp: RadiusAVP[Any]) = {
    avp match {
      case avp: OctetsRadiusAVP  => JField(avp.getName, JString(OctetOps.octetsToString(avp.value)))
      case avp: StringRadiusAVP => JField(avp.getName, JString(avp.value))
      case avp: IntegerRadiusAVP => JField(avp.getName, JInt(avp.value))
      case avp: TimeRadiusAVP => JField(avp.getName, JString(new java.text.SimpleDateFormat("yyyy-MM-ddThh:mm:ss").format(avp.value)))
      case avp: AddressRadiusAVP => JField(avp.getName, JString(avp.toString))
      case avp: IPv6AddressRadiusAVP => JField(avp.getName, JString(avp.toString))
      case avp: IPv6PrefixRadiusAVP => JField(avp.getName, JString(avp.toString))
      case avp: InterfaceIdRadiusAVP => JField(avp.getName, JString(avp.toString))
      case avp: Integer64RadiusAVP => JField(avp.getName, JInt(avp.value))
     }
  }
  
  
   /*
   * Radius packet JSON
   * 
   * {
   * 	code: <code>,
   *  identifier: <id>,
   *  authenticator: <authenticator>,
   *  avps: {
   *  	attrName1: <attrValue>
   *    attrName2: [<attrValueA>, <attrValueB>]
   *    ...
   *  }
   * }
   */

  /**
   * Custom serializer for RadiusPacket
   */
  class RadiusPacketSerializer extends CustomSerializer[RadiusPacket](implicit jsonFormats => (
  {
    case jv: JValue =>
      val avps = for {
        JObject(javps) <- (jv \ "avps")
        avp <- javps
      } yield TupleJson2RadiusAVP(avp)
      
      new RadiusPacket(
         (jv \ "code").extract[Int],
         (jv \ "identifier").extract[Option[Int]].getOrElse(0),
         OctetOps.stringToOctets(
             (jv \ "authenticator").extract[Option[String]].getOrElse("0")
          ).toArray,
         List[RadiusAVP[Any]](avps: _*) 
         )
  },
  {
    case rp : RadiusPacket =>
      val javps = for {
        avp <- rp.avps.toList
      } yield
        avp match { // TODO: Replace with RadiusAVPToJField
          case avp: OctetsRadiusAVP  => JField(avp.getName, JString(OctetOps.octetsToString(avp.value)))
          case avp: StringRadiusAVP => JField(avp.getName, JString(avp.value))
          case avp: IntegerRadiusAVP => JField(avp.getName, JInt(avp.value))
          case avp: TimeRadiusAVP =>
            val sdf = new java.text.SimpleDateFormat("yyyy-MM-ddThh:mm:ss")
            JField(avp.getName, JString(sdf.format(avp.value)))
          case avp: AddressRadiusAVP => JField(avp.getName, JString(avp.toString))
          case avp: IPv6AddressRadiusAVP => JField(avp.getName, JString(avp.toString))
          case avp: IPv6PrefixRadiusAVP => JField(avp.getName, JString(avp.toString))
          case avp: InterfaceIdRadiusAVP => JField(avp.getName, JString(avp.toString))
          case avp: Integer64RadiusAVP => JField(avp.getName, JInt(avp.value))
        }

      ("code" -> rp.code) ~
      ("id" -> rp.identifier) ~ 
      ("authenticator" -> OctetOps.octetsToString(rp.authenticator.toList)) ~
      ("avps" -> JObject(javps))
  }
  ))
  
  /**
   * For implicit conversion from RadiusPacket to JSON
   */
  implicit def radiusPacketToJson(rp: RadiusPacket): JValue = {
    Extraction.decompose(rp)
  }
  
  /**
   * For implicit conversion from JSON to RadiusPacket
   */
  implicit def jsonToRadiusPacket(jv: JValue): RadiusPacket = {
    jv.extract[RadiusPacket]
  }
}


