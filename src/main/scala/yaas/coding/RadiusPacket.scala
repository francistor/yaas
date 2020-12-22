package yaas.coding

import java.nio.ByteOrder

import akka.util.{ByteString, ByteStringBuilder}
import org.json4s
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.slf4j.LoggerFactory
import yaas.util.UByteString
import yaas.util.OctetOps
import yaas.dictionary._

import scala.util.{Failure, Success, Try}

/**
 * Radius coding error.
 * @param msg the message
 */
class RadiusCodingException(val msg: String) extends java.lang.Exception(msg: String)

/**
 * AVP extraction exception
 * @param msg the message
 */
class RadiusExtractionException(val msg: String) extends java.lang.Exception(msg: String)

/**
 * Builder of Radius AVP from Bytes.
 */
object RadiusAVP {
  private implicit val byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN
    
  val ipv6PrefixRegex = """(.+)/([0-9]+)""".r

  val DATE_FORMAT = "YYYY-MM-dd'T'hh:mm:ss"

  /**
   * Builds a Radius Packet.
   *
   * @param bytes the wire representation of the
   * @param authenticator request authenticator for this packet. Used for encrypted attributes
   * @param secret shared secret with remote. Used for encrypted attributes
   * @return
   */
  def apply(bytes: ByteString, authenticator: Array[Byte], secret: String) : RadiusAVP[Any] = {
    // AVP Header is
    //    code: 1 byte
    //    length: 1 byte
    //    If code == 26
    //      vendorId: 4 bytes
    //      code: 1 byte
    //      length: 1 byte
    //      value
    //    Else
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
  
  private implicit val byteOrder: ByteOrder = RadiusAVP.byteOrder

  /**
   * Holds the Radius dictionary entry for this AVP
   */
  val dictionaryItem: RadiusAVPDictItem = RadiusDictionary.avpMapByCode.getOrElse((vendorId, code), RadiusDictionary.unknownRadiusDictionaryItem)
  
  /**
   * Gets the bytes of the serialized attribute.
   * 
   * If the dictionary specifies that the attribute is encrypted, the RFC algorithm is applied, which requires the passing
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
    //    Else
    //      value
    
    val builder = new ByteStringBuilder()
    // Need to do this first
    
    // use avpMap to encrypt
    val avpMap = RadiusDictionary.avpMapByCode
    val payloadBytes = 
      if(avpMap.get(vendorId, code).map(_.encrypt).contains(1)) ByteString.fromArray(RadiusPacket.encrypt1(authenticator, secret, getPayloadBytes.toArray))
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
	 * Value as a String. To be implemented in concrete classes.
	 */
	def stringValue: String = value.toString

  /**
   * Value as a Long. To be implemented in concrete classes.
   */
	def longValue : Long
	
	/**
	 * Want the stringified AVP be the value, so toString reports only the value
	 */
  override def toString: String = stringValue
	
  /**
   * To RadiusAVP are equal if the code, vendorId and value are the same.
   * @param other the RadiusAVP to compare with this one
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
    val attrName = dictionaryItem.name
    val attrValue = stringValue
    
    s"[$attrName = $attrValue]"
  }

  /**
   * Returns the name of the attribute as defined in the Radius dictionary
   */
  def getName: String = {
    dictionaryItem.name
  }
  
  /**
   * Returns the type of the attribute as defined in the Radius dictionary.
   */
  def getType: Int = {
    dictionaryItem.radiusType
  }
  
  /**
   * Copies this AVP to a new one.
   */
  def copy: RadiusAVP[Any] = {
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

	def getPayloadBytes: ByteString = {
    ByteString(value.toArray)
	}

	override def stringValue: String = {
    OctetOps.octetsToString(value)
	}
	
	override def longValue = throw new RadiusExtractionException("UnknownRadiusAVP cannot be represented as a long")
}

/**
 * Radius type String UTF-8 encoded
 */
class StringRadiusAVP(code: Int, vendorId: Int, value: String) extends RadiusAVP[String](code, vendorId, value) {
	// Secondary constructor from bytes
	def this(code: Int, vendorId: Int, bytes: ByteString){
		this(code, vendorId, bytes.decodeString("UTF-8"))
	}

	def getPayloadBytes: ByteString = {
    ByteString.fromString(value, "UTF-8")
	}

	override def stringValue: String = {
    value
	}
	
  override def longValue = throw new RadiusExtractionException("StringRadiusAVP cannot be represented as a long")
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
	
	def getPayloadBytes: ByteString = {
    ByteString.fromArray(value.toArray)
	}

	override def stringValue: String = {
	  OctetOps.octetsToString(value)
	}
	
  override def longValue = throw new RadiusExtractionException("OctetsRadiusAVP cannot be represented as a long")
}

/**
 * Radius type Integer32
 */
class IntegerRadiusAVP(code: Int, vendorId: Int, value: Long) extends RadiusAVP[Long](code, vendorId, value) {
	// Secondary constructor from bytes
	def this(code: Int, vendorId: Int, bytes: ByteString)(implicit byteOrder: ByteOrder){
		this(code, vendorId, UByteString.getUnsigned32(bytes))
	}

	def getPayloadBytes: ByteString = {
    UByteString.putUnsigned32(new ByteStringBuilder, value).result
	}

  override def stringValue: String = {
    RadiusDictionary.avpMapByCode.get((vendorId, code)).flatMap(_.enumNames).flatMap(_.get(value.toInt)) match {
      case Some(strValue) => strValue
      case _ => value.toString
    }
  }
	
  override def longValue: Long = {
    value
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

	def getPayloadBytes: ByteString = {
    new ByteStringBuilder().putBytes(value.getAddress).result
	}

	override def stringValue: String = {
    value.getHostAddress
	}
	
	override def longValue = throw new RadiusExtractionException("AddressRadiusAVP cannot be represented as a long")
}

/**
 * Radius Type Time (encoded as seconds since the Epoch UTC)
 */
class TimeRadiusAVP(code: Int, vendorId: Int, value: java.util.Date) extends RadiusAVP[java.util.Date](code, vendorId, value) {
	// Secondary constructor from bytes
	def this(code: Int, vendorId: Int, bytes: ByteString)(implicit byteOrder: ByteOrder){
		this(code, vendorId, new java.util.Date(UByteString.getUnsigned32(bytes) * 1000))
	}

	def getPayloadBytes: ByteString = {
    UByteString.putUnsigned32(new ByteStringBuilder, value.getTime / 1000).result
	}

	override def stringValue: String = {
    val sdf = new java.text.SimpleDateFormat(RadiusAVP.DATE_FORMAT)
    sdf.format(value)
	}
	
	def longValue: Long = {
	  value.getTime / 1000
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

	def getPayloadBytes: ByteString = {
    new ByteStringBuilder().putBytes(value.getAddress).result
	}

	override def stringValue: String = {
    value.getHostAddress
	}
	
	override def longValue = throw new RadiusExtractionException("IPv6AddressRadiusAVP cannot be represented as a long")
}

/**
 * Radius Type IPv6 prefix. Encoded as 1 byte padding, 1 byte prefix length, and 16 bytes with prefix.
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
      s"${prefix.getHostAddress}/$prefixLen"
    })
  }
  
  def getPayloadBytes: ByteString = {
    val builder = new ByteStringBuilder
    builder.putByte(0)
    RadiusAVP.ipv6PrefixRegex.findFirstMatchIn(value) match {
      case Some(m) =>
        UByteString.putUnsignedByte(builder, m.group(2).toInt)
        builder.putBytes(java.net.InetAddress.getByName(m.group(1)).getAddress)
      case None =>
        throw new RadiusCodingException(s"Cannot format $value as IPv6 address")
    }

    builder.result
  }
  
  override def stringValue: String = value
  
  override def longValue = throw new RadiusExtractionException("IPv6AddressRadiusAVP cannot be represented as a long")
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

	def getPayloadBytes: ByteString = ByteString.fromArray(value.toArray)

	override def stringValue: String = OctetOps.octetsToString(value)
	
	override def longValue = throw new RadiusExtractionException("InterfaceIdRadiusAVP cannot be represented as a long")
}

/**
 * Radius Type Integer64. 
 * 
 * Values bigger than 2EXP63 are coded / decoded incorrectly
 */
class Integer64RadiusAVP(code: Int, vendorId: Int, value: Long) extends RadiusAVP[Long](code, vendorId, value) {
	// Secondary constructor from bytes
	def this(code: Int, vendorId: Int, bytes: ByteString)(implicit byteOrder: ByteOrder){
		this(code, vendorId, UByteString.getUnsigned64(bytes))
	}

	def getPayloadBytes: ByteString = UByteString.putUnsigned64(new ByteStringBuilder, value).result

	override def stringValue: String = value.toString
	
	override def longValue: Long = value
}

/**
 * Constructor and helper functions for RadiusPackets.
 *   
 */

object RadiusPacket {
  implicit val byteOrder: ByteOrder = java.nio.ByteOrder.BIG_ENDIAN
  
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
   * If this is a request packet, the authenticator is not passed as parameter but taken from the bytes and, along with
   * the secret, allows the decryption of the AVPs. <code>requestAuthenticator</code> is <code>None</code>.
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
    
    @scala.annotation.tailrec
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

  /**
   * Generates an empty request packet with the specified code.
   * 
   * The authenticator will be generated when serializing to bytes.
   * The id is generated just before being sent to the destination
   */
  def request(code: Int): RadiusPacket = {
    new RadiusPacket(code, 0 /* to be replaced */, Array[Byte](16) /* To be generated later */, List[RadiusAVP[Any]]())
  }
    
  /**
   * Generates a new radius packet as response for the specified radius request.
   * 
   * The identifier and authenticator are that of the request at this stage (the value in the wire will be calculated according
   * to the rules for the response authenticator).
   * 
   * If isSuccess is true, the code will be added one, and two otherwise
   */
  def response(requestPacket: RadiusPacket, isSuccess : Boolean = true): RadiusPacket = {
    val code = if(isSuccess) requestPacket.code + 1 else requestPacket.code + 2
    new RadiusPacket(code, requestPacket.identifier, requestPacket.authenticator, List[RadiusAVP[Any]]())
  }
  
  /**
   * Generates a new failure response radius packet for the specified radius request.
   */
  def responseFailure(requestPacket: RadiusPacket): RadiusPacket = response(requestPacket, isSuccess = false)
  
  /**
   * Creates a request packet which is a copy of the received packet.
   * 
   * Id and authenticator will be generated when sending to the wire.
   */
  def proxyRequest(requestPacket: RadiusPacket): RadiusPacket = {
    new RadiusPacket(requestPacket.code, 0, Array[Byte](16) /* To be generated later */, requestPacket.avps.map(attr => attr.copy))
  }
  
  /**
   * Creates a response packet for the specified request packet (id and authenticator), but copying the attributes from the
   * response packet in the argument.
   * 
   */
  def proxyResponse(responsePacket: RadiusPacket, requestPacket: RadiusPacket): RadiusPacket = {
    new RadiusPacket(responsePacket.code, requestPacket.identifier, requestPacket.authenticator, responsePacket.avps.map(attr => attr.copy))
  }
  
  /**
   * Encrypts the specified value according to the rules in rfc2865, item 5.2.
   */
  def encrypt1(authenticator: Array[Byte], secret: String, value: Array[Byte]) : Array[Byte] = {

    @scala.annotation.tailrec
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
    appendChunk(Array(), authenticator, secret.getBytes("UTF-8"), value.padTo[Byte, Array[Byte]](vLenPadded, 0)).slice(0, vLen)

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

    @scala.annotation.tailrec
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
    prependChunk(Array(), authenticator, secret.getBytes("UTF-8"), value.padTo[Byte, Array[Byte]](vLenPadded, 0)).slice(0, vLen)
  }
  
  /**
   * Wrapper for MD5 generation
   */
  def md5(v: Array[Byte]): Array[Byte] = {
    java.security.MessageDigest.getInstance("MD5").digest(v)
  }
  
  /**
   * Generates a new random value to be used as authenticator.
   */
  def newAuthenticator: Array[Byte] = {
    val firstPart = System.currentTimeMillis()
    val secondPart = (Math.random() * 9223372036854775807L).toLong
    new ByteStringBuilder().putLong(firstPart).putLong(secondPart).result.toArray
  }
  
  /**
   * Check that the authenticator received in the response packet is correct (that is, contains the hash of the 
   * sent values with the request authenticator and secret).
   */
  def checkAuthenticator(packet: ByteString, reqAuthenticator: Array[Byte], secret: String): Boolean = {
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

  implicit val byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN
  
  /**
   * Builds the radius packet from the bytes.
   */
  private def getBytes(secret: String) : ByteString = {
    // code: 1 byte
    // identifier: 1 byte
    // length: 2: 2 byte
    // authenticator: 16 octets
    
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
   * If other packet types, no encryption can take place (!! used 0 as the authenticator), and the request authenticator is calculated as a md5 hash
   * 
   */
  def getRequestBytes(secret: String, id: Int) : ByteString = {
    
    identifier = id
    code match {
      case RadiusPacket.ACCESS_REQUEST =>
        authenticator = RadiusPacket.newAuthenticator
        getBytes(secret)
        
      case _ => 
        // Just in case it was filled
        authenticator = List.fill[Byte](16)(0).toArray
        val bytes = getBytes(secret)
        
        // Authenticator is md5(code+identifier+zeroed authenticator+request attributes+secret)
        // patch authenticator
        authenticator = RadiusPacket.md5(bytes.concat(ByteString.fromString(secret, "UTF-8")).toArray)
        bytes.patch(4, authenticator, 16)
    }
  }

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
   * Generates a new radius packet as response for this request packet.
   */
  def response(isSuccess : Boolean = true): RadiusPacket = RadiusPacket.response(this, isSuccess)
  
  /**
   * Generates a new failure response radius packet for this radius request .
   * 
   */
  def responseFailure: RadiusPacket = RadiusPacket.response(this, isSuccess = false)
  
  /**
   * Creates a new request packet with the same attributes as this packet
   */
  def proxyRequest: RadiusPacket = RadiusPacket.proxyRequest(this)
  
  /**
   * Creates a response packet for this request with the same attributes as the packet passed as parameter.
   */
  def proxyResponse(responsePacket: RadiusPacket): RadiusPacket = RadiusPacket.proxyResponse(responsePacket, this)
  
  /**
  * Adds AVP to packet.
  * 
  * Same as <code>put</code>
  */
  def << (avp: RadiusAVP[Any]) : RadiusPacket = {
    avps :+= avp
    this
  }

  /**
   * Adds AVP to packet
   */
  def put(avp: RadiusAVP[Any]) : RadiusPacket = << (avp: RadiusAVP[Any])
  
  
  // Versions with Option. Do nothing if the Option is empty
  /**
  * Adds AVP to packet.
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
   * Adds AVP to packet
   */
  def put(avpOption: Option[RadiusAVP[Any]]) : RadiusPacket = << (avpOption: Option[RadiusAVP[Any]])
  
  /**
   * Adds a list of Radius AVPs to the packet.
   * 
   * Same as <code>putAll</code>
   */
  def << (mavp : List[RadiusAVP[Any]]) : RadiusPacket = {
    avps = avps ++ mavp
    this
  }

  /**
   * Adds a list of Radius AVPs to the packet.
   */
  def putAll(mavp : List[RadiusAVP[Any]]) : RadiusPacket = << (mavp : List[RadiusAVP[Any]])
  
  /**
  * Adds AVP to packet if not already present.
  * 
  * Same as <code>putIfAbsent</code>
  */
  def <<? (avp: RadiusAVP[Any]) : RadiusPacket = {
    if(!avps.exists(v => v.vendorId == avp.vendorId && v.code == avp.code)){
      avps :+= avp
    }
    this
  }

  /**
   * Adds AVP to packet if not already present
   */
  def putIfAbsent(avp: RadiusAVP[Any]) : RadiusPacket = <<? (avp: RadiusAVP[Any])
  
  /**
  * Adds AVP to packet
  * 
  * Same as <code>putIfAbsent</code>
  */
  def <<? (avpOption: Option[RadiusAVP[Any]]) : RadiusPacket = {
    avpOption match {
      case Some(avp) =>
        this.putIfAbsent(avp)

      case None =>
        this
    }
  }

  /**
   *Adds AVP to packet if not already present
   */
  def putIfAbsent(avpOption: Option[RadiusAVP[Any]]) : RadiusPacket = <<? (avpOption: Option[RadiusAVP[Any]])
  
  /**
   * Adds a list of Diameter AVPs to the packet, if not already present.
   * 
   * Same as <code>putAll</code>
   */
  def <<? (avpList : List[RadiusAVP[Any]]) : RadiusPacket = {
    avpList.map(avp => this <<? avp)
    this
  }

  /**
   * Adds a list of Radius AVPs to the packet, if not already present.
   */
  def putAllIfAbsent(avpList : List[RadiusAVP[Any]]) : RadiusPacket = <<? (avpList : List[RadiusAVP[Any]])

  /**
  * Add AVP to packet if not already present, and replace existing if present
  * 
  * Same as <code>putOrReplace</code>
  */
  def <:< (avp: RadiusAVP[Any]) : RadiusPacket = {
    this.removeAll(avp.code, avp.vendorId)
    avps :+= avp
    this
  }

  /**
   * Adds AVP to packet, replacing the existing value
   */
  def putOrReplace(avp: RadiusAVP[Any]) : RadiusPacket = <:< (avp: RadiusAVP[Any])

  
  /**
  * Adds AVP to packet, replacing the existing value. If the contents of the Options are empty, does nothing.
  * 
  * Same as <code>putOrReplace</code>
  */
  def <:< (avpOption: Option[RadiusAVP[Any]]) : RadiusPacket = {
    avpOption match {
      case Some(avp) =>
        this.putOrReplace(avp)

      case None =>
        this
    }
  }

  /**
   * Adds AVP to packet, replacing the existing value. If the contents of the Options are empty, does nothing.
   */
  def putOrReplace(avpOption: Option[RadiusAVP[Any]]) : RadiusPacket = <:< (avpOption: Option[RadiusAVP[Any]])
  
  /**
  * Adds AVP list to packet if not already present.
  * 
  * Same as <code>putOrReplace</code>
  */
  def <:< (avpList: List[RadiusAVP[Any]]) : RadiusPacket = {
    for(avp <- avpList){
      this.removeAll(avp.code, avp.vendorId)
      avps :+= avp
    }
    this
  }

  /**
   * Adds AVP list to packet, replacing the existing value
   */
  def putOrReplaceAll(avpList: List[RadiusAVP[Any]]) : RadiusPacket = <:< (avpList: List[RadiusAVP[Any]])

  /**
   * Extracts the first AVP with the specified name from packet.
   * 
   * Same as <code>get</code>
   */
  def >> (attributeName: String) : Option[RadiusAVP[Any]] = {
    RadiusDictionary.getAttrCodeFromName(attributeName) match {
      case Some((vendorId, code)) => avps.find(avp => avp.vendorId == vendorId && avp.code == code)
      case None => None
    }
  }

  /**
   * Extracts the first AVP with the specified name from packet.
   */
  def get(attributeName: String): Option[RadiusAVP[Any]] = >> (attributeName: String)
  
  /**
   * Extracts all the AVPs with the specified name from packet.
   * 
   * Same as <code>getAll</code>
   */
  def >>+ (attributeName: String) : List[RadiusAVP[Any]] = {
    RadiusDictionary.getAttrCodeFromName(attributeName) match {
      case Some((vendorId, code)) => avps.filter(avp => avp.vendorId == vendorId && avp.code == code)
      case None => List[RadiusAVP[Any]]()
    }
  }

  /**
   * Extract all the AVPs with the specified name from packet.
   */
  def getAll(attributeName: String): List[RadiusAVP[Any]] = >>+ (attributeName: String)

  /**
   * Extracts all AVP with the specified name from packet and force conversion to string. If multi-value, returns comma separated list
   */
  def >>* (attributeName: String): String = {
    RadiusDictionary.getAttrCodeFromName(attributeName) match {
      case Some((vendorId, code)) =>
        avps.filter(avp => avp.vendorId == vendorId && avp.code == code).map(_.stringValue).mkString(",")
      case None => ""
    }
  }

  /**
   * Extracts all AVP with the specified name from packet and force conversion to string. If multivalue, returns comma separated list
   */
  def getAsString(attributeName: String): String = >>* (attributeName: String)
  
  /**
   * Deletes all the attributes with the specified name from the packet.
   */
  def removeAll(attributeName: String): RadiusPacket = {
    RadiusDictionary.getAttrCodeFromName(attributeName) match {
      case Some((vendorId, code)) =>
        avps = avps.filter(avp => avp.code != code || avp.vendorId != vendorId)
      case None =>
    }
    this
  }
  
  /**
   * Deletes all the attributes with the specified code from the packet.
   */
  def removeAll(code: Int, vendorId: Int): RadiusPacket = {
    val dictItemOption = RadiusDictionary.avpMapByCode.get((vendorId, code))
    dictItemOption match {
      case Some(dictItem) => 
        avps = avps.filter(avp => avp.vendorId != dictItem.vendorId || avp.code != dictItem.code )
      case None =>
    }
    this
  }

  /**
   * Tries to extract a single attribute with String type, throwing exception if unable and no default specified
   * @param attributeName the name of the attribute to extract
   * @param defaultValue a default value. If not specified, an exception will be thrown if the attribute is not present
   * @return
   */
  def S(attributeName: String, defaultValue: String*): String = {
    getAll(attributeName) match {
      case _ :: List(_) =>
        throw new RadiusExtractionException(s"Multiple $attributeName found")
        
      case List(avp) => avp.stringValue
        
      case Nil =>
        if(defaultValue.isEmpty) throw new RadiusExtractionException(s"attribute $attributeName not found")
        else defaultValue.head
    }
  }

  /**
   * Tries to extract a single attribute with Long type, throwing exception if unable and no default specified
   * @param attributeName the name of the attribute to extract
   * @param defaultValue a default value. If not specified, an exception will be thrown if the attribute is not present
   * @return
   */
  def L(attributeName: String, defaultValue: Long*): Long = {
    getAll(attributeName) match {
      case _ :: List(_) =>
        throw new RadiusExtractionException(s"Multiple $attributeName found")
        
      case List(avp) => avp.longValue
        
      case Nil =>
        if(defaultValue.isEmpty) throw new RadiusExtractionException(s"attribute $attributeName not found")
        else defaultValue.head
        
    }
  }
  
  /**
   * To print the RadiusPacket CDR contents to file.
   */
  def getCDR(format: RadiusSerialFormat): String = {
    format match {
      case f : LivingstoneRadiusSerialFormat =>
        
        val filteredAVPs = if(f.attrList.nonEmpty) avps.filter(avp => f.attrList.contains(avp.getName)) else avps

        val utcDateFormat = new java.text.SimpleDateFormat(RadiusAVP.DATE_FORMAT)
        utcDateFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"))

        utcDateFormat.format(new java.util.Date) + "\n" +
        filteredAVPs.map(avp => s"""${avp.getName}="${avp.stringValue}"""").mkString("\n") + 
        "\n"

      case f : CSVRadiusSerialFormat =>
        // Conversion made explicit
        f.attrList.map(attr => {s""""${RadiusConversions.RadiusAVP2String(get(attr))}""""}).mkString(",")
        
      case _ : JSONRadiusSerialFormat =>
        compact(render(RadiusConversions.radiusPacketToJson(this) \ "avps"))
    }
  }

  private val cookies = scala.collection.mutable.Map[String, String]()

  /**
   * Cookies are assigned to the radius packet to take processing decisions
   * @param name of the cookie
   * @param value of the cookie
   */
  def pushCookie(name: String, value: String): Unit = cookies(name) = value

  /**
   * Retrieves the value of a cookie
   * @param name of the cookie
   * @return
   */
  def getCookie(name: String): Option[String] = cookies.get(name)

  override def toString: String = {
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
    val prettyCookies = "Cookies: " + cookies.map{case (k, v) => s"$k -> $v"}.mkString(",")

    s"\n$codeString\n$prettyCookies\n$prettyAVPs"
  }
  
  /**
   * Two Radius packets are equal if have the same code, identifier, authenticator and avps
   */
  override def equals(other: Any): Boolean = {
    
    // Helper for sorting AVPs
    def avpSorter(a: RadiusAVP[Any], b: RadiusAVP[Any]) = {
      if(a.code != b.code) a.code < b.code
      else a.stringValue < b.stringValue
    }
    
    other match {
      case x: RadiusPacket =>
        // Need to sort the avps in order to compare
        if( x.code != code || 
            x.identifier != identifier || 
            !x.authenticator.sameElements(authenticator) ||
            !(x.avps.sortWith(avpSorter) == avps.sortWith(avpSorter))) false else true
      case _ => 
        false
    }
  }
}

/**
 * Implicit conversions for Radius
 */
object RadiusConversions {
  
  implicit var jsonFormats: Formats = DefaultFormats + new RadiusPacketSerializer

  private val log = LoggerFactory.getLogger(RadiusConversions.getClass)

  /**
   * Implicit conversion to Option[String]
   * @param avpOption the Radius attribute
   * @return
   */
  implicit def RadiusAVP2StringOption(avpOption: Option[RadiusAVP[Any]]): Option[String] = {
    avpOption.map(_.stringValue)
  }

  /**
   * Implicit conversion
   * @param avpOption the Radius attribute
   * @return
   */
  implicit def RadiusAVP2LongOption(avpOption: Option[RadiusAVP[Any]]): Option[Long] = {
      avpOption.map(_.longValue)
  }

  /**
   * Implicit conversion
   * @param avpOption the Radius attribute
   * @return
   */
  implicit def RadiusAVP2Long(avpOption: Option[RadiusAVP[Any]]): Long = {
    avpOption match {
      case Some(avp) => avp.longValue
      case None => throw new RadiusExtractionException(s"Radius Attribute not found")
    }
  }
  
  /**
   * Radius AVP to String (value)
   */
  implicit def RadiusAVP2String(avp: Option[RadiusAVP[Any]]) : String = {
    avp.map(_.stringValue).getOrElse("")
  }
  
  /**
   * Radius AVP from tuple (name, value)
   */
  implicit def Tuple2RadiusAVP(tuple : (String, String)) : RadiusAVP[Any] = {
    val (attrName, attrValue) = tuple
    
    val dictItem = RadiusDictionary.avpMapByName(attrName)
    val code = dictItem.code
    val vendorId = dictItem.vendorId
    
    dictItem.radiusType match {
      case RadiusTypes.OCTETS => new OctetsRadiusAVP(code, vendorId, OctetOps.stringToOctets(attrValue))
      case RadiusTypes.STRING => new StringRadiusAVP(code, vendorId, attrValue)
      case RadiusTypes.INTEGER => new IntegerRadiusAVP(code, vendorId, dictItem.enumValues.get(attrValue))
      case RadiusTypes.TIME => new TimeRadiusAVP(code, vendorId, new java.text.SimpleDateFormat(RadiusAVP.DATE_FORMAT).parse(attrValue))
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
  def JField2RadiusAVP(tuple: JField): RadiusAVP[Any] = {
    val (attrName, attrValue) = tuple
    
    val dictItem = RadiusDictionary.avpMapByName(attrName)
    val code = dictItem.code
    val vendorId = dictItem.vendorId
    
    dictItem.radiusType match {
      case RadiusTypes.OCTETS => new OctetsRadiusAVP(code, vendorId, OctetOps.stringToOctets(attrValue.extract[String]))
      case RadiusTypes.STRING => new StringRadiusAVP(code, vendorId, attrValue.extract[String])
      case RadiusTypes.INTEGER =>
        attrValue match {
          case JString(v) =>
            // Try to get as enum (first option) or convert from string (second option)
            val longValue = (for {
              values <- dictItem.enumValues
              value <- values.get(v)
              longValue = value.toLong
            } yield longValue).getOrElse(v.toLong)
            new IntegerRadiusAVP(code, vendorId, longValue)
            
          case _ => new IntegerRadiusAVP(code, vendorId, attrValue.extract[Int])
        }
        
      case RadiusTypes.TIME => new TimeRadiusAVP(code, vendorId, new java.text.SimpleDateFormat(RadiusAVP.DATE_FORMAT).parse(attrValue.extract[String]))
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
  def RadiusAVPToJField(avp: RadiusAVP[Any]): (String, json4s.JValue) = {
    avp match {
      case avp: OctetsRadiusAVP  => JField(avp.getName, JString(OctetOps.octetsToString(avp.value)))
      case avp: StringRadiusAVP => JField(avp.getName, JString(avp.value))
      case avp: IntegerRadiusAVP => 
        // Try to convert to String if there is an enum map and the value is found
        val dictItem = RadiusDictionary.avpMapByName(avp.getName)
        val mayBeString = for {
          sValues <- dictItem.enumNames
          sValue <- sValues.get(avp.longValue.toInt)
        } yield sValue
        
        mayBeString match {
          case Some(stringValue) => 
            JField(avp.getName, stringValue)
            
          case _ => 
            JField(avp.getName, JInt(avp.value))
        }
      case avp: TimeRadiusAVP => JField(avp.getName, JString(new java.text.SimpleDateFormat(RadiusAVP.DATE_FORMAT).format(avp.value)))
      case avp: AddressRadiusAVP => JField(avp.getName, JString(avp.toString))
      case avp: IPv6AddressRadiusAVP => JField(avp.getName, JString(avp.toString))
      case avp: IPv6PrefixRadiusAVP => JField(avp.getName, JString(avp.toString))
      case avp: InterfaceIdRadiusAVP => JField(avp.getName, JString(avp.toString))
      case avp: Integer64RadiusAVP => JField(avp.getName, JInt(avp.value))
      case avp: UnknownRadiusAVP => JField(avp.getName, JString(avp.toString))
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
   *  	attrName1: [<attrValueA>, <attrValueB>]
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
      
      val fields = for {
        JObject(javps) <- jv \ "avps"
        JField(k, varr) <- javps
        JArray(vList) <- varr
        v <- vList
      } yield (k, v)

      val avps = fields.flatMap{case (k, v) =>
        Try{JField2RadiusAVP((k, v))} match {
          case Success(avp) => List(avp)
          case Failure(e) =>
            log.error(s"Could not code $k attribute with value $v")
            List()
        }
      }
      
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
        avp <- rp.avps
      } yield RadiusAVPToJField(avp)
      
      // List[(String, JValue)] -> Map[String, List[(String, JValue)] -> Map[String, JArray[JValue]]
      // Group by attribute name and map the values to JArrays
      val javpsArr = javps.groupBy(javp => javp._1).mapValues(listKeyVal => JArray(listKeyVal.map(v => v._2))).toList

      ("code" -> rp.code) ~
      ("id" -> rp.identifier) ~ 
      ("authenticator" -> OctetOps.octetsToString(rp.authenticator.toList)) ~
      ("avps" -> JObject(javpsArr))
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
