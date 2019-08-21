package yaas.handlers.test.superserver

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import yaas.server._
import yaas.coding._
import yaas.coding.DiameterConversions._
import yaas.server.MessageHandler
import yaas.database._
import org.json4s.JsonDSL._

class NASReqHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {
  
  log.info("Instantiated NASREQ Handler")
  
  val nCPUOperations = Option(System.getenv("YAAS_CPU_OPERATIONS"))
    .orElse(Option(System.getProperty("YAAS_CPU_OPERATIONS")))
    .map(req => Integer.parseInt(req)).getOrElse(0)

  
  override def handleDiameterMessage(ctx: DiameterRequestContext) = {
    
    ctx.diameterRequest.command match {
      case "AA" => handleAAR(ctx)
      case "AC" => handleACR(ctx)
    }
  }
  
  /**
   * Sends back two Class attributes with the Framed-Interface-Id and CHAP-Auth.CHAP.Ident
   */
  def handleAAR(implicit ctx: DiameterRequestContext) = {
    
    val request = ctx.diameterRequest
    val framedInterfaceId: String = request >> "Framed-Interface-Id"
    val chapIdent: String = (request >>> "CHAP-Auth") >> "CHAP-Ident"
    
    val answer = DiameterMessage.answer(ctx.diameterRequest)
    answer << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS) << ("Class", framedInterfaceId) << ("Class", chapIdent)
    
    // Fake CPU load
    if(nCPUOperations > 0) for(i <- 0 to nCPUOperations) Math.atan(Math.random())
    
    sendDiameterAnswer(answer)
  }
  
  def handleACR(implicit ctx: DiameterRequestContext) = {
    
    val request = ctx.diameterRequest
    
    if(request >>++ "User-Name" contains("sessiondb")){
      if((request >> "Accounting-Record-Type").contentEquals("START_RECORD")){
        
          // Store in sessions database
          SessionDatabase.putSession(new JSession(
            request >> "Session-Id",
            request >> "Framed-IP-Address",
            "Client-Id",
            "0",
            System.currentTimeMillis(),
            ("uno" -> "uno") ~ ("dos" -> "dos")))
  
        } else if((request >> "Accounting-Record-Type").contentEquals("STOP_RECORD")){
          
          // Remove session
           SessionDatabase.removeSession(request >>++ "Session-Id")
        }
    }
    
    val answer = DiameterMessage.answer(ctx.diameterRequest)
    answer << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
    
    // Fake CPU Load
    if(nCPUOperations > 0) for(i <- 0 to nCPUOperations) Math.atan(Math.random())
    
    sendDiameterAnswer(answer)
  }
}