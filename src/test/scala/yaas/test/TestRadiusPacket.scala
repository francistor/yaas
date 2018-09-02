package yaas.test

import scala.collection.mutable.Queue

import akka.actor.ActorSystem
import akka.util.{ByteStringBuilder, ByteString}
import akka.testkit.{TestKit}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike, MustMatchers}
import org.scalatest.FlatSpec

import yaas.dictionary._
import yaas.coding._
import yaas.coding.RadiusConversions._
import yaas.util.OctetOps

import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.{read, write, writePretty}

class TestRadiusMessage extends TestKit(ActorSystem("AAATest"))
  with WordSpecLike with MustMatchers with BeforeAndAfterAll {
  
  implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN
  
  val authenticator = RadiusPacket.newAuthenticator
  
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
  
  "Radius Dictionary has been correctly loaded" in {
    val avpNameMap = RadiusDictionary.avpMapByName
    avpNameMap("User-Name") mustEqual RadiusAVPDictItem(1, 0, "User-Name", RadiusTypes.STRING, 0, false, None, None)
    avpNameMap("PSA-CampaignData") mustEqual RadiusAVPDictItem(107, 21100, "PSA-CampaignData", RadiusTypes.STRING, 0, false, None, None)
    
    val avpCodeMap = RadiusDictionary.avpMapByCode
    avpCodeMap.get((21100, 102)) mustEqual Some(RadiusAVPDictItem(102, 21100, "PSA-Service-Name", RadiusTypes.STRING, 0, false, None, None))
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
    val responsePacket = RadiusPacket.response(requestPacket, true)
    requestPacket.avps = requestPacket.avps :+ passwordAVP
    
    val decodedResponse = RadiusPacket(requestPacket.getResponseBytes("secret"), Some(requestAuthenticator), "secret")
    decodedResponse >> "User-Password" mustEqual Some(passwordAVP)
  }
  
  "RadiusPacket serialization and deserialization" in {
    val radiusPacket = RadiusPacket.request(RadiusPacket.ACCESS_REQUEST)
    radiusPacket << ("User-Name" -> "frg@tid.es")
    radiusPacket << ("User-Password" -> OctetOps.fromUTF8ToHex("secret password!"))
    radiusPacket << ("NAS-IP-Address" -> "1.2.3.4")
    radiusPacket << ("Service-Type" -> "Outbound")
    radiusPacket << ("CHAP-Password" -> "0011ABCD")
    radiusPacket << ("Class" -> "class-1")
    radiusPacket << ("Class" -> "class-2")
    
    val jsonRadiusPacket: JValue = radiusPacket
    val deserializedRadiusPacket: RadiusPacket = jsonRadiusPacket
    
    deserializedRadiusPacket mustEqual radiusPacket
    
  }
}