package diameterServer

import org.json4s._
import akka.util.{ByteString, ByteStringBuilder}
import scala.collection.mutable.ArrayBuffer
import java.nio.ByteOrder.BIG_ENDIAN

object DiameterMessage {
  val DIAMETER_SUCCESS: Int = 2001
  val DIAMETER_LIMITED_SUCCESS: Int = 2002
  val DIAMETER_UNKNOWN_PEER: Int = 3010
	val DIAMETER_UNKNOWN_SESSION_ID = 5002
  val DIAMETER_UNABLE_TO_COMPLY = 5012
}

case class DiameterMessage(applicationId: Int, commandCode: Int, hopByHopId: Int, endToEndId: Int, avps: Seq[DiameterAVP], isRequest: Boolean, isProxyable: Boolean = true, isError: Boolean = false) {
  
}

object DiameterAVP {
  
  def apply(bytes: ByteString) : DiameterAVP = {
    // AVP Header is
    //    code: 4 byte
    //    flags: 1 byte
    //    length: 3 byte
    //    vendorId: 0 / 4 byte
    //    data: rest of bytes
    
    val code = bytes.slice(0, 2).toByteBuffer.getInt*65536 + bytes.slice(2, 4).toByteBuffer.getInt
    val flags = bytes.slice(4, 5).toByteBuffer.get()
    val isMandatory : Boolean = (flags & 128) == 1
    val isVendorSpecific : Boolean = (flags & 64) == 1
    val avpLength : Int = bytes.slice(5, 6).toByteBuffer.get()*65535 + bytes.slice(6, 8).toByteBuffer.getShort()
    val dataOffset = if (isVendorSpecific) 8 else 12 
    val vendorCode = isVendorSpecific match {
          case true => bytes.slice(5, 1).toByteBuffer.get()*256 + bytes.slice(6, 8).toByteBuffer.getInt
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

abstract class DiameterAVP(val code: Long, val isVendorSpecific: Boolean, val isMandatory: Boolean, val vendorId: Long) {
  
  implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN
  
  def toByteString: ByteString = {
    
    val builder = new ByteStringBuilder()
    // TODO: Warning. This will not work for codes bigger than 2^32 -1
    builder.putInt(code.toInt)
    builder.putByte((0 + (if(isVendorSpecific) 128 else 0) + (if(isMandatory) 64 else 0)).toByte)
    // TODO: Write length later
    builder.putByte(0)
    builder.putInt(0)
    
    builder.result()
    
  }
  
  def addPayload(builder: ByteStringBuilder)
}

class OctetStringAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: Array[Byte]) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, data: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, data.toArray)
  }
  
  def addPayload(builder: ByteStringBuilder) = {
    builder.putBytes(value)
  }
}

class Integer32AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: Int) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, data: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, data.toByteBuffer.getInt)
  }
  
  def addPayload(builder: ByteStringBuilder) = {
    builder.putInt(value)
  }
}

class Integer64AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, data: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, data.toByteBuffer.getLong)
  }
  
  def addPayload(builder: ByteStringBuilder) = {
    builder.putLong(value)
  }
}

class UnsignedInteger32AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, data: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, 
        data.slice(0, 2).toByteBuffer.getInt * 65536L + data.slice(2, 4).toByteBuffer.getInt)
  }
  
  def addPayload(builder: ByteStringBuilder) = {
    // TODO: Check this
    if(value < 2147483647) builder.putInt(value.toInt) else builder.putInt((4294967296L-value).toInt)
  }
}

class UnsignedInteger64AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, data: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, data.toByteBuffer.getLong)
  }
  
  def addPayload(builder: ByteStringBuilder) = {
    // We have a problem here
    builder.putLong(value.toInt)
  }
}

class Float32AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: Float) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, data: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, data.toByteBuffer.getFloat)
  }
  
  def addPayload(builder: ByteStringBuilder) = {
    builder.putFloat(value)
  }
}
class Float64AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: Double) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, data: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, data.toByteBuffer.getDouble)
  }
  
  def addPayload(builder: ByteStringBuilder) = {
    builder.putDouble(value)
  }
}

class GroupedAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: Seq[DiameterAVP]) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, data: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, {
        val avps = ArrayBuffer[DiameterAVP]()
        var idx = 0
        while(idx < data.length){
          val l = data.slice(idx + 5, idx + 6).toByteBuffer.get()*65535 + data.slice(idx + 6, idx + 8).toByteBuffer.getShort()
          avps += DiameterAVP(data.slice(idx, idx +  l))
          idx += l + l%4
        }
        avps
    })
  }
  
  def addPayload(builder: ByteStringBuilder) = {
    for(avp <- value){
      builder.append(avp.toByteString)
      // Padding
      val padLen = 4 - builder.length % 4
      if(padLen != 4) builder.putBytes(new Array[Byte](padLen))
    }
  }
}

class AddressAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, data: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, java.net.InetAddress.getByAddress(data.drop(2).toArray).getHostAddress())
  }
  
  def addPayload(builder: ByteStringBuilder) = {
    builder.putBytes(java.net.InetAddress.getByName(value).getAddress())
  }
}

class TimeAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: java.util.Date) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, data: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, 
        new java.util.Date(data.slice(0, 2).toByteBuffer.getInt * 65536L + data.slice(2, 4).toByteBuffer.getInt)
        )
  }
  
  def addPayload(builder: ByteStringBuilder) = {
    builder.putLong(value.getTime())
  }
}


class UTF8StringAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, data: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, data.decodeString("UTF-8"))
  }
  
  def addPayload(builder: ByteStringBuilder) = {
    builder.putBytes(value.getBytes("UTF-8"))
  }
}
class DiameterIdentityAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, data: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, data.decodeString("UTF-8"))
  }
  
  def addPayload(builder: ByteStringBuilder) = {
    builder.putBytes(value.getBytes("UTF-8"))
  }
}
class DiameterURIAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, data: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, data.decodeString("UTF-8"))
  }
  
  def addPayload(builder: ByteStringBuilder) = {
    builder.putBytes(value.getBytes("UTF-8"))
  }
}

class EnumeratedAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: Int) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, data: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, data.slice(0, 4).toByteBuffer.getInt)
  }
  
  def addPayload(builder: ByteStringBuilder) = {
    builder.putInt(value)
  }
}

class IPFilterRuleAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, data: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, data.decodeString("UTF-8"))
  }
  
  def addPayload(builder: ByteStringBuilder) = {
    builder.putBytes(value.getBytes("UTF-8"))
  }
}

class IPV4AddressAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, data: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, java.net.InetAddress.getByAddress(data.slice(0, 4).toArray).getHostAddress())
  }
  
  def addPayload(builder: ByteStringBuilder) = {
    builder.putBytes(java.net.InetAddress.getByName(value).getAddress())
  }
}

class IPV6AddressAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, data: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, java.net.InetAddress.getByAddress(data.slice(0, 16).toArray).getHostAddress())
  }
  
  def addPayload(builder: ByteStringBuilder) = {
    builder.putBytes(java.net.InetAddress.getByName(value).getAddress())
  }
}

class IPV6PrefixAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, data: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, {
      // rfc3162
      val prefixLen = data.slice(1, 2).toByteBuffer.getInt
      val prefixBytes = data.drop(2).toArray
      // Fill until 16 bytes
      val prefix = java.net.InetAddress.getByAddress(prefixBytes.padTo[Byte, Array[Byte]](16, 0))
      prefix.getHostAddress + "/" + prefixLen
    })
  }
  
  def addPayload(builder: ByteStringBuilder) = {
    val ipv6PrefixRegex = """(.+)/([0-9]+) """.r
    builder.putByte(0)
    for(m <- ipv6PrefixRegex.findFirstMatchIn(value)){
      builder.putByte(m.group(2).toByte)
      builder.putBytes(java.net.InetAddress.getByName(m.group(1)).getAddress)
    }
  }
}