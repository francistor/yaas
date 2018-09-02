package yaas.coding

import java.nio.ByteOrder
import akka.util.{ByteString, ByteStringBuilder, ByteIterator}

import yaas.dictionary._
import yaas.config.DiameterConfigManager
import yaas.util.IDGenerator
import yaas.util.UByteString
import yaas.util.OctetOps

import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

import scala.collection.immutable.Queue

class DiameterCodingException(val msg: String) extends java.lang.Exception(msg: String)

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
    
    val it = bytes.iterator
    val code = UByteString.getUnsigned32(it)
    val flags = UByteString.getUnsignedByte(it)
    val isVendorSpecific : Boolean = (flags & 0x80) > 0
    val isMandatory : Boolean = (flags & 0x40) > 0 
    val avpLength = UByteString.getUnsigned24(it)
    val dataOffset = if (isVendorSpecific) 12 else 8 
    val vendorId = isVendorSpecific match {
          case true => UByteString.getUnsigned32(it)
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

abstract class DiameterAVP[+A](val code: Long, val isVendorSpecific: Boolean, var isMandatory: Boolean, val vendorId: Long, val value: A){
  
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
    if(vendorId !=0) UByteString.putUnsigned32(builder, vendorId)
    // data
    builder.append(payloadBytes)
    
    // Pad to 4 byte boundary
    if(length % 4 != 0) builder.putBytes(new Array[Byte](4 - length % 4))
    
    builder.result()
  }
  
  // Serializes the payload only
  def getPayloadBytes: ByteString
  
  override def equals(other: Any): Boolean = {
    other match {
      case x: DiameterAVP[Any] =>
        if(x.code != code || x.isVendorSpecific != isVendorSpecific || x.isMandatory != isMandatory || !x.value.equals(value)) false else true
      case _ => false
    }
  }
  
  // To be overriden in concrete classes
  def stringValue = value.toString()
  
  // Want the stringified AVP be the value, so toString reports only the value
  // and there is an implicit conversion that does the same
  override def toString = stringValue
  
  /*
   * Print the AVP in [name -> value] format
   * With special treatment for Grouped and Enumerated attributes
   */
  def pretty(indent: Int = 0) : String = {
    val dictItem = DiameterDictionary.avpMapByCode.get((vendorId, code)).getOrElse(BasicAVPDictItem(0, 0, "Unknown", DiameterTypes.NONE))
    val attributeName = dictItem.name
    
    val attributeValue = this match {
      case thisAVP : GroupedAVP => thisAVP.value.foldRight("\n")((avp, acc) => acc + avp.pretty(indent + 1) + "\n")
      case thisAVP : EnumeratedAVP =>
        dictItem match {
          case di : EnumeratedAVPDictItem =>
            di.values.map(_.swap).getOrElse(thisAVP.value, thisAVP.value.toString)
          case _ => "ERROR"
        }
      case _ => stringValue
    }
    
    val tab = "  " * indent

    s"$tab[$attributeName = $attributeValue]"
  } 
  
  def getName = {
    DiameterDictionary.avpMapByCode.get((vendorId, code)).map(_.name).getOrElse("UNKNOWN")
  }
  
  def getType = {
    DiameterDictionary.avpMapByCode.get((vendorId, code)).map(_.diameterType).getOrElse(DiameterTypes.NONE)
  }
}

class UnknownAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: List[Byte]) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
    // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.toList)
  }
  
  def getPayloadBytes = {
    ByteString.fromArray(value.toArray)
  }
  
  override def stringValue = {
    OctetOps.octetsToString(value)
  }
}

class OctetStringAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: List[Byte]) extends DiameterAVP[List[Byte]](code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.toList)
  }
  
  def getPayloadBytes = {
    // Pad to multiple of 4 bytes
    // val paddedLen : Int = if (value.length ==0) 0 else (value.length / 4 + 1) * 4 
    // ByteString.fromArray(value.padTo[Byte, Array[Byte]](paddedLen, 0))
    ByteString.fromArray(value.toArray)
  }
  
  override def stringValue = {
    OctetOps.octetsToString(value)
  }
}

