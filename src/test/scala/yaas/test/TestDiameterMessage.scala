package yaas.test

import akka.actor.ActorSystem
import akka.util.{ByteStringBuilder, ByteString}
import akka.testkit.{TestKit}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike, MustMatchers}
import org.scalatest.FlatSpec

import yaas.dictionary._
import yaas.coding._
import yaas.coding.DiameterConversions._

import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.{read, write, writePretty}

class TestDiameterMessage extends TestKit(ActorSystem("AAATest"))
  with WordSpecLike with MustMatchers with BeforeAndAfterAll {
  
  implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN
  
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
  
  "Diameter Dictionary has been correctly loaded" in {
    val avpNameMap = DiameterDictionary.avpMapByName
    avpNameMap("User-Name") mustEqual BasicAVPDictItem(1, 0, "User-Name", DiameterTypes.UTF8STRING)
    avpNameMap("3GPP-Bearer-Identifier") mustEqual BasicAVPDictItem(1020, 10415, "Bearer-Identifier", DiameterTypes.OCTETSTRING)
    
    val avpCodeMap = DiameterDictionary.avpMapByCode
    avpCodeMap.get((10415, 1020)) mustEqual Some(BasicAVPDictItem(1020, 10415, "Bearer-Identifier", DiameterTypes.OCTETSTRING))
  }
  
  "OctetString serialization and deserialization" in {
	  val octetStringAVP = new OctetStringAVP(1 /* code */, true /* vendorspecific */, false /* mandatory */, 1001 /* vendorId */, List[Byte](1, 2, 3))
	  DiameterAVP(octetStringAVP.getBytes) mustEqual octetStringAVP
  }
	  
  "Integer32 serialization and deserialization" in {
	  val integer32AVP = new Integer32AVP(2, true, false, 1001, 31416)
	  DiameterAVP(integer32AVP.getBytes) mustEqual integer32AVP    
  }
	  
  "Integer64 serialization and deserialization" in {
    val integer64AVP = new Integer64AVP(3, true, false, 1001, 1000000000000000000L)
	  DiameterAVP(integer64AVP.getBytes) mustEqual integer64AVP
  }
  
  "Unsigned32 serialization and deserialization" in {
	  val unsigned32AVP = new Unsigned32AVP(4, true, false, 1001, 2147484648L)
	  DiameterAVP(unsigned32AVP.getBytes) mustEqual unsigned32AVP    
  }
      
  "Unsigned64 serialization and deserialization" in {
	  val unsigned64AVP = new Integer64AVP(5, true, false, 1001, 1000000000000000000L)
	  DiameterAVP(unsigned64AVP.getBytes) mustEqual unsigned64AVP    
  }
  
  "Float32 serialization and deserialization" in {
	  val float32AVP = new Float32AVP(6, true, false, 1001, 193.4f)
	  DiameterAVP(float32AVP.getBytes) mustEqual float32AVP    
  }
      
  "Float64 serialization and deserialization" in {
	  val float64AVP = new Float64AVP(7, true, false, 1001, 193.4d)
	  DiameterAVP(float64AVP.getBytes) mustEqual float64AVP
  }
  
  "Address serialization and deserialization" in {
	  val address4AVP = new AddressAVP(8, true, false, 1001, java.net.InetAddress.getByName("200.44.3.1"))
	  DiameterAVP(address4AVP.getBytes) mustEqual address4AVP
	  
	  val address6AVP = new AddressAVP(8, true, false, 1001, java.net.InetAddress.getByName("2001:cafe:8008:1234:5678::0"))
	  DiameterAVP(address6AVP.getBytes) mustEqual address6AVP
  }
  
  "Time serialization and deserialization" in {
    val timeAVP = new TimeAVP(9, true, false, 1001, TimeAVP.dateToDiameterSeconds(new java.util.Date))
    DiameterAVP(timeAVP.getBytes) mustEqual timeAVP
  }
  
  "UTF8String and derivatives serialization and deserialization" in {
    val stringAVP = new UTF8StringAVP(10, true, false, 1001, "hello world! desde España")
    DiameterAVP(stringAVP.getBytes) mustEqual stringAVP
    
    val diameterIdentityAVP = new DiameterIdentityAVP(11, true, false, 1001, "DiameterIdentity")
    DiameterAVP(diameterIdentityAVP.getBytes) mustEqual diameterIdentityAVP
    
    val diameterURIAVP = new DiameterURIAVP(12, true, false, 1001, "DiameterURI")
    DiameterAVP(diameterURIAVP.getBytes) mustEqual diameterURIAVP
    
    val ipFilterRuleAVP = new DiameterURIAVP(13, true, false, 1001, "1.1.1.1 pass")
    DiameterAVP(ipFilterRuleAVP.getBytes) mustEqual ipFilterRuleAVP
  }
  
  "Radius addresses serialization and deserialization" in {
    val ipv4Address = new IPv4AddressAVP(14, true, false, 1001, java.net.InetAddress.getByName("200.44.3.1"))
    DiameterAVP(ipv4Address.getBytes) mustEqual ipv4Address
    
    val ipv6Address = new IPv6AddressAVP(15, true, false, 1001, java.net.InetAddress.getByName("2001:cafe:8008:1234:5678::0"))
    DiameterAVP(ipv6Address.getBytes) mustEqual ipv6Address
    
    val ipv6Prefix = new IPv6PrefixAVP(16, true, false, 1001, "2001:cafe:8008:abcd:0:0:0:0/64")
    DiameterAVP(ipv6Prefix.getBytes) mustEqual ipv6Prefix
  }
  
  "Enumerated serialization and deserialization" in {
    val enumAVP = new EnumeratedAVP(17, true, false, 1001, 1)
    DiameterAVP(enumAVP.getBytes) mustEqual enumAVP
  }
  
  "Grouped serialization and deserialization" in {
    val groupedAVP = new GroupedAVP(18, true, false, 1001, List(new Integer32AVP(2, true, false, 1001, 99), new UTF8StringAVP(10, true, false, 1001, "hello world! desde España")))
    DiameterAVP(groupedAVP.getBytes) mustEqual groupedAVP
  }
  
  "Diameter Message serialization and deserialization" in {
    val groupedAVP = new GroupedAVP(18, true, false, 1001, List(new Integer32AVP(2, true, false, 1001, 99), new UTF8StringAVP(10, true, false, 1001, "hello world! desde España")))
    val diameterMessage = new DiameterMessage(1000 /* applicationId */ , 2000 /* commandCode */, 99 /* h2hId */, 88 /* e2eId */, List(groupedAVP) /* value */, isRequest = true /* isRequest */)
    val serializedMessage = diameterMessage.getBytes
    val unserializedDiameterMessage = DiameterMessage(serializedMessage)
    //unserializedDiameterMessage.applicationId must be (diameterMessage.applicationId)
    //unserializedDiameterMessage.commandCode must be (diameterMessage.commandCode)
    //unserializedDiameterMessage.hopByHopId must be (diameterMessage.hopByHopId)
    //unserializedDiameterMessage.endToEndId must be (diameterMessage.endToEndId)
    //unserializedDiameterMessage.avps mustEqual(List(groupedAVP))
    unserializedDiameterMessage mustEqual diameterMessage
  }
  
  "Adding and retrieving simple avp to Diameter Message" in {
    val message = DiameterMessage.request("Base", "Capabilities-Exchange")
    
    // Add AVP to Diameter message
    message << ("Result-Code" -> DiameterMessage.DIAMETER_UNABLE_TO_COMPLY.toString)
    
    // Get AVP from Diameter message
    val resultCode : String = message >> "Result-Code"
    // Implicit conversion
    resultCode mustEqual DiameterMessage.DIAMETER_UNABLE_TO_COMPLY.toString
    // Forced conversion
    (message >> "Result-Code").get.toString mustEqual DiameterMessage.DIAMETER_UNABLE_TO_COMPLY.toString
  }
  
  "Adding and retrieving grouped avp to Diameter Message" in {
    val diameterMessage = DiameterMessage.request("Gx", "Credit-Control")
    val userIMSI = "99999"
    
    // Add AVP to Diameter message
    val gavp: GroupedAVP = ("Subscription-Id", Seq(("Subscription-Id-Type" -> "EndUserIMSI"), ("Subscription-Id-Data", userIMSI)))
    diameterMessage << gavp
    
    // Retrieve value
    ((diameterMessage >>> "Subscription-Id").get >> "Subscription-Id-Data").get.toString mustEqual userIMSI
  }
  
  "DiameterMessage serialization and deserialization" in {
    val diameterMessage = DiameterMessage.request("Gx", "Credit-Control")
    val userIMSI = "99999"
    
    // Add AVP to Diameter message
    val gavp = ("Subscription-Id", Seq(("Subscription-Id-Type" -> "EndUserIMSI"), ("Subscription-Id-Data", userIMSI)))
    diameterMessage << gavp << ("Framed-IP-Address" -> "1.2.3.4") << ("Session-Id", "This-is-the-session-id")
    
    val jsonDiameterMessage: JValue = diameterMessage
    
    val deserializedDiameterMessage: DiameterMessage = jsonDiameterMessage
    
    deserializedDiameterMessage mustEqual diameterMessage
  }
}