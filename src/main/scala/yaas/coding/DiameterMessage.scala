package yaas.coding

import java.nio.ByteOrder

import akka.util.{ByteString, ByteStringBuilder}
import yaas.dictionary._
import yaas.config.DiameterConfigManager
import yaas.util.IDGenerator
import yaas.util.UByteString
import yaas.util.OctetOps
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

import scala.util.matching.Regex

/**
 * Diameter coding error.
 * @param msg the message
 */
class DiameterCodingException(val msg: String) extends java.lang.Exception(msg: String)

/**
 * AVP extraction exception
 * @param msg the message
 */
class DiameterExtractionException(val msg: String) extends java.lang.Exception(msg: String)

/**
 * DiameterAVP Builder via apply.
 */
object DiameterAVP {
  
  implicit val byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN
  
  val ipv6PrefixRegex: Regex = """(.+)/([0-9]+)""".r
  
  /**
   * Builds a Diameter AVP from the received bytes.
   */
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
    val vendorId = if(isVendorSpecific) UByteString.getUnsigned32(it) else 0
    
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

/**
 * Superclass for all DiameterAVP types.
 *
 * <code>isMandatory</code> is a var because may be changed during serialization to wire format in <code>enforceMandatory</code>
 */
abstract class DiameterAVP[+A](val code: Long, val isVendorSpecific: Boolean, var isMandatory: Boolean, val vendorId: Long, val value: A){
  
  implicit val byteOrder: ByteOrder = DiameterAVP.byteOrder

  /**
   * Holds the dictionary entry for this AVP
   */
  val dictionaryItem: DiameterAVPDictItem = DiameterDictionary.avpMapByCode.getOrElse((vendorId, code), DiameterDictionary.unknownDiameterDictionaryItem)

  /**
   * Holds the name of the attribute
   */
  val name: String = getName

  /**
   * Serializes the AVP
   * @return the wire representation of the AVP
   */
  def getBytes: ByteString = {
    
    val builder = new ByteStringBuilder()
    // AVP Header is
    //    code: 4 byte
    //    flags: 1 byte
    //    length: 3 byte
    //    vendorId: 0 / 4 byte
    //    data: rest of bytes
    
    // Need to do this first to write the header
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
  
  /**
   * Serializes the payload only.
   * 
   * To be overridden in concrete classes.
   */
  def getPayloadBytes: ByteString
  
  /**
   * To AVPs are equal if have the same code, vendor specificity, madatory-ness and the values are the same.
   */
  override def equals(other: Any): Boolean = {
    other match {
      case x: DiameterAVP[Any] =>
        if(x.code == code && x.isVendorSpecific == isVendorSpecific && x.isMandatory == isMandatory && x.value.equals(value)) true else false

      case _ => false
    }
  }
  
  /**
   * To be overridden in concrete classes. Nicer name than the general-purpose <code>toString</code>
   */
  def stringValue : String

  /**
   * Returns the value of the AVP as a string.
   *
   * To get names and values use <code>pretty</code>
   */
  override def toString: String = stringValue

  /**
   * Returns the To be overriden in concrete classes.
   * @return the contents of the AVP as a Long, if possible
   *
   */
  def longValue: Long
  
  /**
   * Returns a copy of this AVP.
   * 
   * To be overridden in concrete classes.
   * Copying does not make too much sense, since AVPs are "almost" immutable (with the exception of the isMandatory
   * value). The implementations create a new AVP but pointing to the same <code>value</code>
   */
  def copy : DiameterAVP[Any]
  
  /**
   * Only GroupedAVPs override this
   */
  def enforceMandatory(): Unit = {}

  /**
   * Stringifies the AVP in [name -> value] format.
   *
   * @param indent better use the default value of 0
   * @return string
   */
  def pretty(indent: Int = 0) : String = {
    
    val attributeValue = this match {
      case thisAVP : GroupedAVP =>
        thisAVP.value.foldRight("\n")((avp, acc) => acc + avp.pretty(indent + 1) + "\n")
      case thisAVP : EnumeratedAVP =>
        dictionaryItem match {
          case di : EnumeratedAVPDictItem =>
            di.codes.getOrElse(thisAVP.value, thisAVP.value.toString)
          case _ => "ERROR"
        }
      case _ => stringValue
    }
    
    val tab = "  " * indent

    s"$tab[$name = $attributeValue]"
  } 
  
  /**
   * Name of the AVP as it appears in the dictionary.
   */
  def getName: String = {
    dictionaryItem.name
  }
  
  /**
   * Type of the AVP as Integer.
   *
   * Values in <code>DiameterTypes</code>
   */
  def getType: Int = {
    dictionaryItem.diameterType
  }
}

/**
 * Diameter AVP of unknown type.
 *
 * Unknown AVP is treated as a list of bytes.
 */
class UnknownAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: List[Byte]) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  /**
   * Secondary constructor from bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.toList)
  }

  /**
   * Returns ByteString with the on-the-wire representation of the payload
   * @return AVP bytes
   */
  def getPayloadBytes: ByteString = {
    ByteString.fromArray(value.toArray)
  }
  
  /**
   * As 0x[2-char-Hex-encoded-bytes]
   */
  override def stringValue: String = {
    OctetOps.octetsToString(value)
  }

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = {
    throw new DiameterExtractionException("UnknownAVP cannot be extracted as Long")
  }

  /**
   * List are immutable, so we are not copying it
   * @return the copied AVP
   */
  override def copy = new UnknownAVP(code, isVendorSpecific, isMandatory, vendorId, value)
}

/**
 * Diameter AVP of Octet String type.
 *
 * Value is a <code>List[Byte]</code>
 */
class OctetStringAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: List[Byte]) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  /**
   * Secondary constructor from bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.toList)
  }

  def getPayloadBytes: ByteString = {
    ByteString.fromArray(value.toArray)
  }
  
   /**
   * As 0x[2-char-Hex-encoded-bytes]
   */
  override def stringValue: String = {
    OctetOps.octetsToString(value)
  }

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = {
    throw new DiameterExtractionException("OctetStringAVP cannot be extracted as Long")
  }
  
  override def copy = new OctetStringAVP(code, isVendorSpecific, isMandatory, vendorId, value)
}

/**
 * Diameter AVP of integer32 type.
 *
 * Value is a short Integer
 */
class Integer32AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: Int) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  /**
   * Secondary constructor from bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString)(implicit byteOrder: ByteOrder){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getInt(byteOrder))
  }
  
  def getPayloadBytes: ByteString = {
    new ByteStringBuilder().putInt(value).result
  } 
  
  override def stringValue: String = {
    value.toString
  }

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = value.toLong
  
  override def copy = new Integer32AVP(code, isVendorSpecific, isMandatory, vendorId, value)
}

/**
 * Diameter AVP of integer64 type.
 *
 * Value is a long Integer
 */
class Integer64AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
   /**
   * Secondary constructor from bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString)(implicit byteOrder: ByteOrder){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getLong(byteOrder))
  }
  
  def getPayloadBytes: ByteString = {
    new ByteStringBuilder().putLong(value).result
  } 
  
  override def stringValue: String = {
    value.toString
  }

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = value.toLong
  
  override def copy = new Integer64AVP(code, isVendorSpecific, isMandatory, vendorId, value)
}

/**
 * Diameter AVP of unsigned32 type.
 * 
 * Since Scala does not have an unsigned integer type, the value is represented as a Long
 */
class Unsigned32AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
   /**
   * Secondary constructor from bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString)(implicit byteOrder: ByteOrder) {
    this(code, isVendorSpecific, isMandatory, vendorId, UByteString.getUnsigned32(bytes))
  }
  
  def getPayloadBytes: ByteString = {
    UByteString.putUnsigned32(new ByteStringBuilder(), value).result
  }
  
  override def stringValue: String = {
    value.toString
  }

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = value.toLong
  
  override def copy = new Unsigned32AVP(code, isVendorSpecific, isMandatory, vendorId, value)
}

/**
 * AVP of unsigned64 type
 * 
 * Since Scala does not have an unsigned integer type, the value is represented as a Long. 
 * This class does not correctly represents integers bigger than 2exp63
 */
class Unsigned64AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
   /**
   * Secondary constructor from bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString)(implicit byteOrder: ByteOrder) {
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getLong)
  }
  
  def getPayloadBytes: ByteString = {
    UByteString.putUnsigned64(new ByteStringBuilder(), value).result
  } 
  
  override def stringValue: String = {
    value.toString
  }

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = value.toLong
  
  override def copy = new Unsigned64AVP(code, isVendorSpecific, isMandatory, vendorId, value)
}

/**
 * AVP of float type
 */
class Float32AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: Float) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
   /**
   * Secondary constructor from bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString)(implicit byteOrder: ByteOrder){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getFloat)
  }
  
  def getPayloadBytes: ByteString = {
    new ByteStringBuilder().putFloat(value).result
  } 
  
  override def stringValue: String = {
    value.toString
  }

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = value.toLong
  
  override def copy = new Float32AVP(code, isVendorSpecific, isMandatory, vendorId, value)
}

/**
 * AVP of double type
 */
class Float64AVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: Double) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
   /**
   * Secondary constructor from bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString)(implicit byteOrder: ByteOrder){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getDouble)
  }
  
  def getPayloadBytes: ByteString = {
    new ByteStringBuilder().putDouble(value).result
  } 

  override def stringValue: String = {
    value.toString
  }

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = value.toLong
  
  override def copy = new Float64AVP(code, isVendorSpecific, isMandatory, vendorId, value)
}

/**
 * Value is a List of DiameterAVP.
 * 
 * Embedded AVP manipulation functions
 * 	<code>withAttr()</code>, or <code><--</code>: appends the attribute and returns a copy
 *  <code>get()</code>, or <code>></code>: returns the first attribute with the specified name
 *  <code>get()</code>, or <code>>+</code>: returns all attributes (List) with the specified name
 */
class GroupedAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: List[DiameterAVP[Any]]) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
   /**
   * Secondary constructor from bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, {
        var avps = List[DiameterAVP[Any]]()
        var idx = 0
        while(idx < bytes.length){
          val l = UByteString.getUnsigned24(bytes.slice(idx + 5, idx + 8))
          val theNextAVP = DiameterAVP(bytes.slice(idx, idx +  l))
          avps = avps :+ theNextAVP
          idx += (l + (4 - l % 4) % 4)
        }
        avps
    })
  }

  def getPayloadBytes: ByteString = {
    val builder = new ByteStringBuilder()
    for(avp <- value){
      builder.append(avp.getBytes)
      // Padding
      if(builder.length % 4 != 0) builder.putBytes(new Array[Byte](4  - (builder.length % 4)))
    }
    builder.result
  }

  /**
   * <code>{name=value,name=value}</code> for each inner AVP
   */
  override def stringValue: String = {
    "{" +
      value.map{inAVP =>
        s""""${inAVP.getName}"="${inAVP.stringValue}""""
      }.mkString(",") +
      "}"
  }

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = {
    throw new DiameterExtractionException("GroupedAVP cannot be extracted as Long")
  }

  override def copy: GroupedAVP = new GroupedAVP(code, isVendorSpecific, isMandatory, vendorId, value.map(_.copy))
  
  /**
   * Checks and sets Mandatory-ness (recursive)
   */
  override def enforceMandatory(): Unit = {
    DiameterDictionary.avpMapByCode((vendorId, code)) match {
      case GroupedAVPDictItem(_, _, _, _, groupedItems) =>
        for((name, groupedProps) <- groupedItems){
          get(name) match {
            case Some(avp) =>
              if(groupedProps.isMandatory) avp.isMandatory = true
              avp.enforceMandatory()
            case None =>
              // May send a message with a mandatory attribute not set
              // The standard says that the receiving end must understand it (but might not be sent at all)
          }
        }
      case _ =>
        throw new DiameterCodingException(s"Attribute with code: $code and vendor $vendorId is not grouped in dictionary")
    }
  }

  // Synonyms
   /**
   * Returns a NEW Grouped AVP with the prepended attribute.
   * 
   * Same as <code>put</code>
   */
  def <-- (avp: DiameterAVP[Any]) : GroupedAVP = {
    new GroupedAVP(code, isVendorSpecific, isMandatory, vendorId, value :+ avp)
  }

  /**
   * Returns a NEW Grouped AVP with the prepended attribute. Notice that the attribute is not appended, but prepended,
   * for performance, but this might cause issues if attribute ordering is important. The application should enforce
   * the order taking this into account.
   */
  def withAttr(avp: DiameterAVP[Any]) : GroupedAVP = <-- (avp: DiameterAVP[Any])
  
  // Synonyms
  /**
   * Retrieves the first AVP with the specified name.
   *
   * Same as <code>get</code>
   */
  def >> (attributeName: String) : Option[DiameterAVP[Any]] = {
    DiameterDictionary.getAttrCodeFromName(attributeName) match {
      case Some((vendorId, code)) => value.find(avp => avp.vendorId == vendorId && avp.code == code)
      case _ => None
    }
  }

  /**
   * Retrieves the first AVP with the specified name.
   */
  def get(attrName: String) : Option[DiameterAVP[Any]] = >> (attrName: String)

  /**
   * Gets all the AVP with the specified name (non recursive, does  not enter in Groups).
   * 
   * Same as <code>getAll</code>
   */
  def >>+ (attributeName: String): List[DiameterAVP[Any]] = {
    DiameterDictionary.getAttrCodeFromName(attributeName) match {
      case Some((vendorId, code)) => value.filter(avp => avp.vendorId == vendorId && avp.code == code)
      case None => List()
    }
  }

  /**
   * Gets all the AVP with the specified name (non recursive).
   */
  def getAll(attributeName: String): Seq[DiameterAVP[Any]] = >>+ (attributeName: String)

}

/**
 * Value is an IP address
 */
class AddressAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: java.net.InetAddress) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  /**
   * Secondary constructor from Bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, java.net.InetAddress.getByAddress(bytes.drop(2).toArray))
  }
  
  def getPayloadBytes: ByteString = {
    val bsb = new ByteStringBuilder()
    // First 2 octets are <1> if IPv4 and <2> if IPv6
    if(value.isInstanceOf[java.net.Inet4Address]) bsb.putShort(1) else bsb.putShort(2)
    bsb.putBytes(value.getAddress).result
  } 
  
  override def stringValue: String = {
    value.getHostAddress
  }

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = {
    throw new DiameterExtractionException("AddressAVP cannot be extracted as Long")
  }
  
  override def copy = new AddressAVP(code, isVendorSpecific, isMandatory, vendorId, value)
}

/**
 * Helper methods for Diameter TimeAVP.
 */
object TimeAVP {
  private val df = new java.text.SimpleDateFormat("YYYY-MM-dd'T'hh:mm:ss")
  df.setTimeZone(java.util.TimeZone.getTimeZone("UTC"))
  
  private val epochOffset = df.parse("1900-01-01T00:00:00").getTime / 1000
  
  /**
   * Gets the number of seconds since 1st Jan 1900.
   */
  def dateToDiameterSeconds(d: java.util.Date): Long = d.getTime / 1000 - epochOffset
  
  /**
   * Gets the date for the specified diameter seconds (since 1 Jan 1900).
   */
  def diameterSecondsToDate(l: Long): java.util.Date = new java.util.Date((l + epochOffset) * 1000)
}

/**
 * Value is date
 * 
 * The value is stored as a Long representing the seconds since 1 Jan 1900
 *
 * The string representation is in "YYYY-MM-dd'T'hh:mm:ss" format.
 * 
 * TODO: Implement the overflow procedure described in RFC5905
 */
class TimeAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: Long) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){