class Integer32AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: Int) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString)(implicit byteOrder: ByteOrder){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getInt(byteOrder))
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putInt(value).result
  } 
  
  override def stringValue = {
    value.toString
  }
}

class Integer64AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString)(implicit byteOrder: ByteOrder){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getLong(byteOrder))
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putLong(value).result
  } 
  
  override def stringValue = {
    value.toString
  }
}

class Unsigned32AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString)(implicit byteOrder: ByteOrder) {
    this(code, isVendorSpecific, isMandatory, vendorId, UByteString.getUnsigned32(bytes))
  }
  
  def getPayloadBytes = {
    UByteString.putUnsigned32(new ByteStringBuilder(), value).result
  }
  
  override def stringValue = {
    value.toString
  }
}

// This class does not correctly represents integers bigger than 2exp63
class Unsigned64AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString)(implicit byteOrder: ByteOrder) {
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getLong)
  }
  
  def getPayloadBytes = {
    UByteString.putUnsigned64(new ByteStringBuilder(), value).result
  } 
  
  override def stringValue = {
    value.toString
  }
}

class Float32AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: Float) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString)(implicit byteOrder: ByteOrder){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getFloat)
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putFloat(value).result
  } 
  
  override def stringValue = {
    value.toString
  }
}

class Float64AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: Double) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString)(implicit byteOrder: ByteOrder){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getDouble)
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putDouble(value).result
  } 

  override def stringValue = {
    value.toString
  }
}

class GroupedAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: scala.collection.mutable.Queue[DiameterAVP[Any]]) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, {
        var avps = scala.collection.mutable.Queue[DiameterAVP[Any]]()
        var idx = 0
        while(idx < bytes.length){
          val l = UByteString.getUnsigned24(bytes.slice(idx + 5, idx + 8))
          val theNextAVP = DiameterAVP(bytes.slice(idx, idx +  l))
          avps += theNextAVP
          idx += (l + (4 - l % 4) % 4)
        }
        avps
    })
  }
  
  def << (avp: DiameterAVP[Any]) : GroupedAVP = {
    value += avp
    this
  }
  
  def >> (attrName: String) : Option[DiameterAVP[Any]] = {
    DiameterDictionary.avpMapByName.get(attrName).map(_.code) match {
      case Some(code) => value.find(avp => avp.code == code)
      case _ => None
    }
  }
  
  def getPayloadBytes = {
    val builder = new ByteStringBuilder()
    for(avp <- value){
      builder.append(avp.getBytes)
      // Padding
      if(builder.length % 4 != 0) builder.putBytes(new Array[Byte](4  - (builder.length % 4)))
    }
    builder.result
  }
  
  // TODO: Review this. Prints only the nested values, but not the names
  override def stringValue = {
    value match {
      case avps: Seq[Any] => avps.mkString(", ")
      case _ => "Error"
    }
  }
}

class AddressAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: java.net.InetAddress) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, java.net.InetAddress.getByAddress(bytes.drop(2).toArray))
  }
  
  def getPayloadBytes = {
    val bsb = new ByteStringBuilder()
    // First 2 octets are <1> if IPv4 and <2> if IPv6
    if(value.isInstanceOf[java.net.Inet4Address]) bsb.putShort(1) else bsb.putShort(2)
    bsb.putBytes(value.getAddress()).result
  } 
  
  override def stringValue = {
    value.getHostAddress()
  }
}

class TimeAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: java.util.Date) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString)(implicit byteOrder: ByteOrder){
    this(code, isVendorSpecific, isMandatory, vendorId, new java.util.Date(bytes.iterator.getLong))
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putLong(value.getTime()).result
  }
  
  override def stringValue = {
    val sdf = new java.text.SimpleDateFormat("yyyy-MM-dd hh:mm:ss")
    sdf.format(value)
  }
}

class UTF8StringAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.decodeString("UTF-8"))
  }
  
  def getPayloadBytes = {
    ByteString.fromString(value, "UTF-8")
  }
  
  override def stringValue = {
    value
  }
}

class DiameterIdentityAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.decodeString("UTF-8"))
  }
  
  def getPayloadBytes = {
    ByteString.fromString(value, "UTF-8")
  }
  
  override def stringValue = {
    value
  }
}

class DiameterURIAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.decodeString("UTF-8"))
  }
  
  def getPayloadBytes = {
    ByteString.fromString(value, "UTF-8")
  }
  
  override def stringValue = {
    value
  }
}

class EnumeratedAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: Int) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString)(implicit byteOrder: ByteOrder){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getInt)
  }
  
  def getPayloadBytes = {
    new ByteStringBuilder().putInt(value).result
  }
  
  override def stringValue = {
    DiameterDictionary.avpMapByCode.get((vendorId, code)) match {
      case Some(EnumeratedAVPDictItem(code, vendorId, name, diameterType, values, codes)) => codes.getOrElse(value, "Unkown")
      case _ => "Unknown"
    }
  }
}

class IPFilterRuleAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.decodeString("UTF-8"))
  }
  
  def getPayloadBytes = {
    ByteString.fromString(value, "UTF-8")
  }
  
  override def stringValue = {
    value
  }
}

class IPv4AddressAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: java.net.InetAddress) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, java.net.InetAddress.getByAddress(bytes.slice(0, 4).toArray))
  }
  
  def getPayloadBytes = {
    ByteString.fromArray(value.getAddress())
  }
  
  override def stringValue = {
    value.getHostAddress()
  }
}

class IPv6AddressAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: java.net.InetAddress) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, java.net.InetAddress.getByAddress(bytes.slice(0, 16).toArray))
  }
  
  def getPayloadBytes = {
    ByteString.fromArray(value.getAddress())
  }
  
  override def stringValue = {
    value.getHostAddress()
  }
}

class IPv6PrefixAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  // Secondary constructor from bytes
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, {
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
    for(m <- DiameterAVP.ipv6PrefixRegex.findFirstMatchIn(value)){
      builder.putByte(m.group(2).toByte);
      builder.putBytes(java.net.InetAddress.getByName(m.group(1)).getAddress);
    }
    builder.result
  }
  
  override def stringValue = {
    value
  }
}

/**
 * DiameterMessage Builder DiameterMessage(ByteString)
 */
object DiameterMessage {
  
  import DiameterConversions._
  implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN
  
  // Success
  val DIAMETER_SUCCESS = 2001
  val DIAMETER_LIMITED_SUCCESS = 2002
  
  // Protocol Errors
  val DIAMETER_UNKNOWN_PEER = 3010
  val DIAMETER_REALM_NOT_SERVED = 3003
  
  // Transient Failures
  val DIAMETER_AUTHENTICATION_REJECTED = 4001
  
  // Permanent failures
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
  
  /**
   * Builds a new Diameter Request with the specified application and command names, setting the 
   * identifiers and flags to default values and empty attribute list
   */
  def request(applicationName : String, commandName: String)(implicit idGen: IDGenerator) = {
    val diameterConfig = DiameterConfigManager.getDiameterConfig
    val applicationDictItem = DiameterDictionary.appMapByName(applicationName)
    
    val requestMessage = new DiameterMessage(applicationDictItem.code, applicationDictItem.commandMapByName(commandName).code, 
        idGen.nextHopByHopId, idGen.nextEndToEndId, Queue(), true, true, false, false)
    
    requestMessage << ("Origin-Host" -> diameterConfig.diameterHost)
    requestMessage << ("Origin-Realm" -> diameterConfig.diameterRealm) 
    
    requestMessage
  }
  
  /**
   * Builds a Diameter Answer to the specified request with empty attribute list
   */
  def answer(request: DiameterMessage) = {
    val diameterConfig = DiameterConfigManager.getDiameterConfig
    val answerMessage = new DiameterMessage(request.applicationId, request.commandCode, request.hopByHopId, request.endToEndId, Queue(), false, true, false, false)
    answerMessage << ("Origin-Host" -> diameterConfig.diameterHost)
    answerMessage << ("Origin-Realm" -> diameterConfig.diameterRealm) 
  }
}

/**
 * Represents a Diameter Message
 */
