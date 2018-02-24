package yaas.handlers

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._
import yaas.diameter.coding._
import yaas.util.IDGenerator
import yaas.diameter.coding.DiameterConversions._
import yaas.config.DiameterConfigManager
import yaas.dictionary.DiameterDictionary

class GxHandler extends DiameterMessageHandler {
  
  log.info("Instantiated GxHandler")
  
  implicit val idGen = new IDGenerator
  
  override def handleMessage(message : DiameterMessage, originActor: ActorRef) = {
    
    message.command match {
      case "Credit-Control" => handleCCR(message, originActor)
    }
  }
  
  def handleCCR(message : DiameterMessage, originActor: ActorRef) = {
    
    // Send request to proxy
    val request = DiameterMessage.request("Gx", "Credit-Control")
    request << ("Origin-Host" -> DiameterConfigManager.getDiameterConfig.diameterHost)
    request << ("Origin-Realm" -> DiameterConfigManager.getDiameterConfig.diameterRealm)
    request << ("Destination-Realm" -> "8950AAA")
    request << ("Session-Id" -> "1")
    request << ("Auth-Application-Id" -> "Gx")
    request << ("CC-Request-Type" -> "Initial")
    request << ("CC-Request-Number" -> 1)
    sendRequest(request, 5000, (proxyReply) => {
        if(proxyReply.isDefined) log.info("Received proxy reply {}", proxyReply.get) else log.info("Proxy timeout")
        val reply = DiameterMessage.reply(message)  
        reply << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
        sendReply(reply, originActor)
      }
    )
  }
}