  /**
   * Secondary constructor from Bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString)(implicit byteOrder: ByteOrder){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getLong)
  }
  
  def getPayloadBytes: ByteString = {
    new ByteStringBuilder().putLong(value).result
  }
  
  override def stringValue: String = new java.text.SimpleDateFormat("YYYY-MM-dd'T'hh:mm:ss").format(value)

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = value
  
  override def copy = new TimeAVP(code, isVendorSpecific, isMandatory, vendorId, value)
}

/**
 * The value is a UTF-8 String
 */
class UTF8StringAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
   /**
   * Secondary constructor from Bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.decodeString("UTF-8"))
  }
  
  def getPayloadBytes: ByteString = {
    ByteString.fromString(value, "UTF-8")
  }
  
  override def stringValue: String = value

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = {
    throw new DiameterExtractionException("UTF8StringAVP cannot be extracted as Long")
  }
  
  override def copy = new UTF8StringAVP(code, isVendorSpecific, isMandatory, vendorId, value)
}

/**
 * The value is a String representing a Diameter Identity
 */
class DiameterIdentityAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value) {
  /**
   * Secondary constructor from Bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.decodeString("UTF-8"))
  }

  def getPayloadBytes: ByteString = {
    ByteString.fromString(value, "UTF-8")
  }

  override def stringValue: String = value

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = {
    throw new DiameterExtractionException("DiameterIdentityAVP cannot be extracted as Long")
  }

  override def copy = new DiameterIdentityAVP(code, isVendorSpecific, isMandatory, vendorId, value)
}

/**
 * The value is a Diameter URI
 */
class DiameterURIAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value) {
  /**
   * Secondary constructor from Bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.decodeString("UTF-8"))
  }

  def getPayloadBytes: ByteString = {
    ByteString.fromString(value, "UTF-8")
  }

  override def stringValue: String = value

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = {
    throw new DiameterExtractionException("DiameterURIAVP cannot be extracted as Long")
  }

  override def copy = new DiameterURIAVP(code, isVendorSpecific, isMandatory, vendorId, value)
}

/**
 * The value is an enumerated integer.
 */
class EnumeratedAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: Int) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  /**
   * Secondary constructor from bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString)(implicit byteOrder: ByteOrder){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.iterator.getInt(byteOrder))
  }

  def getPayloadBytes: ByteString = {
    new ByteStringBuilder().putInt(value).result
  }

  override def stringValue: String = DiameterDictionary.avpMapByCode.get((vendorId, code)).map{case EnumeratedAVPDictItem(_, _, _, _, _ , codes) => codes.getOrElse(value, "Unknown value")}.getOrElse("Unknown type")

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = value.toLong
  
  override def copy = new EnumeratedAVP(code, isVendorSpecific, isMandatory, vendorId, value)
}

/**
 * The value is a string representing a IP Filter rule
 */
class IPFilterRuleAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
  /**
   * Secondary constructor from Bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, bytes.decodeString("UTF-8"))
  }

  def getPayloadBytes: ByteString = {
    ByteString.fromString(value, "UTF-8")
  }

  override def stringValue: String = value

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = {
    throw new DiameterExtractionException("IPFilterRuleAVP cannot be extracted as Long")
  }
  
  override def copy = new IPFilterRuleAVP(code, isVendorSpecific, isMandatory, vendorId, value)
}

/**
 * The value is an IPv4 address
 */
class IPv4AddressAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: java.net.InetAddress) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
   /**
   * Secondary constructor from Bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, java.net.InetAddress.getByAddress(bytes.slice(0, 4).toArray))
  }
  
  def getPayloadBytes: ByteString = ByteString.fromArray(value.getAddress)
  
  override def stringValue: String = value.getHostAddress

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = {
    throw new DiameterExtractionException("IPv4AddressAVP cannot be extracted as Long")
  }
  
  override def copy = new IPv4AddressAVP(code, isVendorSpecific, isMandatory, vendorId, value)
}

/**
 * The value is an IPv6 Address
 */
class IPv6AddressAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: java.net.InetAddress) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
   /**
   * Secondary constructor from Bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, java.net.InetAddress.getByAddress(bytes.slice(0, 16).toArray))
  }
  
  def getPayloadBytes: ByteString = ByteString.fromArray(value.getAddress)
  
  override def stringValue: String = value.getHostAddress

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = {
    throw new DiameterExtractionException("IPv6AddressAVP cannot be extracted as Long")
  }
  
  override def copy = new IPv6AddressAVP(code, isVendorSpecific, isMandatory, vendorId, value)
}

/**
 * The value is an IPv6 prefix.
 * 
 */
