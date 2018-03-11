package yaas.test

import scala.collection.mutable.Queue

import akka.actor.ActorSystem
import akka.util.{ByteStringBuilder, ByteString}
import akka.testkit.{TestKit}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike, MustMatchers}
import org.scalatest.FlatSpec

import yaas.dictionary._
import yaas.coding.radius._

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
    val secretAttribute = "this is a secret"
    val encrypted = RadiusPacket.dencrypt1(authenticator, "secret", secretAttribute.getBytes("UTF-8"))
    val decrypted = RadiusPacket.dencrypt1(authenticator, "secret", encrypted)
    decrypted mustEqual secretAttribute.getBytes("UTF-8")
  }
  
  "Radius packet serialization and deserialization" in {
    val packet = RadiusPacket.request(RadiusPacket.ACCESS_REQUEST)
    val userNameAVP = new StringRadiusAVP(1, 0, "theuser@name")
    packet.avps = packet.avps :+ userNameAVP
    RadiusPacket(packet.getBytes("<secret>"), "<secret>") mustEqual packet
  }
}