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

class TestServerAccountingRequestHandler(statsServer: ActorRef) extends MessageHandler(statsServer) {
  
  log.info("Instantiated AccountingRequestHandler")
  
  override def handleRadiusMessage(ctx: RadiusRequestContext) = {
    // Should always be an access-request anyway
    ctx.requestPacket.code match {
      case RadiusPacket.ACCOUNTING_REQUEST => handleAccountingRequest(ctx)
    }
  }
  
  def handleAccountingRequest(implicit ctx: RadiusRequestContext) = {

    sendRadiusGroupRequest("allServers", ctx.requestPacket.proxyRequest, 1000, 1).onComplete{
      case Success(response) =>
        sendRadiusResponse(ctx.requestPacket.proxyResponse(response))
      case Failure(e) =>
        log.error(e.getMessage)
        dropRadiusPacket
    }
  }
}