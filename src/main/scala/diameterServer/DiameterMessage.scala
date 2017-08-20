package diameterServer

import org.json4s._
import akka.util.{ByteString, ByteStringBuilder}
import scala.collection.mutable.ArrayBuffer

object DiameterMessage {
  val DIAMETER_SUCCESS: Int = 2001
  val DIAMETER_LIMITED_SUCCESS: Int = 2002
  val DIAMETER_UNKNOWN_PEER: Int = 3010
	val DIAMETER_UNKNOWN_SESSION_ID = 5002
  val DIAMETER_UNABLE_TO_COMPLY = 5012
  
  // Builds a Diameter AVP from the received bytes
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
    
    val messageLength = UByteString.getUnsigned24(bytes.slice(1, 4))
    val flags = bytes.slice(4, 5).toByteBuffer.get
    val isRequest = (flags & 128) > 0
    val isProxyable = (flags & 64) > 0
    val isError = (flags & 32) > 0
    val isRetransmission = (flags & 16) > 0
    val commandCode = UByteString.getUnsigned24(bytes.slice(5, 8))
    val applicationId = bytes.slice(8, 12).toByteBuffer.getInt
    val endToEndId = bytes.slice(12, 16).toByteBuffer.getInt
    val hopByHopId = bytes.slice(16, 20).toByteBuffer.getInt
    
    // Get Avps until buffer exhausted
    val avps = ArrayBuffer[DiameterAVP]()
    var idx = 20
    while(idx < bytes.length){
      val l = UByteString.getUnsigned24(bytes.slice(idx + 5, idx + 8))
      avps += DiameterAVP(bytes.slice(idx, idx +  l))
      idx += l + l%4
    }

    new DiameterMessage(applicationId, commandCode, hopByHopId, endToEndId, avps, isRequest, isProxyable, isError, isRetransmission)
  }
}

class DiameterMessage(val applicationId: Int, val commandCode: Int, val hopByHopId: Int, val endToEndId: Int, val avps: Seq[DiameterAVP], val isRequest: Boolean, val isProxyable: Boolean = true, val isError: Boolean = false, val isRetransmission: Boolean = false) {
  
  implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN
  
  def getBytes: ByteString = {
    // Diameter Message is
    // 1 byte version
    // 3 byte message length
    // 1 byte flags
    //   request, proxyable, error, retransmission
    // 3 byte command code
    // 4 byte applicationId
    // 4 byte End-2-End Identifier
    // 4 byte Hop-by-Hop Identifier
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
    // End-to-End identifier
    builder.putInt(endToEndId)
    // Hop-by-hop identifier
    builder.putInt(hopByHopId)
    
    val result = builder.result
    println("1 "+result)
    
    // Write length now
    val length = result.length
    val lBuilder = new ByteStringBuilder()
    UByteString.putUnsigned24(lBuilder, length)
    result.patch(1, lBuilder.result, 3)
  }
}

object DiameterAVP {
  
  val ipv6PrefixRegex = """(.+)/([0-9]+) """.r
  