class DiameterMessage(val applicationId: Long, val commandCode: Int, val hopByHopId: Int, val endToEndId: Int, var avps: Queue[DiameterAVP[Any]], val isRequest: Boolean, val isProxyable: Boolean = true, val isError: Boolean = false, val isRetransmission: Boolean = false) {
  
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
    UByteString.putUnsigned32(builder, applicationId)
    // Hop-by-hop identifier
    builder.putInt(hopByHopId)
    // End-to-End identifier
    builder.putInt(endToEndId)
    
    // Set flags and filter according to dictionary
    val commandDictItem = DiameterDictionary.appMapByCode(applicationId).commandMapByCode(commandCode)
    val commandAVPMap = if(isRequest) commandDictItem.request.avpCodeMap else commandDictItem.response.avpCodeMap
   
    // Add AVPs. Ignore avp if not in dictionary for this application & command
    for(avp <- avps if commandAVPMap.get((avp.vendorId, avp.code)).isDefined) {
      if(commandAVPMap((avp.vendorId, avp.code)).isMandatory) avp.isMandatory=true
      builder.append(avp.getBytes)
      if(builder.length % 4 != 0) builder.putBytes(new Array[Byte](4 - builder.length % 4))
    }
    
    val result = builder.result
    
    // Write length now   
    result.patch(1, UByteString.putUnsigned24(new ByteStringBuilder(), result.length).result, 3)
  }
  
  /**
   * Insert AVP in message
   */  
  def << (avp: DiameterAVP[Any]) : DiameterMessage = {
    avps = avps :+ avp
    this
  }
  
  def <<< (avp: GroupedAVP) : DiameterMessage = {
    avps = avps :+ avp
    this
  }
  
  /**
   * Extract AVP from message
   */
  def >> (attributeName: String) : Option[DiameterAVP[Any]] = {
    DiameterDictionary.avpMapByName.get(attributeName).map(_.code) match {
      case Some(code) => avps.find(avp => avp.code == code)
      case None => None
    }
  }
    
  def >>> (attributeName: String) : Option[GroupedAVP] = {
    DiameterDictionary.avpMapByName.get(attributeName).map(_.code) match {
      case Some(code) => 
        val avp = avps.find(avp => avp.code == code)
        if(avp.isDefined){
          avp.get match {
            case v: GroupedAVP => Some(v)
            case _ => None
          }
        }
        else None
      case None => None
    }
  } 
  
  def application : String = {
    DiameterDictionary.appMapByCode.get(applicationId).map(_.name).getOrElse("Unknown")
  }
  
  def command: String = {
    DiameterDictionary.appMapByCode.get(applicationId).map(_.commandMapByCode.get(commandCode).map(_.name)).flatten.getOrElse("Unknown")
  }
  
  override def toString = {
    val header = s"req: $isRequest, pxabl: $isProxyable, err: $isError, ret: $isRetransmission, hbhId: $hopByHopId, e2eId: $endToEndId"
    val application = DiameterDictionary.appMapByCode.get(applicationId)
    val applicationName = application.map(_.name).getOrElse("Unknown")
    val commandName = application.map(_.commandMapByCode.get(commandCode).map(_.name)).flatten.getOrElse("Unknown")
    val prettyAVPs = avps.foldRight("")((avp, acc) => acc + avp.pretty() + "\n")
    
    s"\n$applicationName - $commandName\n$header\n$prettyAVPs"
  }
  
  override def equals(other: Any): Boolean = {
    other match {
      case x: DiameterMessage =>
        if( x.applicationId != applicationId || 
            x.commandCode != commandCode || 
            x.endToEndId != endToEndId ||
            x.hopByHopId != hopByHopId ||
            x.isError != isError ||
            x.isRequest != isRequest ||
            x.isProxyable != isProxyable ||
            x.isRetransmission != isRetransmission ||
            !x.avps.equals(avps)) false else true
      case _ => false
    }
  }
}


object DiameterConversions {
  
  implicit var jsonFormats = DefaultFormats
  
