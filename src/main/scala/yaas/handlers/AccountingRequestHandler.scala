package yaas.handlers

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._
import yaas.coding.diameter._
import yaas.config.DiameterConfigManager
import yaas.dictionary.DiameterDictionary
import yaas.coding.radius._
import yaas.coding.radius.RadiusPacket._
import yaas.server.RadiusActorMessages._
import yaas.coding.radius.RadiusConversions._

import scala.util.{Success, Failure}

class AccountingRequestHandler extends MessageHandler {
  
  log.info("Instantiated AccountingRequestHandler")
  
  override def handleRadiusMessage(radiusPacket : RadiusPacket, originActor: ActorRef, origin: RadiusEndpoint) = {
    // Should always be an access-request anyway
    radiusPacket.code match {
      case RadiusPacket.ACCOUNTING_REQUEST => handleAccountingRequest(radiusPacket, originActor, origin)
    }
  }
  
  def handleAccountingRequest(radiusPacket : RadiusPacket, originActor: ActorRef, origin: RadiusEndpoint) = {
    import scala.collection.immutable.Queue
    
    // Proxy to upstream server
    val proxyRequest = RadiusPacket.request(ACCOUNTING_REQUEST)
    proxyRequest << ("NAS-IP-Address" -> "1.2.3.4")

    sendRadiusGroupRequest("allServers", proxyRequest, 1000).onComplete{
      case Success(proxyReply) =>
        val reply = RadiusPacket.reply(radiusPacket, true)
        sendRadiusReply(reply, originActor, origin)
      case Failure(e) =>
        log.error(e.getMessage)
    }
  }
}