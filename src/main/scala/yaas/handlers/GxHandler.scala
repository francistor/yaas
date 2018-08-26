package yaas.handlers

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._
import yaas.coding.diameter._
import yaas.util.IDGenerator
import yaas.coding.diameter.DiameterConversions._
import yaas.config.DiameterConfigManager
import yaas.dictionary.DiameterDictionary

import scala.util.{Success, Failure}

class GxHandler extends MessageHandler {
  
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
      case Success(proxyReply) =>
        log.info("Received proxy reply {}", proxyReply)
        val reply = DiameterMessage.reply(message) 
        reply << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
        sendDiameterReply(reply, originActor)
        
      case Failure(e) =>
        log.error("Proxy timeout")
    }
  }
}