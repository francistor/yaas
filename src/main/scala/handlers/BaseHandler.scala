package handlers

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import diameterServer._
import diameterServer.coding._
import diameterServer.util.IDGenerator
import diameterServer.coding.DiameterConversions._

object BaseHandler extends DiameterApplicationHandler {
  
  println("Instantiated BaseHandler")
  
  implicit val idGen = new IDGenerator
  
  def handleMessage(message : DiameterMessage, originActor: ActorRef) = {
    println("Test handler is handling a message!")
    println(message)
    
    val reply = DiameterMessage.reply(message)
    reply << ("Result-Code" -> DiameterMessage.DIAMETER_UNABLE_TO_COMPLY.toString)
    
    DiameterMessageHandler.sendReply(reply, originActor)
  }
  
  def handleCER(message : DiameterMessage, originActor: ActorRef) = {
    
  }
}