  /**
   * Simple Diameter AVP to String (value)
   */
  implicit def DiameterAVP2String(avp: Option[DiameterAVP[Any]]) : String = {
    avp match {
      case Some(v) => v.stringValue
      case None => ""
    }
  }
  
  /**
   * Simple Diameter AVP from tuple (name, value)
   */
  implicit def Tuple2DiameterAVP(tuple : (String, String)) : DiameterAVP[Any] = {
    val (attrName, attrValue) = tuple
    
    val dictItem = DiameterDictionary.avpMapByName(attrName)
    val code = dictItem.code
    val isVendorSpecific = dictItem.vendorId != 0
    val isMandatory = false
    val vendorId = dictItem.vendorId
    
    dictItem.diameterType match {
      case DiameterTypes.OCTETSTRING => 
        new OctetStringAVP(code, isVendorSpecific, isMandatory, vendorId, OctetOps.stringToOctets(attrValue))
        
      case DiameterTypes.INTEGER_32 =>
        new Integer32AVP(code, isVendorSpecific, isMandatory, vendorId, attrValue.toInt)
      
      case DiameterTypes.INTEGER_64 =>
        new Integer64AVP(code, isVendorSpecific, isMandatory, vendorId, attrValue.toLong)
      
      case DiameterTypes.UNSIGNED_32 =>
        new Unsigned32AVP(code, isVendorSpecific, isMandatory, vendorId, attrValue.toLong)
      
      case DiameterTypes.UNSIGNED_64 =>
        // Problem here. Only signed is supported
        new Unsigned64AVP(code, isVendorSpecific, isMandatory, vendorId, attrValue.toLong)
      
      case DiameterTypes.FLOAT_32 =>
        new Float32AVP(code, isVendorSpecific, isMandatory, vendorId, attrValue.toFloat)
      
      case DiameterTypes.FLOAT_64 =>
        new Float64AVP(code, isVendorSpecific, isMandatory, vendorId, attrValue.toDouble)
      
      case DiameterTypes.GROUPED =>
        throw new DiameterCodingException("Tried to set a grouped attribute with a single value")
      
      case DiameterTypes.ADDRESS =>
        new AddressAVP(code, isVendorSpecific, isMandatory, vendorId, java.net.InetAddress.getByName(attrValue))
      
      case DiameterTypes.TIME =>
        val sdf = new java.text.SimpleDateFormat("yyyy-MM-ddThh:mm:ss")
        new TimeAVP(code, isVendorSpecific, isMandatory, vendorId, sdf.parse(attrValue))
      
      case DiameterTypes.UTF8STRING =>
        new UTF8StringAVP(code, isVendorSpecific, isMandatory, vendorId, attrValue)
        
      case DiameterTypes.DIAMETERIDENTITY =>
        new DiameterIdentityAVP(code, isVendorSpecific, isMandatory, vendorId, attrValue)
        
      case DiameterTypes.DIAMETERURI =>
        // TODO: Check syntax using regex
        new DiameterURIAVP(code, isVendorSpecific, isMandatory, vendorId, attrValue)
        
      case DiameterTypes.ENUMERATED =>
        new EnumeratedAVP(code, isVendorSpecific, isMandatory, vendorId, dictItem.asInstanceOf[EnumeratedAVPDictItem].values(attrValue))
        
      case DiameterTypes.IPFILTERRULE =>
        // TODO: Check syntax using regex
        new IPFilterRuleAVP(code, isVendorSpecific, isMandatory, vendorId, attrValue)
      
      case DiameterTypes.RADIUS_IPV4ADDRESS =>
        new IPv4AddressAVP(code, isVendorSpecific, isMandatory, vendorId, java.net.InetAddress.getByName(attrValue))

      case DiameterTypes.RADIUS_IPV6ADDRESS =>
        new IPv6AddressAVP(code, isVendorSpecific, isMandatory, vendorId, java.net.InetAddress.getByName(attrValue))

      case DiameterTypes.RADIUS_IPV6PREFIX =>
        new IPv6PrefixAVP(code, isVendorSpecific, isMandatory, vendorId, attrValue)
    }
  }
  
