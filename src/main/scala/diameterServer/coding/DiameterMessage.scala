package diameterServer.coding

import org.json4s._
import java.nio.ByteOrder
import akka.util.{ByteString, ByteStringBuilder, ByteIterator}
import scala.collection.mutable.ArrayBuffer
import scala.collection.immutable.Queue

import diameterServer.dictionary._

/**
 * DiameterAVP Builder
 */
object DiameterAVP {
  
  implicit val byteOrder = ByteOrder.BIG_ENDIAN
  
  val ipv6PrefixRegex = """(.+)/([0-9]+)""".r
  
  // Builds a Diameter AVP from the received bytes
  def apply(bytes: ByteString) : DiameterAVP[Any] = {
    // AVP Header is
    //    code: 4 byte
    //    flags: 1 byte (vendor, mandatory, proxy)
    //    length: 3 byte
    //    vendorId: 0 / 4 byte
    //    data: rest of bytes
    
    val code = bytes.slice(0, 4).toByteBuffer.getInt
    val flags = bytes.slice(4, 5).toByteBuffer.get()
    val isVendorSpecific : Boolean = (flags & 0x80) > 0
    val isMandatory : Boolean = (flags & 0x40) > 0 
    val avpLength : Int = UByteString.getUnsigned24(bytes.slice(5, 8))
    val dataOffset = if (isVendorSpecific) 12 else 8 
    val vendorId = isVendorSpecific match {
          case true => bytes.slice(8, 12).toByteBuffer.getInt
          case false => 0
    }
    
    val data = bytes.slice(dataOffset, avpLength)
    
    DiameterDictionary.avpMapByCode.get((vendorId, code)).map(_.diameterType).getOrElse(DiameterTypes.NONE) match {
      case DiameterTypes.OCTETSTRING => 
        new OctetStringAVP(code, isVendorSpecific, isMandatory, vendorId, data)
        
      case DiameterTypes.INTEGER_32 =>
        new Integer32AVP(code, isVendorSpecific, isMandatory, vendorId, data)
      
      case DiameterTypes.INTEGER_64 =>
        new Integer64AVP(code, isVendorSpecific, isMandatory, vendorId, data)
      
      case DiameterTypes.UNSIGNED_32 =>
        new Unsigned32AVP(code, isVendorSpecific, isMandatory, vendorId, data)
      
      case DiameterTypes.UNSIGNED_64 =>
        // Problem here. Only signed is supported
        new Unsigned64AVP(code, isVendorSpecific, isMandatory, vendorId, data)
      
      case DiameterTypes.FLOAT_32 =>
        new Float32AVP(code, isVendorSpecific, isMandatory, vendorId, data)
      
      case DiameterTypes.FLOAT_64 =>
        new Float64AVP(code, isVendorSpecific, isMandatory, vendorId, data)
      
      case DiameterTypes.GROUPED =>
        new GroupedAVP(code, isVendorSpecific, isMandatory, vendorId, data)
      
      case DiameterTypes.ADDRESS =>
        new AddressAVP(code, isVendorSpecific, isMandatory, vendorId, data)
      
      case DiameterTypes.TIME =>
        new TimeAVP(code, isVendorSpecific, isMandatory, vendorId, data)
      
      case DiameterTypes.UTF8STRING =>
        new UTF8StringAVP(code, isVendorSpecific, isMandatory, vendorId, data)
        
      case DiameterTypes.DIAMETERIDENTITY =>
        new DiameterIdentityAVP(code, isVendorSpecific, isMandatory, vendorId, data)
        
      case DiameterTypes.DIAMETERURI =>
        // TODO: Check syntax using regex
        new DiameterURIAVP(code, isVendorSpecific, isMandatory, vendorId, data)
        
      case DiameterTypes.ENUMERATED =>
        new EnumeratedAVP(code, isVendorSpecific, isMandatory, vendorId, data)
        
      case DiameterTypes.IPFILTERRULE =>
        // TODO: Check syntax using regex
        new IPFilterRuleAVP(code, isVendorSpecific, isMandatory, vendorId, data)
      
      case DiameterTypes.RADIUS_IPV4ADDRESS =>
        new IPv4AddressAVP(code, isVendorSpecific, isMandatory, vendorId, data)

      case DiameterTypes.RADIUS_IPV6ADDRESS =>
        new IPv6AddressAVP(code, isVendorSpecific, isMandatory, vendorId, data)

      case DiameterTypes.RADIUS_IPV6PREFIX =>
        new IPv6PrefixAVP(code, isVendorSpecific, isMandatory, vendorId, data)

      case _ =>
        new UnknownAVP(code, isVendorSpecific, isMandatory, vendorId, data)
    }
  }
}