  // Builds a Diameter AVP from the received bytes
  def apply(bytes: ByteString) : DiameterAVP = {
    // AVP Header is
    //    code: 4 byte
    //    flags: 1 byte
    //    length: 3 byte
    //    vendorId: 0 / 4 byte
    //    data: rest of bytes
    
    val code = bytes.toByteBuffer.getInt
    val flags = bytes.slice(4, 5).toByteBuffer.get()
    val isMandatory : Boolean = (flags & 128) > 0 
    val isVendorSpecific : Boolean = (flags & 64) > 0
    val avpLength : Int = UByteString.getUnsigned24(bytes.slice(5, 8))
    val dataOffset = if (isVendorSpecific) 8 else 12 
    val vendorCode = isVendorSpecific match {
          case true => bytes.slice(8, 12).toByteBuffer.getInt
          case false => 0
    }
    
    val data = bytes.slice(dataOffset, avpLength)
    
    DiameterDictionary.avpMapByCode((vendorCode, code)).diameterType match {
      case AVPDictionaryItem.OCTETSTRING => 
        new OctetStringAVP(code, isMandatory, isVendorSpecific, vendorCode, data)
        
      case AVPDictionaryItem.INTEGER_32 =>
        new Integer32AVP(code, isMandatory, isVendorSpecific, vendorCode, data)
      
      case AVPDictionaryItem.INTEGER_64 =>
        new Integer64AVP(code, isMandatory, isVendorSpecific, vendorCode, data)
      
      case AVPDictionaryItem.UNSIGNED_32 =>
        new UnsignedInteger32AVP(code, isMandatory, isVendorSpecific, vendorCode, data)
      
      case AVPDictionaryItem.UNSIGNED_64 =>
        // Problem here. Only signed is supported
        new UnsignedInteger64AVP(code, isMandatory, isVendorSpecific, vendorCode, data)
      
      case AVPDictionaryItem.FLOAT_32 =>
        new Float32AVP(code, isMandatory, isVendorSpecific, vendorCode, data)
      
      case AVPDictionaryItem.FLOAT_64 =>
        new Float64AVP(code, isMandatory, isVendorSpecific, vendorCode, data)
      
      case AVPDictionaryItem.GROUPED =>
        new GroupedAVP(code, isMandatory, isVendorSpecific, vendorCode, data)
      
      case AVPDictionaryItem.ADDRESS =>
        new AddressAVP(code, isMandatory, isVendorSpecific, vendorCode, data)
      
      case AVPDictionaryItem.TIME =>
        new TimeAVP(code, isMandatory, isVendorSpecific, vendorCode, data)
      
      case AVPDictionaryItem.UTF8STRING =>
        new UTF8StringAVP(code, isMandatory, isVendorSpecific, vendorCode, data)
        
      case AVPDictionaryItem.DIAMETERIDENTITY =>
        new DiameterIdentityAVP(code, isMandatory, isVendorSpecific, vendorCode, data)
        
      case AVPDictionaryItem.DIAMETERURI =>
        // TODO: Check syntax using regex
        new DiameterURIAVP(code, isMandatory, isVendorSpecific, vendorCode, data)
        
      case AVPDictionaryItem.ENUMERATED =>
        new EnumeratedAVP(code, isMandatory, isVendorSpecific, vendorCode, data)
        
      case AVPDictionaryItem.IPFILTERRULE =>
        // TODO: Check syntax using regex
        new IPFilterRuleAVP(code, isMandatory, isVendorSpecific, vendorCode, data)
      
      case AVPDictionaryItem.RADIUS_IPV4ADDRESS =>
        new IPV4AddressAVP(code, isMandatory, isVendorSpecific, vendorCode, data)

      case AVPDictionaryItem.RADIUS_IPV6ADDRESS =>
        new IPV6AddressAVP(code, isMandatory, isVendorSpecific, vendorCode, data)

      case AVPDictionaryItem.RADIUS_IPV6PREFIX =>
        new IPV6PrefixAVP(code, isMandatory, isVendorSpecific, vendorCode, data)

      case _ => throw new Exception()
    }
  }
}

abstract class DiameterAVP(val code: Int, val isVendorSpecific: Boolean, val isMandatory: Boolean, val vendorId: Int) {
  
  implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN
  
  def getBytes: ByteString = {
    
    val builder = new ByteStringBuilder()
    // AVP Header is
    //    code: 4 byte
    //    flags: 1 byte
    //    length: 3 byte
    //    vendorId: 0 / 4 byte
    //    data: rest of bytes
    
    // TODO: Warning. This will not work for codes bigger than 2^32 -1
    // code
    builder.putInt(code.toInt)
    // flags
    builder.putByte((0 + (if(isVendorSpecific) 128 else 0) + (if(isMandatory) 64 else 0)).toByte)
    // length
    // TODO: Write length later
    builder.putByte(0).putInt(0)
    // vendorId
    if(vendorId !=0) builder.putInt(0)
    // data
    val payloadBytes = getPayloadBytes
    builder.append(getPayloadBytes)
    
    val result = builder.result
    // Write length now
    val length = if(vendorId == 0) 8 + payloadBytes.length else 12 + payloadBytes.length
    val lBuilder = new ByteStringBuilder()
    UByteString.putUnsigned24(lBuilder, length)
    result.patch(5, lBuilder.result, 3)
  }
  
  // Serializes the payload only
  def getPayloadBytes: ByteString
}

class OctetStringAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, value: Array[Byte]) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.toArray)
  }
  
  def getPayloadBytes = {
    ByteString.fromArray(value)
  }
}

class Integer32AVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, val value: Int) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.toByteBuffer.getInt)
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putInt(value).result
  } 
}

