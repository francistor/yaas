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

class GxHandler(statsServer: ActorRef) extends MessageHandler(statsServer) {
  
  log.info("Instantiated GxHandler")
  
  implicit val idGen = new IDGenerator
  
  override def handleDiameterMessage(message : DiameterMessage, originActor: ActorRef) = {
    
    message.command match {
      case "Credit-Control" => handleCCR(message, originActor)
    }
  }
  
  def handleCCR(message : DiameterMessage, originActor: ActorRef) = {
    
    // Send request to proxy
    val request = DiameterMessage.request("Gx", "Credit-Control")
    request << ("Destination-Realm" -> "8950AAA")
    request << ("Session-Id" -> "1")
    request << ("Auth-Application-Id" -> "Gx")
    request << ("CC-Request-Type" -> "Initial")
    request << ("CC-Request-Number" -> 1)
    
    sendDiameterRequest(request, 5000).onComplete{
      case Success(proxyAnswer) =>
        log.info("Received proxy answer {}", proxyAnswer)
        val answer = DiameterMessage.answer(message) 
        answer << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
        sendDiameterAnswer(answer, originActor)
        
      case Failure(e) =>
        log.error("Proxy timeout")
    }
  }
}