abstract class DiameterAVP[+A](val code: Int, val isVendorSpecific: Boolean, val isMandatory: Boolean, val vendorId: Int, val value: A){
  
  implicit val byteOrder = DiameterAVP.byteOrder 
  
  def getBytes: ByteString = {
    
    val builder = new ByteStringBuilder()
    // AVP Header is
    //    code: 4 byte
    //    flags: 1 byte
    //    length: 3 byte
    //    vendorId: 0 / 4 byte
    //    data: rest of bytes
    
    // Need to do this first
    val payloadBytes = getPayloadBytes
    val length = if(vendorId == 0) 8 + payloadBytes.length else 12 + payloadBytes.length
    
    // TODO: Warning. This will not work for codes bigger than 2^32 -1
    // code
    builder.putInt(code.toInt)
    // flags. The minus sign in the -128 is not an error
    builder.putByte((0 + (if(isVendorSpecific) -128 else 0) + (if(isMandatory) 64 else 0)).toByte)
    // length
    UByteString.putUnsigned24(builder, length)
    // vendorId
    if(vendorId !=0) builder.putInt(vendorId)
    // data
    builder.append(getPayloadBytes)
    
    // Pad to 4 byte boundary
    if(length % 4 != 0) builder.putBytes(new Array[Byte](4 - length % 4))
    
    builder.result()
  }
  
  // Serializes the payload only
  def getPayloadBytes: ByteString
  
  override def equals(other: Any): Boolean =
    other match {
      case x: DiameterAVP[Any] =>
        if(x.code != code || x.isVendorSpecific != isVendorSpecific || x.isMandatory != isMandatory || x.value != value) false else true
      case _ => false
    }
  
  /*
   * Print the AVP in [name -> value] format
   * With special treatment for Grouped and Enumerated attributes
   */
  override def toString = {
    val dictItem = DiameterDictionary.avpMapByCode.get((vendorId, code)).getOrElse(BasicAVPDictItem(0, 0, "Unknown", DiameterTypes.NONE))
    val attributeName = dictItem.name
    
    // Get the value in string format
    val attributeValue = dictItem match {
      // If grouped, iterate through the items
      case di : GroupedAVPDictItem =>
        value match {
          case avps: Seq[Any] => avps.mkString(", ")
          case _ => "Error"
        }
      // If enumerated, get the decoded value
      case di : EnumeratedAVPDictItem =>
        // Get reverse map
        value match {
          case v : Int =>
            di.values.map(_.swap).getOrElse(v, value.toString)
          case _ =>
            "ERROR"
        }
      // Simply convert to string
      case _ => value.toString()
    }
    s"[ $attributeName = $attributeValue ]"
  } 
}

class UnknownAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: List[Byte]) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
    // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.toList)
  }
  
  def getPayloadBytes = {
    ByteString()
  }
}

class OctetStringAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: List[Byte]) extends DiameterAVP[List[Byte]](code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.toList)
  }
  
  def getPayloadBytes = {
    // Pad to multiple of 4 bytes
    // val paddedLen : Int = if (value.length ==0) 0 else (value.length / 4 + 1) * 4 
    // ByteString.fromArray(value.padTo[Byte, Array[Byte]](paddedLen, 0))
    ByteString.fromArray(value.toArray)
  }
}

class Integer32AVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: Int) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString)(implicit byteOrder: ByteOrder){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getInt(byteOrder))
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putInt(value).result
  } 
}

class Integer64AVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString)(implicit byteOrder: ByteOrder){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getLong(byteOrder))
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putLong(value).result
  } 
}