class IPv6PrefixAVP(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, value: String) extends DiameterAVP(code, isVendorSpecific, isMandatory, vendorId, value){
   /**
   * Secondary constructor from Bytes
   */
  def this(code: Long, isVendorSpecific: Boolean, isMandatory: Boolean, vendorId: Long, bytes: ByteString){
    this(code, isVendorSpecific, isMandatory, vendorId, {
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
    DiameterAVP.ipv6PrefixRegex.findFirstMatchIn(value) match {
      case Some(m) =>
        builder.putByte(m.group(2).toByte)
        builder.putBytes(java.net.InetAddress.getByName(m.group(1)).getAddress)
        builder.result

      case _ =>
        throw new DiameterCodingException(s"Cannot format $value as IPv6 address")
    }
  }
  
  override def stringValue: String = value

  /**
   * @return the contents of the AVP as a Long, if possible
   */
  override def longValue: Long = {
    throw new DiameterExtractionException("IPv6PrefixAVP cannot be extracted as Long")
  }
  
  override def copy = new IPv6PrefixAVP(code, isVendorSpecific, isMandatory, vendorId, value)
}

/**
 * DiameterMessage Builder DiameterMessage(ByteString)
 */
object DiameterMessage {

  private implicit val byteOrder: ByteOrder = java.nio.ByteOrder.BIG_ENDIAN
  
  private val lineSeparator = sys.props("line.separator")
  
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
    // And the length
    UByteString.getUnsigned24(it)
    val flags = it.getByte
        val isRequest = (flags & 128) > 0
        val isProxyable = (flags & 64) > 0
        val isError = (flags & 32) > 0
        val isRetransmission = (flags & 16) > 0
    val commandCode = UByteString.getUnsigned24(it)
    val applicationId = it.getInt
    val hopByHopId = it. getInt
    val endToEndId = it.getInt
    
    @scala.annotation.tailrec
    def appendAVPsFromByteIterator(acc: List[DiameterAVP[Any]]) : List[DiameterAVP[Any]] = {
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

    new DiameterMessage(applicationId, commandCode, hopByHopId, endToEndId, appendAVPsFromByteIterator(List()), isRequest, isProxyable, isError, isRetransmission)
  }
  
  /**
   * Builds a new Diameter Request with the specified application and command names, setting the 
   * identifiers and flags to default values and empty attribute list.
   * 
   * <code>Origin-Host</code> and <code>Origin-Realm</code> are added. End2EndId and HopByHopId are autogenerated
   *
   * @param applicationName name
   * @param commandName name
   * @return the new DiameterMessage
   */

  def request(applicationName : String, commandName: String): DiameterMessage = {
    
    val diameterConfig = DiameterConfigManager.diameterConfig
    val applicationDictItem = DiameterDictionary.appMapByName(applicationName)
    
    val requestMessage = new DiameterMessage(applicationDictItem.code, applicationDictItem.commandMapByName(commandName).code, 
        IDGenerator.nextHopByHopId, IDGenerator.nextEndToEndId, List(), true, true, false, false)

    requestMessage.put(new DiameterIdentityAVP(264, false, false, 0, diameterConfig.diameterHost))
    requestMessage.put(new DiameterIdentityAVP(296, false, false, 0, diameterConfig.diameterRealm))
  }
  
  /**
   * Builds a Diameter Answer to the specified request with empty attribute list.
   * 
   * <code>Origin-Host</code> and <code>Origin-Realm</code> are added. End2EndId and HopByHopId are copied from request
   *
   * @param request the Diameter Message to be answered
   * @return the new DiameterMessage
   */
  def answer(request: DiameterMessage): DiameterMessage = {
    val diameterConfig = DiameterConfigManager.diameterConfig
    val answerMessage = new DiameterMessage(request.applicationId, request.commandCode, request.hopByHopId, request.endToEndId, List(), false, true, false, false)

    answerMessage.put(new DiameterIdentityAVP(264, false, false, 0, diameterConfig.diameterHost))
    answerMessage.put(new DiameterIdentityAVP(296, false, false, 0, diameterConfig.diameterRealm))
  }
  
  /**
   * Builds a copy of the diameter message, with new autogenerated EndToEndId and HopByHopId.
   *
   * @param diameterMessage the message to be copied
   */
  def copy(diameterMessage: DiameterMessage): DiameterMessage = {
    new DiameterMessage(
        diameterMessage.applicationId, 
        diameterMessage.commandCode,
        IDGenerator.nextHopByHopId,
        IDGenerator.nextEndToEndId,
        for(avp <- diameterMessage.avps) yield avp.copy, 
        diameterMessage.isRequest,
        diameterMessage.isProxyable, 
        diameterMessage.isError,
        diameterMessage.isRetransmission)
  }
  
  val EMPTY_FIELD = "<void>"
}

/**
 * Used for stats.
 */
case class DiameterMessageKey(originHost: String, originRealm: String, destinationHost: String, destinationRealm: String, applicationId: String, commandCode: String)

/**
 * Represents a Diameter Message.
 * 
 * Includes methods to add and extract AVP, and some other to generate derived messages (copy, proxy, and response)
 */
class DiameterMessage(val applicationId: Long, val commandCode: Int, val hopByHopId: Int, val endToEndId: Int, var avps: List[DiameterAVP[Any]], val isRequest: Boolean, val isProxyable: Boolean = true, val isError: Boolean = false, val isRetransmission: Boolean = false) {
  
  import DiameterConversions._
  private implicit val byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN

  /**
   * Serializes the DiameterMessage.
   *
   * @return wire representation of the message
   */
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
      // Command level
      if(commandAVPMap((avp.vendorId, avp.code)).isMandatory) avp.isMandatory = true
      // AVP level
      avp.enforceMandatory()
      
      builder.append(avp.getBytes)
      if(builder.length % 4 != 0) builder.putBytes(new Array[Byte](4 - builder.length % 4))
    }
    
    val result = builder.result
    
    // Write length now   
    result.patch(1, UByteString.putUnsigned24(new ByteStringBuilder(), result.length).result, 3)
  }
  
  
  /**
   * Generates a new request with all attributes copied except for the header ones (Origin-Host/Realm and Destination-Host/Realm).
   * Origin-Host/Realm are automatically refilled with the node configuration
   */
  def proxyRequest: DiameterMessage = {
    val diameterConfig = DiameterConfigManager.diameterConfig
    
    val requestMessage = new DiameterMessage(applicationId, commandCode,  
      IDGenerator.nextHopByHopId, IDGenerator.nextEndToEndId, avps, true, isProxyable, false, false).
      removeAll(0, 264). // Origin-Host
      removeAll(0, 264). // Origin-Realm
      removeAll(0, 293). // Destination-Host
      removeAll(0, 283) // Destination-Realm


    requestMessage << ("Origin-Host" -> diameterConfig.diameterHost)
    requestMessage << ("Origin-Realm" -> diameterConfig.diameterRealm) 
    
    requestMessage
  }
  
  /**
   * Generates an empty answer to this message
   */
  def answer: DiameterMessage = DiameterMessage.answer(this)
  
   /**
   * Generates a copy of this message, except for EndtoEndId and HopByHopId.
   */
  def copy: DiameterMessage = DiameterMessage.copy(this)

  // Synonyms
  /**
   * Adds an AVP to message.
   * 
   * Same as <code>put</code>.
   */
  def << (avp: DiameterAVP[Any]) : DiameterMessage = {
    avps = avps :+ avp
    this
  }

  /**
   * Adds an AVP to the message.
   */
  def put(avp: DiameterAVP[Any]) : DiameterMessage = << (avp: DiameterAVP[Any])
  
  // Versions with option. Does nothing if avpOption is None
   /**
   * Adds an AVP in message, doing nothing if the parameter is empty.
   * 
   * Same as <code>put(Option[DiameterAVP])</code>
    *
   */ 
  def << (avpOption: Option[DiameterAVP[Any]]) : DiameterMessage = {
    avpOption match {
      case Some(avp) => 
        avps = avps :+ avp
      case None => 
    }
    this
  }

