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
    val avpOffset = if (isVendorSpecific) 8 else 12 
    val vendorCode = isVendorSpecific match {
          case true => bytes.slice(5, 1).toByteBuffer.get()*256 + bytes.slice(6, 8).toByteBuffer.getInt
          case false => 0
    }
    
    DiameterDictionary.avpMapByCode((vendorCode, code)).diameterType match {
      case AVPDictionaryItem.OCTETSTRING => 
        new OctetStringAVP(code, isMandatory, isVendorSpecific, vendorCode, bytes.slice(avpOffset, avpLength))
      case AVPDictionaryItem.INTEGER_32 =>
        new Integer32AVP(code, isMandatory, isVendorSpecific, vendorCode, bytes.slice(avpOffset, avpOffset + 4).toByteBuffer.getInt)
      case AVPDictionaryItem.INTEGER_64 =>
        new Integer64AVP(code, isMandatory, isVendorSpecific, vendorCode, bytes.slice(avpOffset, avpOffset + 8).toByteBuffer.getLong)
      case AVPDictionaryItem.UNSIGNED_32 =>
        new UnsignedInteger32AVP(code, isMandatory, isVendorSpecific, vendorCode, bytes.slice(avpOffset, avpOffset + 2).toByteBuffer.getInt*65536L + bytes.slice(avpOffset + 2, avpOffset + 4).toByteBuffer.getInt)
      case AVPDictionaryItem.UNSIGNED_64 =>
        // Problem here. Only signed is supported
        new UnsignedInteger64AVP(code, isMandatory, isVendorSpecific, vendorCode, bytes.slice(avpOffset, avpOffset + 8).toByteBuffer.getLong)
      case AVPDictionaryItem.FLOAT_32 =>
        new Float32AVP(code, isMandatory, isVendorSpecific, vendorCode, bytes.slice(avpOffset, avpLength).toByteBuffer.getFloat)
      case AVPDictionaryItem.FLOAT_64 =>
        new Float64AVP(code, isMandatory, isVendorSpecific, vendorCode, bytes.slice(avpOffset, avpLength).toByteBuffer.getDouble)
      case AVPDictionaryItem.GROUPED =>
        val avps = ArrayBuffer[DiameterAVP]()
        var idx = avpOffset
        while(idx < avpLength){
          val l = bytes.slice(idx + 5, idx + 6).toByteBuffer.get()*65535 + bytes.slice(idx + 6, idx + 8).toByteBuffer.getShort()
          avps += DiameterAVP(bytes.slice(idx, idx +  l))
          idx += l + l%4
        }
        new GroupedAVP(code, isMandatory, isVendorSpecific, vendorCode, avps)
      case _ => throw new Exception()
    }
    
  }
}

class DiameterAVP(val code: Long, val isVendorSpecific: Boolean, val isMandatory: Boolean, val vendorId: Long) {
  
  //def toByteString: ByteString
}

class OctetStringAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: Seq[Byte]) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId)
class Integer32AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: Int) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId)
class Integer64AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId)
class UnsignedInteger32AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId)
class UnsignedInteger64AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId) // We have a problem here
class Float32AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: Float) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId)
class Float64AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: Double) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId)
class GroupedAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: Seq[DiameterAVP]) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId)
class AddressAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId)
class TimeAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: java.util.Date) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId)
class UTF8StringAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId)
class DiameterIdentityAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId)
class DiameterURIAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId)
class EnumeratedAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: Int) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId)
class IPFilterRuleAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, val value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId)
