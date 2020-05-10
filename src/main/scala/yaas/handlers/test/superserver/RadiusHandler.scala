package yaas.handlers.test.superserver

import akka.actor.ActorRef
import org.json4s.JsonDSL._
import yaas.coding.RadiusConversions._
import yaas.coding._
import yaas.database.{JSession, SessionDatabase}
import yaas.handlers.RadiusPacketUtils.getFromClass
import yaas.server.{MessageHandler, _}

class RadiusHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {
  
  log.info("Instantiated AccessRequestHandler")
  
  private val nCPUOperations = Option(System.getenv("YAAS_CPU_OPERATIONS"))
    .orElse(Option(System.getProperty("YAAS_CPU_OPERATIONS")))
    .map(req => Integer.parseInt(req)).getOrElse(0)

  override def handleRadiusMessage(ctx: RadiusRequestContext): Unit = {
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

      case RadiusPacket.ACCOUNTING_REQUEST =>
        try {
          handleAccountingRequest(ctx)
        } catch {
          case e: RadiusExtractionException =>
            log.error(e, e.getMessage)
            dropRadiusPacket(ctx)
        }
    }
  }
  
  def handleAccessRequest(implicit ctx: RadiusRequestContext): Unit = {
    
    val requestPacket = ctx.requestPacket
    val userName: String = requestPacket >> "User-Name"
    val password: String = requestPacket >> "User-Password"
    
    // Will send a response depending on the realm
    if(userName.contains("reject")){
      // Appending userName as an utility for automated testing
      sendRadiusResponse(requestPacket.responseFailure << ("Reply-Message" -> ("Rejected by superserver! " + userName)))
    } 
    else if(userName.contains("drop")){
      // Required for stats
      dropRadiusPacket
    } 
    else {
      if(nCPUOperations > 0) for(i <- 0 to nCPUOperations) Math.atan(Math.random())
      // Echo password and set Framed-Protocol for the benefit of automated testing
      sendRadiusResponse(requestPacket.response() << ("User-Password" -> password) << ("Framed-Protocol" -> "PPP"))
    }
  }

  def handleAccountingRequest(implicit ctx: RadiusRequestContext): Unit = {

    val request = ctx.requestPacket
    val nasIpAddress = request.S("NAS-IP-Address")
    val userName = request.S("User-Name")
    val userNameComponents = userName.split("@")
    val realm = if(userNameComponents.length > 1) userNameComponents(1) else "NONE"

    // Requests with Service-Type = "Call-Check" are copy-mode targets
    val prefix = if((request >>* "Service-Type").contains("Call-Check")) "CC-" else "SS-"

    // Will send a response depending on the contents of the User-Name
    if(userName.contains("drop")){
      dropRadiusPacket
    }
    else{
      if(!userName.contains("nosession")){
        if((request >> "Acct-Status-Type").contentEquals("Start")){

          // Store in sessionDatabase
          SessionDatabase.putSession(new JSession(
            prefix + (request >> "Acct-Session-Id").get,
            prefix + (request >> "Framed-IP-Address").getOrElse(""),
            getFromClass(request, "C").getOrElse("<SS-UNKNOWN>"),
            getFromClass(request, "M").getOrElse("<SS-UNKNOWN>"),
            List(nasIpAddress, realm),
            System.currentTimeMillis,
            System.currentTimeMillis(),
            ("a" -> "aval") ~ ("b" -> 2)))
        }
        else if((request >> "Acct-Status-Type").contentEquals("Stop")){

          // Remove session
          SessionDatabase.removeSession(prefix + (request >>* "Acct-Session-Id"))
        }
      }

      if(nCPUOperations > 0) for(i <- 0 to nCPUOperations) Math.atan(Math.random())
      sendRadiusResponse(request.response() << ("User-Name" -> userName))
    }
  }
}