  implicit def TupleInt2DiameterAVP(tuple : (String, Int)) : DiameterAVP[Any] = {
    val (attrName, attrValue) = tuple
    TupleLong2DiameterAVP((attrName, attrValue.toLong))
  }
  
  implicit def TupleLong2DiameterAVP(tuple : (String, Long)) : DiameterAVP[Any] = {
    val (attrName, attrValue) = tuple
    
    val dictItem = DiameterDictionary.avpMapByName(attrName)
    val code = dictItem.code
    val isVendorSpecific = dictItem.vendorId != 0
    val isMandatory = false
    val vendorId = dictItem.vendorId
    
    dictItem.diameterType match {
      case DiameterTypes.OCTETSTRING => 
        throw new DiameterCodingException(s"Invalid value $attrValue for attribute $attrName")
        
      case DiameterTypes.INTEGER_32 =>
        new Integer32AVP(code, isVendorSpecific, isMandatory, vendorId, attrValue.toInt)
      
      case DiameterTypes.INTEGER_64 =>
        new Integer64AVP(code, isVendorSpecific, isMandatory, vendorId, attrValue)
      
      case DiameterTypes.UNSIGNED_32 =>
        new Unsigned32AVP(code, isVendorSpecific, isMandatory, vendorId, attrValue)
      
      case DiameterTypes.UNSIGNED_64 =>
        // Problem here. Only signed is supported
        new Unsigned64AVP(code, isVendorSpecific, isMandatory, vendorId, attrValue)
      
      case DiameterTypes.FLOAT_32 =>
        new Float32AVP(code, isVendorSpecific, isMandatory, vendorId, attrValue.toFloat)
      
      case DiameterTypes.FLOAT_64 =>
        new Float64AVP(code, isVendorSpecific, isMandatory, vendorId, attrValue.toDouble)
      
      case DiameterTypes.GROUPED =>
        throw new DiameterCodingException("Tried to set a grouped attribute with a single value")
      
      case DiameterTypes.ADDRESS =>
        throw new DiameterCodingException(s"Invalid value $attrValue for attribute $attrName")
      
      case DiameterTypes.TIME =>
        throw new DiameterCodingException(s"Invalid value $attrValue for attribute $attrName")
      
      case DiameterTypes.UTF8STRING =>
        throw new DiameterCodingException(s"Invalid value $attrValue for attribute $attrName")
        
      case DiameterTypes.DIAMETERIDENTITY =>
        throw new DiameterCodingException(s"Invalid value $attrValue for attribute $attrName")
        
      case DiameterTypes.DIAMETERURI =>
        throw new DiameterCodingException(s"Invalid value $attrValue for attribute $attrName")
        
      case DiameterTypes.ENUMERATED =>
        new EnumeratedAVP(code, isVendorSpecific, isMandatory, vendorId, attrValue.toInt)
        
      case DiameterTypes.IPFILTERRULE =>
        throw new DiameterCodingException(s"Invalid value $attrValue for attribute $attrName")
      
      case DiameterTypes.RADIUS_IPV4ADDRESS =>
        throw new DiameterCodingException(s"Invalid value $attrValue for attribute $attrName")

      case DiameterTypes.RADIUS_IPV6ADDRESS =>
        throw new DiameterCodingException(s"Invalid value $attrValue for attribute $attrName")

      case DiameterTypes.RADIUS_IPV6PREFIX =>
        throw new DiameterCodingException(s"Invalid value $attrValue for attribute $attrName")
    }
  }
  
  /**
   * Grouped AVP to Seq of (String, (String, String))
   */
  implicit def GroupedDiameterAVP2Seq(avp: GroupedAVP) : Seq[(String, String)] = {
    (for {
      avpElement <- avp.value
    } yield (DiameterDictionary.avpMapByCode.get(avpElement.vendorId, avpElement.code).map(_.name).getOrElse("Unknown") -> avpElement.stringValue))
  }
 
