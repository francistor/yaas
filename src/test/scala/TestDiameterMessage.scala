import akka.actor.ActorSystem
import akka.util.{ByteStringBuilder, ByteString}
import akka.testkit.{TestKit}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike, MustMatchers}
import org.scalatest.FlatSpec

import diameterServer._


class TestDiameterMessage extends TestKit(ActorSystem("AAATest"))
  with WordSpecLike with MustMatchers with BeforeAndAfterAll {
  
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
  
  "Retrieval of 24 bit values" in {
    val byteStringBuilder = new ByteStringBuilder()
    byteStringBuilder.putByte(0)
    UByteString.putUnsigned24(byteStringBuilder, 114688)
    UByteString.getUnsigned24(byteStringBuilder.result.slice(1, 4)) must be (114688)
  }
  
  "Dictionary has been correctly loaded" in {
    val avpMap = DiameterDictionary.avpMapByName
    avpMap("User-Name").code must be (1)
  }
  
  "Diameter Message is serialized and deserialized" in {
    val integer32AVP = new Integer32AVP(266, false, false, 0, 12345)
    val diameterMessage = new DiameterMessage(1000, 2000, 99, 999, Seq(integer32AVP), true)
    val serializedMessage = diameterMessage.getBytes
    println(serializedMessage)
    val unserializedDiameterMessage = DiameterMessage(serializedMessage)
    unserializedDiameterMessage.applicationId must be (1000)
  }

}