package yaas.handlers.test.superserver

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import yaas.server._
import yaas.coding._
import yaas.coding.RadiusPacket._
import yaas.server.RadiusActorMessages._
import yaas.coding.RadiusConversions._
import yaas.server.MessageHandler

class AccessRequestHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {
  
  log.info("Instantiated AccessRequestHandler")
  
  val nCPUOperations = Option(System.getenv("YAAS_CPU_OPERATIONS"))
    .orElse(Option(System.getProperty("YAAS_CPU_OPERATIONS")))
    .map(req => Integer.parseInt(req)).getOrElse(0)

  override def handleRadiusMessage(ctx: RadiusRequestContext) = {
    // Should always be an access-request anyway
    ctx.requestPacket.code match {
      case RadiusPacket.ACCESS_REQUEST => 
      try {
          handleAccessRequest(ctx)
        } catch {
        case e: RadiusExtractionException =>
          log.error(e, e.getMessage)
          dropRadiusPacket(ctx)
        }
      }
  }
  
  def handleAccessRequest(implicit ctx: RadiusRequestContext) = {
    
    val requestPacket = ctx.requestPacket
    val userName: String = requestPacket >> "User-Name"
    val password: String = requestPacket >> "User-Password"
    
    // Will send a response depending on the realm
    if(userName.contains("reject")){
      sendRadiusResponse(requestPacket.responseFailure << ("Reply-Message" -> "Rejected by superserver!"))
    } 
    else if(userName.contains("drop")){
      // Required for stats
      dropRadiusPacket
    } 
    else {
      if(nCPUOperations > 0) for(i <- 0 to nCPUOperations) Math.atan(Math.random())
      sendRadiusResponse(requestPacket.response() << ("User-Password" -> password) << ("Framed-Protocol" -> "PPP"))
    }
  }
}