  implicit def Seq2GroupedDiameterAVP(tuple : (String, Seq[(String, String)])) : GroupedAVP = {
    val (attrName, avps) = tuple
    
    val dictItem = DiameterDictionary.avpMapByName(attrName)
    val code = dictItem.code
    val isVendorSpecific = dictItem.vendorId != 0
    val isMandatory = false
    val vendorId = dictItem.vendorId
    
    if(dictItem.diameterType != DiameterTypes.GROUPED) throw new DiameterCodingException("Tried to code a grouped attribute for a non grouped attribute name")
    
    val gavp = new GroupedAVP(code, isVendorSpecific, isMandatory, vendorId, scala.collection.mutable.Queue[DiameterAVP[Any]]())
    for(avp <- avps) {
      gavp << avp
    }
    gavp
  }
  
  /**
   * Helper for custom DiameterMessage Serializer
   * Useful for handling types correctly
   */
  def TupleJson2DiameterAVP(tuple: (String, JValue)): DiameterAVP[Any] = {
    val (attrName, attrValue) = tuple
    
    val dictItem = DiameterDictionary.avpMapByName(attrName)
    val code = dictItem.code
    val isVendorSpecific = dictItem.vendorId != 0
    val vendorId = dictItem.vendorId
    
    dictItem.diameterType match {
      case DiameterTypes.OCTETSTRING => 
        new OctetStringAVP(code, isVendorSpecific, false, vendorId, OctetOps.stringToOctets(attrValue.extract[String]))
        
      case DiameterTypes.INTEGER_32 =>
        new Integer32AVP(code, isVendorSpecific, false, vendorId, attrValue.extract[Int])
      
      case DiameterTypes.INTEGER_64 =>
        new Integer64AVP(code, isVendorSpecific, false, vendorId, attrValue.extract[Long])
      
      case DiameterTypes.UNSIGNED_32 =>
        new Unsigned32AVP(code, isVendorSpecific, false, vendorId, attrValue.extract[Int])
      
      case DiameterTypes.UNSIGNED_64 =>
        // Problem here. Only signed is supported
        new Unsigned64AVP(code, isVendorSpecific, false, vendorId, attrValue.extract[Int])
      
      case DiameterTypes.FLOAT_32 =>
        new Float32AVP(code, isVendorSpecific, false, vendorId, attrValue.extract[Float])
      
      case DiameterTypes.FLOAT_64 =>
        new Float64AVP(code, isVendorSpecific, false, vendorId, attrValue.extract[Double])
      
      case DiameterTypes.GROUPED =>
        val avps = for {
          JObject(javps) <- attrValue
          avp <- javps
        } yield TupleJson2DiameterAVP(avp)
        
        new GroupedAVP(code, isVendorSpecific, false, vendorId, scala.collection.mutable.Queue[DiameterAVP[Any]](avps: _*))
      
      case DiameterTypes.ADDRESS =>
        new AddressAVP(code, isVendorSpecific, false, vendorId, java.net.InetAddress.getByName(attrValue.extract[String]))
      
      case DiameterTypes.TIME =>
        val sdf = new java.text.SimpleDateFormat("yyyy-MM-ddThh:mm:ss")
        new TimeAVP(code, isVendorSpecific, false, vendorId, sdf.parse(attrValue.extract[String]))
      
      case DiameterTypes.UTF8STRING =>
        new UTF8StringAVP(code, isVendorSpecific, false, vendorId, attrValue.extract[String])
        
      case DiameterTypes.DIAMETERIDENTITY =>
        new DiameterIdentityAVP(code, isVendorSpecific, false, vendorId, attrValue.extract[String])
        
      case DiameterTypes.DIAMETERURI =>
        // TODO: Check syntax using regex
        new DiameterURIAVP(code, isVendorSpecific, false, vendorId, attrValue.extract[String])
        
      case DiameterTypes.ENUMERATED =>
        new EnumeratedAVP(code, isVendorSpecific, false, vendorId, dictItem.asInstanceOf[EnumeratedAVPDictItem].values(attrValue.extract[String]))
        
      case DiameterTypes.IPFILTERRULE =>
        // TODO: Check syntax using regex
        new IPFilterRuleAVP(code, isVendorSpecific, false, vendorId, attrValue.extract[String])
      
      case DiameterTypes.RADIUS_IPV4ADDRESS =>
        new IPv4AddressAVP(code, isVendorSpecific, false, vendorId, java.net.InetAddress.getByName(attrValue.extract[String]))

      case DiameterTypes.RADIUS_IPV6ADDRESS =>
        new IPv6AddressAVP(code, isVendorSpecific, false, vendorId, java.net.InetAddress.getByName(attrValue.extract[String]))

      case DiameterTypes.RADIUS_IPV6PREFIX =>
        new IPv6PrefixAVP(code, isVendorSpecific, false, vendorId, attrValue.extract[String])
    }
  }
  
