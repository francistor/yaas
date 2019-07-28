package yaas.handlers.test.server

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import yaas.server._
import yaas.coding._
import yaas.coding.DiameterConversions._
import yaas.server.MessageHandler

class GxHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {
  
  log.info("Instantiated GxHandler")
  
  override def handleDiameterMessage(ctx: DiameterRequestContext) = {
    
    ctx.diameterRequest.command match {
      case "Credit-Control" => handleCCR(ctx)
    }
  }
  
  def handleCCR(implicit ctx: DiameterRequestContext) = {
    
    // Generate a failure, in order to make sure that the test in "TestClientMain"
    // validates correctly that the request is sent directly to the superserver
    val answer = DiameterMessage.answer(ctx.diameterRequest) << 
      ("Result-Code" -> DiameterMessage.DIAMETER_UNABLE_TO_COMPLY)
        
    sendDiameterAnswer(answer)
  }
}