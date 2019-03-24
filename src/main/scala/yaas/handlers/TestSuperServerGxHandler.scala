package yaas.handlers

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._
import yaas.coding._
import yaas.coding.DiameterConversions._
import yaas.config.DiameterConfigManager
import yaas.dictionary.DiameterDictionary

import scala.util.{Success, Failure}
import yaas.server.MessageHandler

class TestSuperServerGxHandler(statsServer: ActorRef) extends MessageHandler(statsServer) {
  
  log.info("Instantiated GxHandler")
  
  override def handleDiameterMessage(ctx: DiameterRequestContext) = {
    
    ctx.diameterRequest.command match {
      case "Credit-Control" => handleCCR(ctx)
    }
  }
  
  def handleCCR(implicit ctx: DiameterRequestContext) = {
    
    // Echoes the subscription-id in the charging rule name
    val request = ctx.diameterRequest
    val answer = DiameterMessage.answer(request)
    val subscriptionIdData: String = request >>> "Subscription-Id" >> "Subscription-Id-Data"
    answer << 
      ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS) <<<
      ("3GPP-Charging-Rule-Install" -> Seq(("3GPP-Charging-Rule-Name" -> subscriptionIdData)))
      
    sendDiameterAnswer(answer)
  }
}