  def diameterAVPToJField(avp: DiameterAVP[Any]): JField = {
    avp match {
        
        case avp: OctetStringAVP => JField(avp.getName, JString(OctetOps.octetsToString(avp.value)))
        
        case avp: Integer32AVP => JField(avp.getName, JInt(avp.value))
      
        case avp: Integer64AVP => JField(avp.getName, JLong(avp.value))
      
        case avp: Unsigned32AVP => JField(avp.getName, JLong(avp.value))
      
        case avp: Unsigned64AVP => JField(avp.getName, JLong(avp.value))
      
        case avp: Float32AVP => JField(avp.getName, JDouble(avp.value))
      
        case avp: Float64AVP => JField(avp.getName, JDouble(avp.value))
        
        case avp: GroupedAVP =>
          val childs = for {child <- avp.value} yield diameterAVPToJField(child)
          JField(avp.getName, childs.toList)
      
        case avp: AddressAVP => JField(avp.getName, JString(avp.stringValue))
        
        case avp: TimeAVP => JField(avp.getName, JString(avp.stringValue))
        
        case avp: UTF8StringAVP => JField(avp.getName, JString(avp.value))

        case avp: DiameterIdentityAVP => JField(avp.getName, JString(avp.value))
        
        case avp: DiameterURIAVP => JField(avp.getName, JString(avp.value))
        
        case avp: EnumeratedAVP => JField(avp.getName, JString(avp.stringValue))
        
        case avp: IPFilterRuleAVP => JField(avp.getName, JString(avp.value))
        
        case avp: IPv4AddressAVP => JField(avp.getName, JString(avp.stringValue))
        
        case avp: IPv6AddressAVP => JField(avp.getName, JString(avp.stringValue))
        
        case avp: IPv6PrefixAVP => JField(avp.getName, JString(avp.stringValue))

        }
  }
  
  class DiameterMessageSerializer(implicit idGen: IDGenerator) extends CustomSerializer[DiameterMessage](implicit jsonFormats => (
  {
    case jv: JValue =>
      val avps = for {
        JObject(javps) <- (jv \ "avps")
        (k, v) <- javps
      } yield TupleJson2DiameterAVP((k, v))
      
      new DiameterMessage(
         (jv \ "applicationId").extract[Long],
         (jv \ "commandCode").extract[Int],
         (jv \ "hopByHopId").extract[Option[Int]].getOrElse(idGen.nextHopByHopId),
         (jv \ "endToEndId").extract[Option[Int]].getOrElse(idGen.nextEndToEndId),
         Queue[DiameterAVP[Any]](avps: _*),
         (jv \ "isRequest").extract[Option[Boolean]].getOrElse(true),
         (jv \ "isProxiable").extract[Option[Boolean]].getOrElse(true),
         (jv \ "isError").extract[Option[Boolean]].getOrElse(false),
         (jv \ "isRetransmission").extract[Option[Boolean]].getOrElse(false)
         )
  },
  {
    case dm : DiameterMessage =>
      val javps = for {
        avp <- dm.avps.toList
      } yield diameterAVPToJField(avp)

      ("applicationId" -> dm.applicationId) ~
      ("commandCode" -> dm.commandCode) ~ 
      ("hopByHopId" -> dm.hopByHopId) ~
      ("avps" -> JObject(javps)) ~
      ("isRequest" -> dm.isRequest) ~
      ("isProxyable" -> dm.isProxyable) ~
      ("isError" -> dm.isError) ~
      ("isRetransmission" -> dm.isRetransmission)
  }
  ))
}