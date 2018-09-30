package yaas.handlers

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._
import yaas.config.DiameterConfigManager
import yaas.dictionary.DiameterDictionary
import yaas.coding._
import yaas.coding.RadiusPacket._
import yaas.server.RadiusActorMessages._
import yaas.coding.RadiusConversions._

import scala.util.{Success, Failure}
import yaas.server.MessageHandler

class AccessRequestHandler(statsServer: ActorRef) extends MessageHandler(statsServer) {
  
  log.info("Instantiated AccessRequestHandler")
  
  override def handleRadiusMessage(radiusPacket : RadiusPacket, originActor: ActorRef, origin: RadiusEndpoint, receivedTimestamp: Long) = {
    // Should always be an access-request anyway
    radiusPacket.code match {
      case RadiusPacket.ACCESS_REQUEST => handleAccessRequest(radiusPacket, originActor, origin, receivedTimestamp)
    }
  }
  
  def handleAccessRequest(requestPacket : RadiusPacket, originActor: ActorRef, origin: RadiusEndpoint, receivedTimestamp: Long) = {
    import scala.collection.immutable.Queue
    
    // Proxy to upstream server
    val passwordAVP = new OctetsRadiusAVP(2, 0, "Password sent by YAAS a b c d e f g".getBytes())
    val proxyRequest = RadiusPacket.request(ACCESS_REQUEST)
    proxyRequest.avps = Queue[RadiusAVP[Any]](passwordAVP)

    sendRadiusGroupRequest("allServers", proxyRequest, 1000, 0).onComplete{
      case Success(proxyResponse) =>
        val responsePacket = RadiusPacket.response(requestPacket)
        responsePacket << ("User-Password" -> "Password sent by YAAS a b c d e f g")
        sendRadiusResponse(responsePacket, requestPacket, originActor, origin, receivedTimestamp)
      case Failure(e) =>
        log.error(e.getMessage)
    }
  }
}