class Integer64AVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, val value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.toByteBuffer.getLong)
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putLong(value).result
  } 
}

class UnsignedInteger32AVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, val value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, 
        bytes.slice(0, 2).toByteBuffer.getInt * 65536L + bytes.slice(2, 4).toByteBuffer.getInt)
  }
  
  def getPayloadBytes = {
    // TODO: Check this
    val builder = new ByteStringBuilder()
    if(value < 2147483647) builder.putInt(value.toInt) else builder.putInt((4294967296L-value).toInt)
    builder.result
  }
}

class UnsignedInteger64AVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, val value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.toByteBuffer.getLong)
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putLong(value).result
  } 
}

class Float32AVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, val value: Float) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.toByteBuffer.getFloat)
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putFloat(value).result
  } 
}
class Float64AVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, val value: Double) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.toByteBuffer.getDouble)
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putDouble(value).result
  } 
}

class GroupedAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, val value: Seq[DiameterAVP]) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, {
        val avps = ArrayBuffer[DiameterAVP]()
        var idx = 0
        while(idx < bytes.length){
          val l = UByteString.getUnsigned24(bytes.slice(idx + 5, idx + 8))
          avps += DiameterAVP(bytes.slice(idx, idx +  l))
          idx += l + l%4
        }
        avps
    })
  }
  
  def getPayloadBytes = {
    val builder = new ByteStringBuilder()
    for(avp <- value){
      val avpByteString = avp.getBytes
      builder.append(avpByteString)
      // Padding
      val padLen = (4 - builder.length % 4) % 4
      builder.putBytes(new Array[Byte](padLen))
    }
    builder.result
  }
}

class AddressAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, java.net.InetAddress.getByAddress(bytes.drop(2).toArray).getHostAddress())
  }
  
  def getPayloadBytes = {
    ByteString.fromArray(java.net.InetAddress.getByName(value).getAddress())
  } 
}

class TimeAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, val value: java.util.Date) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, 
        new java.util.Date(bytes.slice(0, 2).toByteBuffer.getInt * 65536L + bytes.slice(2, 4).toByteBuffer.getInt)
        )
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putLong(value.getTime()).result
  }
}

class UTF8StringAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.decodeString("UTF-8"))
  }
  
  def getPayloadBytes = {
    ByteString.fromString(value, "UTF-8")
  }
}
class DiameterIdentityAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.decodeString("UTF-8"))
  }
  
  def getPayloadBytes = {
    ByteString.fromString(value, "UTF-8")
  }
}
class DiameterURIAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.decodeString("UTF-8"))
  }
  
  def getPayloadBytes = {
    ByteString.fromString(value, "UTF-8")
  }
}

class EnumeratedAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, val value: Int) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.slice(0, 4).toByteBuffer.getInt)
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putInt(value).result
  }
}

class IPFilterRuleAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.decodeString("UTF-8"))
  }
  
  def getPayloadBytes = {
    ByteString.fromString(value, "UTF-8")
  }
}

class IPV4AddressAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, java.net.InetAddress.getByAddress(bytes.slice(0, 4).toArray).getHostAddress())
  }
  
  def getPayloadBytes = {
    ByteString.fromArray(java.net.InetAddress.getByName(value).getAddress())
  }
}

class IPV6AddressAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, java.net.InetAddress.getByAddress(bytes.slice(0, 16).toArray).getHostAddress())
  }
  
  def getPayloadBytes = {
    ByteString.fromArray(java.net.InetAddress.getByName(value).getAddress())
  }
}

class IPV6PrefixAVP(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Int, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Int, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, {
      // rfc3162
      val prefixLen = bytes.slice(1, 2).toByteBuffer.getInt
      val prefixBytes = bytes.drop(2).toArray
      // Fill until 16 bytes
      val prefix = java.net.InetAddress.getByAddress(prefixBytes.padTo[Byte, Array[Byte]](16, 0))
      prefix.getHostAddress + "/" + prefixLen
    })
  }
  
  def getPayloadBytes = {
    val builder = new ByteStringBuilder
    builder.putByte(0)
    for(m <- DiameterAVP.ipv6PrefixRegex.findFirstMatchIn(value)){
      builder.putByte(m.group(2).toByte)
      builder.putBytes(java.net.InetAddress.getByName(m.group(1)).getAddress)
    }
    builder.result
  }
}