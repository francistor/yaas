package yaas.handlers

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._
import yaas.coding.diameter._
import yaas.coding.diameter.DiameterConversions._
import yaas.config.DiameterConfigManager
import yaas.dictionary.DiameterDictionary
import yaas.coding.radius._
import yaas.coding.radius.RadiusPacket._
import yaas.server.RadiusActorMessages._

class AccessRequestHandler extends MessageHandler {
  
  log.info("Instantiated AccessRequestHandler")
  
  override def handleRadiusMessage(radiusPacket : RadiusPacket, originActor: ActorRef, origin: RadiusEndpoint) = {
    // Should always be an access-request anyway
    radiusPacket.code match {
      case RadiusPacket.ACCESS_REQUEST => handleAccessRequest(radiusPacket, originActor, origin)
    }
  }
  
  def handleAccessRequest(radiusPacket : RadiusPacket, originActor: ActorRef, origin: RadiusEndpoint) = {
    import scala.collection.immutable.Queue
    
    // Proxy to upstream server
    val passwordAVP = new OctetsRadiusAVP(2, 0, "this is the password that I have encrypted".getBytes())
    val proxyRequest = RadiusPacket.request(ACCESS_REQUEST)
    proxyRequest.avps = Queue[RadiusAVP[Any]](passwordAVP)
        
    sendRadiusGroupRequest("allServers", proxyRequest, 1000, (response: Option[RadiusPacket]) => {
        //if(response.isDefined){
          val reply = RadiusPacket.reply(radiusPacket)
          reply.avps = Queue[RadiusAVP[Any]](passwordAVP)
          sendRadiusReply(reply, originActor, origin)
        //}
      }
    )
  }
}