  /**
   * Adds an AVP in message, doing nothing if the parameter is empty.
   */
  def put(avp: Option[DiameterAVP[Any]]) : DiameterMessage = << (avp: Option[DiameterAVP[Any]])
  
   /**
   * Adds a Grouped AVP to the message.
   * 
   * Same as << and <code>put</code>. Used only for symmetry
   */
  def <-< (avp: GroupedAVP) : DiameterMessage = {
    avps = avps :+ avp
    this
  }

  /**
   * Adds a Grouped AVP to the message.
   *
   * Same as << and <code>put</code>. Used only for symmetry
   */
  def putGrouped(avp: GroupedAVP): DiameterMessage = <-< (avp: GroupedAVP)

  
   /**
   * Adds a grouped AVP to message.
   */
  def <-< (avpOption: Option[GroupedAVP]) : DiameterMessage = {
    avpOption match {
      case Some(avp) => 
        avps = avps :+ avp
      case None => 
    }
    this
  }

  /**
   * Adds a grouped AVP to the message.
   *
   * Simple <code>put</code> may also be used,
   */
  def putGrouped(avp: Option[GroupedAVP]): DiameterMessage = <-< (avp: Option[GroupedAVP])
  
  // Insert multiple values
  /**
   * Adds a list of Diameter AVPs to the message.
   * 
   * Same as <code>putAll</code>
   */
  def << (mavp : List[DiameterAVP[Any]]) : DiameterMessage = {
    avps = avps ++ mavp
    this
  }

  /**
   * Adds a list of Diameter AVPs to the message.
   */
  def put(mavp : List[DiameterAVP[Any]]): DiameterMessage = << (mavp : List[DiameterAVP[Any]])
  
   /**
   * Extracts the first AVP with the specified name from message.
   * 
   * Same as <code>get</code>
   */
  def >> (attributeName: String): Option[DiameterAVP[Any]] = {
    DiameterDictionary.getAttrCodeFromName(attributeName) match {
      case Some((vendorId, code)) =>
        avps.find(avp => avp.vendorId == vendorId && avp.code == code)
      case None => None
    }
  }

  /**
   * Extracts the first AVP with the specified name from message.
   */
  def get(attributeName: String): Option[DiameterAVP[Any]] = >> (attributeName: String)

  /**
   * Get attribute with good performance
   * @param vendorId the vendorId
   * @param code the code
   * @return
   */
  def get(vendorId: Int, code: Int): Option[DiameterAVP[Any]] = {
    avps.find(avp => avp.vendorId == vendorId && avp.code == code)
  }
  
  /**
   * Extracts the first grouped AVP with the specified name from message. Used for type safety.
   * 
   * Same as <code>getGroup</code>
   */
  def >-> (attributeName: String) : Option[GroupedAVP] = {
    DiameterDictionary.getAttrCodeFromName(attributeName) match {
      case Some((vendorId, code)) =>
        avps.find(avp => avp.vendorId == vendorId && avp.code == code) match {
          case Some(theAVP) =>
            theAVP match {
              case v : GroupedAVP => Some(v)
              case _ => None
            }
          case _ => None
        }
      case None => None
    }
  }

  /**
   * Extracts the first grouped AVP with the specified name from message.
   */
  def getGroup(attributeName: String) : Option[GroupedAVP] = >-> (attributeName: String)

  /**
   * Extracts the DiameterAVP from the full name using dot notation
   */
  def >>> (attributeName: String): Option[DiameterAVP[Any]] = {

     // if avpOption is None, go for the avp in the DiameterMessage. Otherwise go for the AVP in this GroupedAVP
     @scala.annotation.tailrec
     def getNextAVP(names: List[String], avpOption: Option[DiameterAVP[Any]]): Option[DiameterAVP[Any]] = {
       names match {
         case head :: tail =>
           avpOption match {
             case None =>
               // Done in two steps to just ignore the first avp name if does not exist
               // With this implementation, will return None if the first avp name does not exist
               val nextAVP = get(head)
               if(nextAVP.isEmpty) None else getNextAVP(tail, nextAVP)

             case Some(avp) =>
               avp match {
                 case v : GroupedAVP =>
                   getNextAVP(tail, v.get(head))
                 case _ =>
                   // Trying to extract from a non grouped AVP
                   None
               }
           }

         case _ =>
           // Finished
           avpOption
       }

     }
     
     getNextAVP(attributeName.split("\\.").toList, None)
  }

  /**
   * Extracts the DiameterAVP from the full name using dot notation
   */
  def getDeep(attributeName: String): Option[DiameterAVP[Any]] = >>> (attributeName: String)
  
  /**
   * Extracts a list with all attributes with the specified name.
   * 
   * Same as <code>getAll</code>
   */
  def >>+ (attributeName: String): List[DiameterAVP[Any]] = {
    DiameterDictionary.avpMapByName.get(attributeName).map(_.code) match {
      case Some(code) => avps.filter(avp => avp.code == code)
      case None => List()
    }
  }

  /**
   * Extracts a list with all attributes with the specified name.
   */
  def getAll(attributeName: String): Seq[DiameterAVP[Any]] = >>+ (attributeName: String)
    
  /**
   * Extracts AVP from message and force conversion to string. If multivalue, returns comma separated list. If grouped, generates
   * name = value pairs
   */  
  def getAsString(attributeName: String): String = >>* (attributeName: String)
    
  /**
   * Extracts AVP from message and force conversion to string. If multivalue, returns comma separated list. If grouped, generates
   * name = value pairs
   */
  def >>* (attributeName: String): String = {
    DiameterDictionary.avpMapByName.get(attributeName).map(_.code) match {
      case Some(code) => avps.filter(avp => avp.code == code).map(_.stringValue).mkString(",")
      case None => ""
    }
  }
  
