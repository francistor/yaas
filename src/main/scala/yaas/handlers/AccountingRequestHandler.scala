package yaas.handlers

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._
import yaas.coding._
import yaas.config.DiameterConfigManager
import yaas.dictionary.DiameterDictionary
import yaas.coding.RadiusPacket._
import yaas.server.RadiusActorMessages._
import yaas.coding.RadiusConversions._

import scala.util.{Success, Failure}
import yaas.server.MessageHandler

class AccountingRequestHandler(statsServer: ActorRef) extends MessageHandler(statsServer) {
  
  log.info("Instantiated AccountingRequestHandler")
  
  override def handleRadiusMessage(ctx: RadiusRequestContext) = {
    // Should always be an access-request anyway
    ctx.requestPacket.code match {
      case RadiusPacket.ACCOUNTING_REQUEST => handleAccountingRequest(ctx)
    }
  }
  
  def handleAccountingRequest(implicit ctx: RadiusRequestContext) = {
    import scala.collection.immutable.Queue
    
    // Proxy to upstream server
    val proxyRequest = RadiusPacket.request(ACCOUNTING_REQUEST)
    proxyRequest << ("NAS-IP-Address" -> "1.2.3.4")

    sendRadiusGroupRequest("allServers", proxyRequest, 1000, 0).onComplete{
      case Success(proxyResponse) =>
        val responsePacket = RadiusPacket.response(ctx.requestPacket, true)
        sendRadiusResponse(responsePacket)
      case Failure(e) =>
        log.error(e.getMessage)
    }
  }
}