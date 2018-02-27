package yaas.test

import scala.collection.mutable.Queue

import akka.actor.ActorSystem
import akka.util.{ByteStringBuilder, ByteString}
import akka.testkit.{TestKit}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike, MustMatchers}
import org.scalatest.FlatSpec

import yaas.dictionary._

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
  
}