  /**
   * Delete all AVP with the specified name.
   *
   * @param attributeName the attribute
   */
  def removeAll(attributeName: String) : DiameterMessage = {
    DiameterDictionary.getAttrCodeFromName(attributeName) match {
      case Some((vendorId, code)) =>
        avps = avps.filter(avp => avp.vendorId != vendorId || avp.code != code)
      case None =>
    }
    this
  }

  /**
   * Delete all AVPs with the specified vendorId and code
   * @param vendorId the vendor id
   * @param code the code
   */
  def removeAll(vendorId: Int, code: Int): DiameterMessage = {
    avps = avps.filter(avp => avp.vendorId != vendorId || avp.code != code)
    this
  }

  /**
   * Simple string extractor
   * @param key the attribute to extract in dot format
   */
  def S(key: String): String = {
    getDeep(key) match {
      case Some(avp) =>
        avp.stringValue
      case _ =>
        throw new DiameterExtractionException(s"$key not found")
    }
  }

  /**
   * Simple string extractor
   * @param key the attribute to extract in dot format
   */
  def L(key: String): Long = {
    getDeep(key) match {
      case Some(avp) =>
        avp.longValue
      case _ =>
        throw new DiameterExtractionException(s"$key not found")
    }
  }

  /**
   * Generates a <code>DiameterMessageKey</code> for stats.
   */
  def key: DiameterMessageKey = {
    val originHost = get(0, 264).map(_.stringValue).getOrElse(DiameterMessage.EMPTY_FIELD)
    val originRealm = get(0, 296).map(_.stringValue).getOrElse(DiameterMessage.EMPTY_FIELD)
    val destinationHost = get(0, 293).map(_.stringValue).getOrElse(DiameterMessage.EMPTY_FIELD)
    val destinationRealm = get(0, 283).map(_.stringValue).getOrElse(DiameterMessage.EMPTY_FIELD)
    
    DiameterMessageKey(originHost, originRealm, destinationHost, destinationRealm, applicationId.toString, commandCode.toString)
  }

  /**
   * Gets the application name.
   */
  val application : String = DiameterDictionary.appMapByCode.get(applicationId).map(_.name).getOrElse("Unknown")
  
  /**
   * Gets the command name.
   */
  val command: String = DiameterDictionary.appMapByCode.get(applicationId).flatMap(_.commandMapByCode.get(commandCode).map(_.name)).getOrElse("Unknown")

  /**
   * Gets the destination realm
   */
  def destinationRealm: String = {
    get(0, 283).map(_.stringValue).getOrElse(DiameterMessage.EMPTY_FIELD)
  }

  /**
   * To print the DiameterMessage CDR contents to file.
   *
   * @param format one instance subclassed from DiameterSerialFormat
   */
  def getCDR(format: DiameterSerialFormat): String = {
    format match {
      case _: JSONDiameterSerialFormat =>
        compact(render(DiameterConversions.diameterMessageToJson(this) \ "avps"))

      case f: CSVDiameterSerialFormat =>
        f.attrList.map(_ => s""""${getDeep("").map(_.stringValue).getOrElse("")}"""").mkString(",")
    }
  }
  
  /**
   * Pretty prints the DiameterMessage.
   */
  override def toString: String = {
    val header = s"req: $isRequest, pxabl: $isProxyable, err: $isError, ret: $isRetransmission, hbhId: $hopByHopId, e2eId: $endToEndId"
    val application = DiameterDictionary.appMapByCode.get(applicationId)
    val applicationName = application.map(_.name).getOrElse("Unknown")
    val commandName = application.flatMap(_.commandMapByCode.get(commandCode).map(_.name)).getOrElse("Unknown")
    val prettyAVPs = avps.foldRight("")((avp, acc) => acc + avp.pretty() + DiameterMessage.lineSeparator)
    
    s"$applicationName - $commandName${DiameterMessage.lineSeparator}$header${DiameterMessage.lineSeparator}$prettyAVPs${DiameterMessage.lineSeparator}"
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
            !(x.avps == avps)) false else true
      case _ => false
    }
  }
}

/**
 * Holds implicit conversions.
 */
object DiameterConversions {
  
  private implicit val jsonFormats: Formats = DefaultFormats + new DiameterMessageSerializer
  
  /**
   * This is to allow composing >> after >:>, which returns an Option
   */
  implicit def FromOptionGrouped(o: Option[GroupedAVP]): GroupedAVP = {
    o match {
      case Some(avp) => avp
      case None => throw new DiameterExtractionException("Cannot extract from empty grouped AVP")
    }
  }
  
  /**
   * Simple Diameter AVP to String (value).
   */
  implicit def DiameterAVP2String(o: Option[DiameterAVP[Any]]) : String = {
    o match {
      case Some(avp) => avp.stringValue
      case None => ""
    }
  }

  /**
   * Simple Diameter AVP from tuple (name, value).
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
        val sdf = new java.text.SimpleDateFormat("YYYY-MM-dd'T'hh:mm:ss")
        new TimeAVP(code, isVendorSpecific, isMandatory, vendorId, TimeAVP.dateToDiameterSeconds(sdf.parse(attrValue)))
      
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
        // TODO: Check syntax using regex
        new IPv6PrefixAVP(code, isVendorSpecific, isMandatory, vendorId, attrValue)
    }
  }
  
  /**
   * Simple Diameter AVP from tuple (name, value).
   */
  implicit def TupleInt2DiameterAVP(tuple : (String, Int)) : DiameterAVP[Any] = {
    val (attrName, attrValue) = tuple
    TupleLong2DiameterAVP((attrName, attrValue.toLong))
  }
  
