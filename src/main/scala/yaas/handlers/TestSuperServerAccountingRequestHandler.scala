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

import yaas.database._

import org.json4s.JsonDSL._

class TestSuperServerAccountingRequestHandler(statsServer: ActorRef) extends MessageHandler(statsServer) {
  
  log.info("Instantiated AccessRequestHandler")
  
  override def handleRadiusMessage(ctx: RadiusRequestContext) = {
    // Should always be an access-request anyway
    ctx.requestPacket.code match {
      case RadiusPacket.ACCOUNTING_REQUEST => handleAccountingRequest(ctx)
    }
  }
  
  def handleAccountingRequest(implicit ctx: RadiusRequestContext) = {
    
    val requestPacket = ctx.requestPacket
    val userName: String = requestPacket >> "User-Name"
    
    // Will send a response depending on the contents of the User-Name
    if(userName.contains("drop")){
      dropRadiusPacket
    } 
    else {
      if((requestPacket >> "Acct-Status-Type").contentEquals("Start")){
        
        // Store in sessionDatabase
         SessionDatabase.putSession(new JSession(
            requestPacket >> "Acct-Session-Id",
            requestPacket >> "Framed-IP-Address",
            "Client-Id",
            "MAC-O",
            System.currentTimeMillis,
            ("a" -> "aval") ~ ("b" -> 2)))
      }
      
      sendRadiusResponse(requestPacket.response() << ("User-Name" -> userName))
    }
  }
}
