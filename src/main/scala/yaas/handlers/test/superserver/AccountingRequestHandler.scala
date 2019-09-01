package yaas.handlers.test.superserver

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import yaas.server._
import yaas.coding._
import yaas.coding.RadiusPacket._
import yaas.server.RadiusActorMessages._
import yaas.coding.RadiusConversions._
import yaas.handlers.RadiusAttrParser._
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
    
    val request = ctx.requestPacket
    val nasIpAddress = request.S("NAS-IP-Address")
    val userName = request.S("User-Name")
    val userNameComponents = userName.split("@")
    val realm = if(userNameComponents.length > 1) userNameComponents(1) else "NONE"
    
    // Will send a response depending on the contents of the User-Name
    if(userName.contains("drop")){
      dropRadiusPacket
    } 
    else{
      
      if((request >> "Acct-Status-Type").contentEquals("Start")){
        
        // Store in sessionDatabase
        SessionDatabase.putSession(new JSession(
          "SS-" + (request >> "Acct-Session-Id").get,
          "SS-" + (request >> "Framed-IP-Address").getOrElse(""),
          getFromClass(request, "C").getOrElse("<SS-UNKNOWN>"),
          getFromClass(request, "M").getOrElse("<SS-UNKNOWN>"),
          List(nasIpAddress, realm),
          System.currentTimeMillis,
          System.currentTimeMillis(),
          ("a" -> "aval") ~ ("b" -> 2)))
          
      } 
      else if((request >> "Acct-Status-Type").contentEquals("Stop")){
        
        // Remove session
         SessionDatabase.removeSession("SS-" + (request >>++ "Acct-Session-Id"))
      }
      
      if(nCPUOperations > 0) for(i <- 0 to nCPUOperations) Math.atan(Math.random())
      sendRadiusResponse(request.response() << ("User-Name" -> userName))
    }
  }
}
