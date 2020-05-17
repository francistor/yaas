package yaas.test

import java.nio.ByteOrder

import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatest.{BeforeAndAfterAll, Matchers, MustMatchers, WordSpecLike}
import yaas.coding.RadiusConversions._
import yaas.coding._
import yaas.dictionary._
import yaas.handlers.RadiusPacketUtils
import yaas.util.OctetOps

class TestRadiusPacket extends TestKit(ActorSystem("AAATest"))
  with WordSpecLike with MustMatchers with BeforeAndAfterAll {
  
  implicit val byteOrder: ByteOrder = java.nio.ByteOrder.BIG_ENDIAN
  
  private val authenticator = RadiusPacket.newAuthenticator
  
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
  
  "Radius Dictionary has been correctly loaded" in {
    val avpNameMap = RadiusDictionary.avpMapByName
    avpNameMap("User-Name") mustEqual RadiusAVPDictItem(1, 0, "User-Name", RadiusTypes.STRING, 0, hasTag = false, None, None)
    avpNameMap("PSA-CampaignData") mustEqual RadiusAVPDictItem(107, 21100, "PSA-CampaignData", RadiusTypes.STRING, 0, hasTag = false, None, None)
    
    val avpCodeMap = RadiusDictionary.avpMapByCode
    //     avpCodeMap.get((21100, 102)) mustEqual Some(RadiusAVPDictItem(102, 21100, "PSA-ServiceName", RadiusTypes.STRING, 0, false, None, None))
    avpCodeMap.get((21100, 102)) must contain(RadiusAVPDictItem(102, 21100, "PSA-ServiceName", RadiusTypes.STRING, 0, hasTag = false, None, None))
  }
  
  "OctetString serialization and deserialization" in {
    val octetsAVP = new OctetsRadiusAVP(3, 0, List[Byte](1, 2, 3))
    RadiusAVP(octetsAVP.getBytes(authenticator, "<secret>"), new Array[Byte](16), "<secret>") mustEqual octetsAVP
  }
  
  "String serialization and deserialization" in {
    val stringAVP = new StringRadiusAVP(1, 0, "My big string from España")
    RadiusAVP(stringAVP.getBytes(authenticator, "<secret>"), new Array[Byte](16), "<secret>") mustEqual stringAVP
  }
  
  "Address serialization and deserialization" in {
    val addressAVP = new AddressRadiusAVP(4, 0, java.net.InetAddress.getByName("100.100.100.100"))
    RadiusAVP(addressAVP.getBytes(authenticator, "<secret>"), new Array[Byte](16), "<secret>") mustEqual addressAVP
  }
  
  "Integer serialization and deserialization" in {
    val integerAVP = new IntegerRadiusAVP(5, 0, 2147491917L)
    RadiusAVP(integerAVP.getBytes(authenticator, "<secret>"), new Array[Byte](16), "<secret>") mustEqual integerAVP
  }
  
  "Vendor specific Time serialization and deserialization" in {
    val timeAVP = new TimeRadiusAVP(200, 21100, new java.util.Date((System.currentTimeMillis()/1000L)*1000))
    RadiusAVP(timeAVP.getBytes(authenticator, "<secret>"), new Array[Byte](16), "<secret>") mustEqual timeAVP
  }
  
  "IPV6Address serialization and deserialization" in {
    val ipv6AddressAVP = new IPv6AddressRadiusAVP(95, 0, java.net.InetAddress.getByName("2001:cafe:0000:0000:0001:0002:0003:0004"))
    RadiusAVP(ipv6AddressAVP.getBytes(authenticator, "<secret>"), new Array[Byte](16), "<secret>") mustEqual ipv6AddressAVP
  }
  
  "IPV6Prefix serialization and deserialization" in {
    val ipv6PrefixAVP = new IPv6PrefixRadiusAVP(97, 0, "2001:cafe:0:0:0:0:0:0/128")
    val uncoded = RadiusAVP(ipv6PrefixAVP.getBytes(authenticator, "<secret>"), new Array[Byte](16), "<secret>")
    RadiusAVP(ipv6PrefixAVP.getBytes(authenticator, "<secret>"), new Array[Byte](16), "<secret>") mustEqual ipv6PrefixAVP
  }
  
  "FramedInterfaceId serialization and deserialization" in {
    val framedInterfaceIdAVP = new InterfaceIdRadiusAVP(96, 0, List[Byte](1,2,3,4,5,6,7,8))
    RadiusAVP(framedInterfaceIdAVP.getBytes(authenticator, "<secret>"), new Array[Byte](16), "<secret>") mustEqual framedInterfaceIdAVP
  }
  
  "Password coding and decoding" in {
    val authenticator = RadiusPacket.newAuthenticator
    val secretAttribute = "the secret is 1 the secret is 1 "
    val encrypted = RadiusPacket.encrypt1(authenticator, "secret", secretAttribute.getBytes("UTF-8"))
    val decrypted = RadiusPacket.decrypt1(authenticator, "secret", encrypted)
    decrypted mustEqual secretAttribute.getBytes("UTF-8")
  }
  
  "Radius packet serialization and deserialization" in {
    val packet = RadiusPacket.request(RadiusPacket.ACCESS_REQUEST)
    val userNameAVP = new StringRadiusAVP(1, 0, "theuser@name")
    packet.avps = packet.avps :+ userNameAVP
    // 150 for the id is an arbitrary value. getRequestBytes will generate an authenticator and modify packet
    RadiusPacket(packet.getRequestBytes("<secret>", 150), None, "<secret>") mustEqual packet
  }
  
  "Password attribute encryption and decryiption in packet" in {
    // Request packet
    val requestPacket = RadiusPacket.request(RadiusPacket.ACCESS_REQUEST)
    val requestBytestring = requestPacket.getRequestBytes("secret", 1)
    val requestAuthenticator = requestPacket.authenticator
    
    // Response packet
    val passwordAVP = new OctetsRadiusAVP(2, 0, "This is the password for the test!".getBytes("UTF-8"))
    val responsePacket = RadiusPacket.response(requestPacket)
    requestPacket.avps = requestPacket.avps :+ passwordAVP
    
    val decodedResponse = RadiusPacket(requestPacket.getResponseBytes("secret"), Some(requestAuthenticator), "secret")
    decodedResponse >> "User-Password" must contain(passwordAVP)
  }
  
  "RadiusPacket serialization and deserialization" in {
    val radiusPacket = RadiusPacket.request(RadiusPacket.ACCESS_REQUEST)
    radiusPacket << ("User-Name" -> "frg@tid.es")
    radiusPacket << ("User-Password" -> OctetOps.fromUTF8ToHex("secret password!"))
    radiusPacket << ("NAS-IP-Address" -> "1.2.3.4")
    radiusPacket << ("Service-Type" -> "Outbound-User")
    radiusPacket << ("CHAP-Password" -> "0011ABCD")
    radiusPacket << ("Class" -> "class-1")
    radiusPacket << ("Class" -> "class-2")
    
    val jsonRadiusPacket: JValue = radiusPacket
    val deserializedRadiusPacket: RadiusPacket = jsonRadiusPacket
    
    deserializedRadiusPacket mustEqual radiusPacket
  }
  
  "RadiusPacket json" in {
    val sRadiusPacket = """
      {
        "code": 4,
        "avps": {
          "NAS-Port": [1],
          "User-Name": ["test@database"],
          "Acct-Session-Id": ["acctSessionId-1"],
          "Framed-IP-Address": ["1.1.1.1"],
          "Acct-Status-Type": ["Start"],
          "Class": ["C:1"],
          "Class": ["S:bab01"]
        }
      }
      """
    val packet: RadiusPacket = parse(sRadiusPacket)
    val jPacket: JValue = packet
    /**
    (jPacket \ "avps" \ "NAS-Port").extract[Int] mustEqual 1
    (jPacket \ "avps" \ "Acct-Status-Type").extract[String] mustEqual "Start"
    * 
    */
    
  }

  "Apply filter" in {
    val theFilter: JValue= parse("""{
      "and": [
        ["NAS-IP-Address", "present"],
        ["User-Name", "contains", "copy"],
        ["NAS-IP-Address", "isNot", "10.0.0.0"],
        ["NAS-IP-Address", "matches", ".+"],
        ["NAS-Port", "isNot", 2],
        ["Framed-IP-Address", "notPresent"],
        ["myCookie", "is", "true"]
      ],
      "or":[
        ["Class", "contains", "not-the-class"],
        ["Class", "is", "class!"],
        ["NAS-Port", "is", 2]
      ]
    }""")

    val packet = RadiusPacket.request(RadiusPacket.ACCESS_REQUEST) <<
      ("User-Name", "copy@me") <<
      ("NAS-IP-Address", "1.1.1.1") <<
      ("NAS-Port", 1) <<
      ("Class", "class!")

    packet.pushCookie("myCookie", "true")

    RadiusPacketUtils.checkRadiusPacket(packet, Some(theFilter)) mustEqual true

    packet.removeAll("Class")
    RadiusPacketUtils.checkRadiusPacket(packet, Some(theFilter)) mustEqual false

  }

  "Attribute mapping" in {
    val theMapA = """
    {
        "allow": ["NAS-IP-Address", "NAS-Port", "Class"],
        "remove": ["Framed-IP-Address", "NAS-Port"],
        "force": [
          ["Class", "Modified Class"]
        ]
      }
    """

    val packet = RadiusPacket.request(RadiusPacket.ACCESS_REQUEST) <<
      ("NAS-IP-Address", "1.1.1.1") <<
      ("NAS-Port", 1) <<
      ("Class", "class!") <<
      ("Framed-IP-Address", "1.2.3.4")

    RadiusPacketUtils.filterAttributes(packet, Some(parse(theMapA)))

    (packet >>* "NAS-IP-Address") mustEqual "1.1.1.1"
    (packet >> "NAS-Port") mustBe empty
    (packet >>* "Class") mustEqual "Modified Class"
  }

}