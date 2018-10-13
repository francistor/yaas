package yaas.handlers

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._
import yaas.config.DiameterConfigManager
import yaas.dictionary.DiameterDictionary
import yaas.coding._
import yaas.coding.RadiusPacket._
import yaas.server.RadiusActorMessages._
import yaas.coding.RadiusConversions._

import scala.util.{Success, Failure}
import yaas.server.MessageHandler

class TestSuperServerAccessRequestHandler(statsServer: ActorRef) extends MessageHandler(statsServer) {
  
  log.info("Instantiated AccessRequestHandler")
  
  override def handleRadiusMessage(ctx: RadiusRequestContext) = {
    // Should always be an access-request anyway
    ctx.requestPacket.code match {
      case RadiusPacket.ACCESS_REQUEST => handleAccessRequest(ctx)
    }
  }
  
  def handleAccessRequest(implicit ctx: RadiusRequestContext) = {
    
    val requestPacket = ctx.requestPacket
    val password: String = requestPacket >> "User-Password"
    
    // Will send a response depending on the contents of the User-Name
    if(password.contains("reject")){
      sendRadiusResponse(requestPacket.responseFailure << ("Reply-Message" -> "The reply message"))
    } 
    else if(password.contains("drop")){
      dropRadiusPacket
    } 
    else {
      sendRadiusResponse(requestPacket.response() << ("User-Password" -> password))
    }
  }
}