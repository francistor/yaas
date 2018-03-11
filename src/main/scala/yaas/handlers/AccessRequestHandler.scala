package yaas.handlers

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._
import yaas.coding.diameter._
import yaas.util.IDGenerator
import yaas.coding.diameter.DiameterConversions._
import yaas.config.DiameterConfigManager
import yaas.dictionary.DiameterDictionary
import yaas.coding.radius._
import yaas.server.RadiusActorMessages._

class AccessRequestHandler extends MessageHandler {
  
  log.info("Instantiated AccessRequestHandler")
  
  implicit val idGen = new IDGenerator
  
  override def handleRadiusMessage(radiusPacket : RadiusPacket, originActor: ActorRef, origin: RadiusOrigin) = {
    // Should always be an access-request anyway
    radiusPacket.code match {
      case RadiusPacket.ACCESS_REQUEST => handleAccessRequest(radiusPacket, originActor, origin)
    }
  }
  
  def handleAccessRequest(radiusPacket : RadiusPacket, originActor: ActorRef, origin: RadiusOrigin) = {
    // Just sends an empty success response
    val passwordAVP = new OctetsRadiusAVP(2, 0, "this is the password".getBytes())
    val reply = RadiusPacket.reply(radiusPacket)
    import scala.collection.immutable.Queue
    reply.avps = Queue[RadiusAVP[Any]](passwordAVP)
    sendRadiusReply(reply, originActor, origin)
  }
}