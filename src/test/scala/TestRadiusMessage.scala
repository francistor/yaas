package yaas.test

import scala.collection.mutable.Queue

import akka.actor.ActorSystem
import akka.util.{ByteStringBuilder, ByteString}
import akka.testkit.{TestKit}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike, MustMatchers}
import org.scalatest.FlatSpec

import yaas.dictionary._
import yaas.radius.coding._

class TestRadiusMessage extends TestKit(ActorSystem("AAATest"))
  with WordSpecLike with MustMatchers with BeforeAndAfterAll {
  
  implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN
  
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
  
  "Radius Dictionary has been correctly loaded" in {
    val avpNameMap = RadiusDictionary.avpMapByName
    avpNameMap("User-Name") mustEqual RadiusAVPDictItem(1, 0, "User-Name", RadiusTypes.STRING, false, None, None)
    avpNameMap("PSA-CampaignData") mustEqual RadiusAVPDictItem(107, 21100, "PSA-CampaignData", RadiusTypes.STRING, false, None, None)
    
    val avpCodeMap = RadiusDictionary.avpMapByCode
    avpCodeMap.get((21100, 102)) mustEqual Some(RadiusAVPDictItem(102, 21100, "PSA-Service-Name", RadiusTypes.STRING, false, None, None))
  }
  
  "OctetString serialization and deserialization" in {
    val octetsAVP = new OctetsRadiusAVP(3, 0, List[Byte](1, 2, 3))
    RadiusAVP(octetsAVP.getBytes) mustEqual octetsAVP
  }
  
  "String serialization and deserialization" in {
    val stringAVP = new StringRadiusAVP(1, 0, "My big string from España")
    RadiusAVP(stringAVP.getBytes) mustEqual stringAVP
  }
  
  "Address serialization and deserialization" in {
    val addressAVP = new AddressRadiusAVP(4, 0, java.net.InetAddress.getByName("100.100.100.100"))
    RadiusAVP(addressAVP.getBytes) mustEqual addressAVP
  }
  
  "Integer serialization and deserialization" in {
    val integerAVP = new IntegerRadiusAVP(5, 0, 2147491917L)
    RadiusAVP(integerAVP.getBytes) mustEqual integerAVP
  }
  
  "Vendor specific Time serialization and deserialization" in {
    val timeAVP = new TimeRadiusAVP(200, 21100, new java.util.Date((System.currentTimeMillis()/1000L)*1000))
    RadiusAVP(timeAVP.getBytes) mustEqual timeAVP
  }
  
  "IPV6Address serialization and deserialization" in {
    val ipv6AddressAVP = new IPv6AddressRadiusAVP(95, 0, java.net.InetAddress.getByName("2001:cafe:0000:0000:0001:0002:0003:0004"))
    RadiusAVP(ipv6AddressAVP.getBytes) mustEqual ipv6AddressAVP
  }
  
  "IPV6Prefix serialization and deserialization" in {
    val ipv6PrefixAVP = new IPv6PrefixRadiusAVP(97, 0, "2001:cafe:0:0:0:0:0:0/128")
    val uncoded = RadiusAVP(ipv6PrefixAVP.getBytes)
    println(uncoded.code, uncoded.vendorId, uncoded.pretty)
    RadiusAVP(ipv6PrefixAVP.getBytes) mustEqual ipv6PrefixAVP
  }
  
  "FramedInterfaceId serialization and deserialization" in {
    val framedInterfaceIdAVP = new InterfaceIdRadiusAVP(96, 0, List[Byte](1,2,3,4,5,6,7,8))
    RadiusAVP(framedInterfaceIdAVP.getBytes) mustEqual framedInterfaceIdAVP
  }
  
  "Password coding and decoding" in {
    val authenticator = RadiusPacket.newAuthenticator
    val secretAttribute = "this is a secret"
    val encrypted = RadiusPacket.dencrypt1(authenticator, "secret", secretAttribute.getBytes("UTF-8"))
    val decrypted = RadiusPacket.dencrypt1(authenticator, "secret", encrypted)
    decrypted mustEqual secretAttribute.getBytes("UTF-8")
  }
  
}