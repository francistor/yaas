package handlers

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import diameterServer._
import diameterServer.coding._
import diameterServer.util.IDGenerator
import diameterServer.coding.DiameterConversions._
import diameterServer.config.DiameterConfigManager
import diameterServer.dictionary.DiameterDictionary

object GxHandler extends DiameterApplicationHandler {
  
  println("Instantiated GxHandler")
  
  implicit val idGen = new IDGenerator
  
  def handleMessage(message : DiameterMessage, originActor: ActorRef) = {
    
    message.command match {
      case "Credit-Control" => handleCCR(message, originActor)
    }
  }
  
  def handleCCR(message : DiameterMessage, originActor: ActorRef) = {
    
    val reply = DiameterMessage.reply(message)
    
    // Add basic parameters
    val diameterConfig = DiameterConfigManager.getDiameterConfig
    
    reply << ("Origin-Host" -> diameterConfig.diameterHost)
    reply << ("Origin-Realm" -> diameterConfig.diameterRealm)    
    
    reply << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
    
    DiameterMessageHandler.sendReply(reply, originActor)
  }
}