  /**
   * Simple Diameter AVP from tuple (name, value).
   */
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
   * Grouped AVP to Seq of (String, String).
   * 
   * Only works for one-level grouped AVP.
   */
  implicit def GroupedDiameterAVP2Seq(avp: GroupedAVP) : Seq[(String, String)] = {
    val v = avp.value
    for {
      avpElement <- v
    } yield DiameterDictionary.avpMapByCode.get((avpElement.vendorId, avpElement.code)).map(_.name).getOrElse("Unknown") -> avpElement.stringValue
  }
  
  /**
   * String -> Seq of tuples to grouped AVP.
   */
  implicit def Seq2GroupedDiameterAVP(tuple : (String, Seq[(String, String)])) : GroupedAVP = {
    val (attrName, avps) = tuple
    
    val dictItem = DiameterDictionary.avpMapByName(attrName)
    val code = dictItem.code
    val isVendorSpecific = dictItem.vendorId != 0
    val isMandatory = false
    val vendorId = dictItem.vendorId
    
    if(dictItem.diameterType != DiameterTypes.GROUPED) throw new DiameterCodingException("Tried to code a grouped attribute for a non grouped attribute name")
    
    // Convert each string tuple to DiameterAVP
    val avpList = for (avp <- avps) yield Tuple2DiameterAVP(avp)
    
    new GroupedAVP(code, isVendorSpecific, isMandatory, vendorId, avpList.toList)
  }

  /**
   * Helpers for custom DiameterMessage Serializer.
   * 
   * Useful for handling types correctly.
   */
  def JField2DiameterAVP(tuple: JField): DiameterAVP[Any] = {
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
        val avps = attrValue match {
          case JObject(javps) => for {jField <- javps} yield JField2DiameterAVP(jField)
          case _ => List()
        }
        
        new GroupedAVP(code, isVendorSpecific, false, vendorId, avps)
      
      case DiameterTypes.ADDRESS =>
        new AddressAVP(code, isVendorSpecific, false, vendorId, java.net.InetAddress.getByName(attrValue.extract[String]))
      
      case DiameterTypes.TIME =>
        val sdf = new java.text.SimpleDateFormat("YYYY-MM-dd'T'hh:mm:ss")
        new TimeAVP(code, isVendorSpecific, false, vendorId, TimeAVP.dateToDiameterSeconds(sdf.parse(attrValue.extract[String])))
      
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
  
  /**
   * Helper for custom Diameter message serializer.
   */
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
          JField(avp.getName, JObject(childs))
      
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
  
  /**
   * Custom JSON serializer for the DiameterMessage.
   *
   */
  class DiameterMessageSerializer extends CustomSerializer[DiameterMessage](implicit jsonFormats => (
  {
    case jv: JValue =>
      
      try {
        val avps = jv \ "avps" match {
          case JObject(javps) => for { jField <- javps} yield JField2DiameterAVP(jField)
          case _ => List()
        }
        
        val applicationId = jv \ "applicationId" match {
          case JString(v) => DiameterDictionary.appMapByName(v).code
          case JInt(v) => v.toLong
          case _ => throw new DiameterCodingException("Bad applicationId value")
        }
        
        val commandCode = jv \ "commandCode" match {
          case JString(v) => DiameterDictionary.appMapByCode(applicationId).commandMapByName(v).code
          case JInt(v) => v.toInt
          case _ => throw new DiameterCodingException("Bad commandCode value")
        }
        
        val hopByHopId = jv \ "hopByHopId" match {
          case JInt(v) => v.toInt
          case _ => IDGenerator.nextHopByHopId
        }
        
        val endToEndId = jv \ "endToEndId" match {
          case JInt(v) => v.toInt
          case _ => IDGenerator.nextEndToEndId
        }
        
        new DiameterMessage(
           applicationId,
           commandCode,
           hopByHopId,
           endToEndId,
           avps,
           (jv \ "isRequest").extract[Option[Boolean]].getOrElse(true),
           (jv \ "isProxiable").extract[Option[Boolean]].getOrElse(true),
           (jv \ "isError").extract[Option[Boolean]].getOrElse(false),
           (jv \ "isRetransmission").extract[Option[Boolean]].getOrElse(false)
           )
      } 
      catch {
        case e : Throwable =>
          throw new DiameterCodingException(e.getMessage)
      }
  },
  {
    case dm : DiameterMessage =>
      
      try {
        val javps = for {
          avp <- dm.avps
        } yield diameterAVPToJField(avp)
  
        ("applicationId" -> DiameterDictionary.appMapByCode(dm.applicationId).name) ~
        ("commandCode" -> DiameterDictionary.appMapByCode(dm.applicationId).commandMapByCode(dm.commandCode).name) ~ 
        ("hopByHopId" -> dm.hopByHopId) ~
        ("endToEndId" -> dm.endToEndId) ~
        ("avps" -> JObject(javps)) ~
        ("isRequest" -> dm.isRequest) ~
        ("isProxyable" -> dm.isProxyable) ~
        ("isError" -> dm.isError) ~
        ("isRetransmission" -> dm.isRetransmission)
      }
      catch {
        case e: Throwable =>
          throw new DiameterCodingException(e.getMessage)
      }
  }
  ))
  
  /**
   * For implicit conversion from DiameterMessage to JSON
   */
  implicit def diameterMessageToJson(dm: DiameterMessage): JValue = {
    Extraction.decompose(dm)
  }
  
  /**
   * For implicit conversion from JSON to DiameterMessage
   */
  implicit def jsonToDiameterMessage(jv: JValue): DiameterMessage = {
    jv.extract[DiameterMessage]
  }
}

