package yaas.handlers.test.superserver

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import yaas.server._
import yaas.coding._
import yaas.coding.RadiusPacket._
import yaas.server.RadiusActorMessages._
import yaas.coding.RadiusConversions._
import yaas.server.MessageHandler
import yaas.database._
import org.json4s.JsonDSL._

class AccountingRequestHandler(statsServer: ActorRef) extends MessageHandler(statsServer) {
  
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
      if(userName.contains("sessiondb")){
        if((requestPacket >> "Acct-Status-Type").contentEquals("Start")){
          
          // Store in sessionDatabase
          SessionDatabase.putSession(new JSession(
            requestPacket >> "Acct-Session-Id",
            requestPacket >> "Framed-IP-Address",
            "Client-Id",
            "MAC-O",
            System.currentTimeMillis,
            ("a" -> "aval") ~ ("b" -> 2)))
        } else if((requestPacket >> "Acct-Status-Type").contentEquals("Stop")){
          
          // Remove session
           SessionDatabase.removeSession(requestPacket >>++ "Acct-Session-Id")
        }
      }
      
      sendRadiusResponse(requestPacket.response() << ("User-Name" -> userName))
    }
  }
}