class Unsigned32AVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString)(implicit byteOrder: ByteOrder) {
    this(code, isVendorSpecific, isMandatory, vendorId, UByteString.getUnsigned32(bytes))
  }
  
  def getPayloadBytes = {
    UByteString.putUnsigned32(new ByteStringBuilder(), value).result
  }
}

// This class does not correctly represents integers bigger than 2exp63
class Unsigned64AVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString)(implicit byteOrder: ByteOrder) {
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getLong)
  }
  
  def getPayloadBytes = {
    UByteString.putUnsigned64(new ByteStringBuilder(), value).result
  } 
}

class Float32AVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: Float) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString)(implicit byteOrder: ByteOrder){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getFloat)
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putFloat(value).result
  } 
}

class Float64AVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: Double) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString)(implicit byteOrder: ByteOrder){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getDouble)
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putDouble(value).result
  } 
}

class GroupedAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: Seq[DiameterAVP[Any]]) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, {
        var avps = Queue[DiameterAVP[Any]]()
        var idx = 0
        while(idx < bytes.length){
          val l = UByteString.getUnsigned24(bytes.slice(idx + 5, idx + 8))
          val theNextAVP = DiameterAVP(bytes.slice(idx, idx +  l))
          avps = avps :+ theNextAVP
          idx += l + l%4
        }
        avps
    })
  }
  
  def getPayloadBytes = {
    val builder = new ByteStringBuilder()
    for(avp <- value){
      builder.append(avp.getBytes)
      // Padding
      builder.putBytes(new Array[Byte](((4 - builder.length) % 4) % 4))
    }
    builder.result
  }
}

class AddressAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: java.net.InetAddress) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, java.net.InetAddress.getByAddress(bytes.drop(2).toArray))
  }
  
  def getPayloadBytes = {
    val bsb = new ByteStringBuilder()
    // First 2 octets are <1> if IPv4 and <2> if IPv6
    if(value.isInstanceOf[java.net.Inet4Address]) bsb.putShort(1) else bsb.putShort(2)
    bsb.putBytes(value.getAddress()).result
  } 
}

class TimeAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: java.util.Date) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString)(implicit byteOrder: ByteOrder){
    this(code, isVendorSpecific, isMandatory, vendorId, new java.util.Date(bytes.iterator.getLong))
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putLong(value.getTime()).result
  }
}

class UTF8StringAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.decodeString("UTF-8"))
  }
  
  def getPayloadBytes = {
    ByteString.fromString(value, "UTF-8")
  }
}

class DiameterIdentityAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.decodeString("UTF-8"))
  }
  
  def getPayloadBytes = {
    ByteString.fromString(value, "UTF-8")
  }
}

class DiameterURIAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.decodeString("UTF-8"))
  }
  
  def getPayloadBytes = {
    ByteString.fromString(value, "UTF-8")
  }
}

class EnumeratedAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: Int) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString)(implicit byteOrder: ByteOrder){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getInt)
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putInt(value).result
  }
}

class IPFilterRuleAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.decodeString("UTF-8"))
  }
  
  def getPayloadBytes = {
    ByteString.fromString(value, "UTF-8")
  }
}

class IPv4AddressAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: java.net.InetAddress) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, java.net.InetAddress.getByAddress(bytes.slice(0, 4).toArray))
  }
  
  def getPayloadBytes = {
    ByteString.fromArray(value.getAddress())
  }
}

class IPv6AddressAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: java.net.InetAddress) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, java.net.InetAddress.getByAddress(bytes.slice(0, 16).toArray))
  }
  
  def getPayloadBytes = {
    ByteString.fromArray(value.getAddress())
  }
}

class IPv6PrefixAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, {
      // rfc3162
      val it = bytes.iterator
      val prefixLen = it.drop(1).getByte
      val prefix = java.net.InetAddress.getByAddress(it.getBytes(16).toArray)
      prefix.getHostAddress + "/" + prefixLen
    })
  }
  
  def getPayloadBytes = {
    val builder = new ByteStringBuilder
    builder.putByte(0)
    for(m <- DiameterAVP.ipv6PrefixRegex.findFirstMatchIn(value)){
      builder.putByte(m.group(2).toByte);
      builder.putBytes(java.net.InetAddress.getByName(m.group(1)).getAddress);
    }
    builder.result
  }
}


