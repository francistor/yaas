package yaas.handlers.test

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


class DefaultAccountingRequestHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {
  
  log.info("Instantiated AccountingRequestHandler")
  
  val writer = new yaas.cdr.CDRFileWriter("cdr", "accounting_request_%d{yyyyMMdd-HHmm}.txt")
  //val format = RadiusSerialFormat.newCSVFormat(List("User-Name", "Acct-Session-Id"))
  val format = RadiusSerialFormat.newLivingstoneFormat(List())
  
  override def handleRadiusMessage(ctx: RadiusRequestContext) = {
    // Should always be an access-request anyway
    ctx.requestPacket.code match {
      case RadiusPacket.ACCOUNTING_REQUEST => handleAccountingRequest(ctx)
    }
  }
  
  def handleAccountingRequest(implicit ctx: RadiusRequestContext) = {
    
    writer.writeCDR(ctx.requestPacket.getCDR(format))
    
    sendRadiusGroupRequest("superServer", ctx.requestPacket.proxyRequest, 500, 1).onComplete{
      case Success(response) =>
        sendRadiusResponse(ctx.requestPacket.proxyResponse(response))
      case Failure(e) =>
        log.error(e.getMessage)
        dropRadiusPacket
    }
  }
  
  override def postStop = {
    super.postStop
  }
}