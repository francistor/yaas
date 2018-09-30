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
  
  override def handleDiameterMessage(requestMessage : DiameterMessage, originActor: ActorRef, receivedTimestamp: Long) = {
    
    requestMessage.command match {
      case "Credit-Control" => handleCCR(requestMessage, originActor, receivedTimestamp)
    }
  }
  
  def handleCCR(requestMessage : DiameterMessage, originActor: ActorRef, receivedTimestamp: Long) = {
    
    // Send request to proxy
    val proxyRequest = DiameterMessage.request("Gx", "Credit-Control")
    proxyRequest << ("Destination-Realm" -> "yaassuperserver")
    proxyRequest << ("Session-Id" -> "1")
    proxyRequest << ("Auth-Application-Id" -> "Gx")
    proxyRequest << ("CC-Request-Type" -> "Initial")
    proxyRequest << ("CC-Request-Number" -> 1)
    
    sendDiameterRequest(proxyRequest, 5000).onComplete{
      case Success(proxyAnswer) =>
        log.info("Received proxy answer {}", proxyAnswer)
        val answer = DiameterMessage.answer(requestMessage) 
        answer << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
        sendDiameterAnswer(answer, requestMessage, originActor, receivedTimestamp)
        
      case Failure(e) =>
        log.error("Proxy timeout")
    }
  }
}