/**
 * DiameterMessage Builder DiameterMessage(ByteString)
 */
object DiameterMessage {
  
  implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN
  
  val DIAMETER_SUCCESS: Int = 2001
  val DIAMETER_LIMITED_SUCCESS: Int = 2002
  val DIAMETER_UNKNOWN_PEER: Int = 3010
	val DIAMETER_UNKNOWN_SESSION_ID = 5002
  val DIAMETER_UNABLE_TO_COMPLY = 5012
  
  /**
   * Builds a Diameter AVP from the received bytes
   */
  def apply(bytes: ByteString) : DiameterMessage = {
    // Diameter Message is
    // 1 byte version
    // 3 byte message length
    // 1 byte flags
    //   request, proxyable, error, retransmission
    // 3 byte command code
    // 4 byte messageId
    // 4 byte End-2-End Identifier
    // 4 byte Hop-by-Hop Identifier
    // ... AVP
    
    val it = bytes.iterator
    // Ignore the version
    it.getByte
    val messageLength = UByteString.getUnsigned24(it)
    val flags = it.getByte
        val isRequest = (flags & 128) > 0
        val isProxyable = (flags & 64) > 0
        val isError = (flags & 32) > 0
        val isRetransmission = (flags & 16) > 0
    val commandCode = UByteString.getUnsigned24(it)
    val applicationId = it.getInt
    val hopByHopId = it. getInt
    val endToEndId = it.getInt
    
    def appendAVPsFromByteIterator(acc: Queue[DiameterAVP[Any]]) : Queue[DiameterAVP[Any]] = {
    		if(it.isEmpty) acc
    		else {
    		  // Iterator to get the bytes of the AVP
    			val clonedIt = it.clone()
    			// Get AVP length, discarding the previous bytes
    			it.getInt   // code
					it.getByte  // flags
					val length = UByteString.getUnsigned24(it)
					// Skip until next AVP, with padding
					it.drop(length + ((4 - length %  4) % 4) - 8)
					
					appendAVPsFromByteIterator(acc :+ DiameterAVP(clonedIt.getByteString(length)))
    		}
    }

    new DiameterMessage(applicationId, commandCode, hopByHopId, endToEndId, appendAVPsFromByteIterator(Queue()), isRequest, isProxyable, isError, isRetransmission)
  }
}

/**
 * Represents a Diameter Message
 */
class DiameterMessage(val applicationId: Int, val commandCode: Int, val hopByHopId: Int, val endToEndId: Int, val avps: Seq[DiameterAVP[Any]], val isRequest: Boolean, val isProxyable: Boolean = true, val isError: Boolean = false, val isRetransmission: Boolean = false) {
  
  implicit val byteOrder = ByteOrder.BIG_ENDIAN  
  
  def getBytes: ByteString = {
    // Diameter Message is
    // 1 byte version
    // 3 byte message length
    // 1 byte flags
    //   request, proxyable, error, retransmission
    // 3 byte command code
    // 4 byte applicationId
    // 4 byte Hop-by-Hop Identifier
    // 4 byte End-2-End Identifier
    // ... AVP
    
    val builder = new ByteStringBuilder()
    // version
    builder.putByte(1) 
    // length will be patched later
    builder.putByte(0).putShort(0)
    // flags
    builder.putByte((0 + (if(isRequest) 128 else 0) + (if(isProxyable) 64 else 0) + (if(isError) 32 else 0) + (if(isRetransmission) 16 else 0)).toByte)
    // command code
    UByteString.putUnsigned24(builder, commandCode)
    // application id
    builder.putInt(applicationId)
    // Hop-by-hop identifier
    builder.putInt(hopByHopId)
    // End-to-End identifier
    builder.putInt(endToEndId)
    
    // Add AVPs
    for(avp <- avps) {
      builder.append(avp.getBytes)
      if(builder.length % 4 != 0) builder.putBytes(new Array[Byte](4 - builder.length % 4))
    }
    
    val result = builder.result
    
    // Write length now   
    result.patch(1, UByteString.putUnsigned24(new ByteStringBuilder(), result.length).result, 3)
  }
}