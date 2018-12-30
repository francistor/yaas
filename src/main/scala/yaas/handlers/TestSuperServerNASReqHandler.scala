package yaas.handlers

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._
import yaas.coding._
import yaas.coding.DiameterConversions._
import yaas.config.DiameterConfigManager
import yaas.dictionary.DiameterDictionary

import scala.util.{Success, Failure}
import yaas.server.MessageHandler

class TestSuperServerNASReqHandler(statsServer: ActorRef) extends MessageHandler(statsServer) {
  
  log.info("Instantiated NASREQ Handler")
  
  override def handleDiameterMessage(ctx: DiameterRequestContext) = {
    
    ctx.diameterRequest.command match {
      case "AA" => handleAAR(ctx)
      case "AC" => handleACR(ctx)
    }
  }
  
  def handleAAR(implicit ctx: DiameterRequestContext) = {
    
    val request = ctx.diameterRequest
    val framedInterfaceId: String = request >> "Framed-Interface-Id"
    val chapIdent: String = (request >>> "CHAP-Auth") >> "CHAP-Ident"
    
    val answer = DiameterMessage.answer(ctx.diameterRequest)
    answer << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS) << ("Class", framedInterfaceId) << ("Class", chapIdent)
    
    sendDiameterAnswer(answer)
  }
  
  def handleACR(implicit ctx: DiameterRequestContext) = {
    
    val answer = DiameterMessage.answer(ctx.diameterRequest)
    answer << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
    
    sendDiameterAnswer(answer)
  }
}