package yaas.handlers

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._
import yaas.coding._
import yaas.util.IDGenerator
import yaas.coding.DiameterConversions._
import yaas.config.DiameterConfigManager
import yaas.dictionary.DiameterDictionary

import scala.util.{Success, Failure}
import yaas.server.MessageHandler

class TestServerGxHandler(statsServer: ActorRef) extends MessageHandler(statsServer) {
  
  log.info("Instantiated GxHandler")
  
  implicit val idGen = new IDGenerator
  
  override def handleDiameterMessage(ctx: DiameterRequestContext) = {
    
    ctx.diameterRequest.command match {
      case "Credit-Control" => handleCCR(ctx)
    }
  }
  
  def handleCCR(implicit ctx: DiameterRequestContext) = {
    
    // Send request to proxy
    val proxyRequest = DiameterMessage.request("Gx", "Credit-Control")
    proxyRequest << ("Destination-Realm" -> "yaassuperserver")
    proxyRequest << ("Session-Id" -> "1")
    proxyRequest << ("Auth-Application-Id" -> "Gx")
    proxyRequest << ("CC-Request-Type" -> "Initial")
    proxyRequest << ("CC-Request-Number" -> System.currentTimeMillis / 1000)
    
    sendDiameterRequest(proxyRequest, 2000).onComplete{
      case Success(proxyAnswer) =>
        log.info("Received proxy answer {}", proxyAnswer)
        val answer = DiameterMessage.answer(ctx.diameterRequest) 
        answer << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
        sendDiameterAnswer(answer)
        
      case Failure(e) =>
        log.error("Proxy timeout")
    }
  }
}