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

class AccountingRequestHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {
  
  log.info("Instantiated AccessRequestHandler")
  
  val nCPUOperations = Option(System.getenv("YAAS_CPU_OPERATIONS"))
    .orElse(Option(System.getProperty("YAAS_CPU_OPERATIONS")))
    .map(req => Integer.parseInt(req)).getOrElse(0)
  
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
    else{
      if(nCPUOperations > 0) for(i <- 0 to nCPUOperations) Math.atan(Math.random())
      sendRadiusResponse(requestPacket.response() << ("User-Name" -> userName))
    }
  }
}
