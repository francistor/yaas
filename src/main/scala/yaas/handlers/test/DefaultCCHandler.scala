package yaas.handlers.test

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import yaas.server._
import yaas.coding._
import yaas.coding.DiameterConversions._
import scala.util.{Success, Failure}
import yaas.server.MessageHandler

class DefaultCCHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {
  
  log.info("Instantiated CCHandler")
  
  override def handleDiameterMessage(ctx: DiameterRequestContext) = {
    
    ctx.diameterRequest.command match {
      case "Credit-Control" => handleCCR(ctx)
    }
  }
  
  def handleCCR(implicit ctx: DiameterRequestContext) = {
    
    // Sends request to server, forwarding the subscription id
    // The server echoes back the subscription-id-data as the user-name and sets Granted-Service-Units to 3600 time units
    
    val request = ctx.diameterRequest
    val subscriptionIdType: String = request >>> "Subscription-Id" >> "Subscription-Id-Type"
    val subscriptionIdData: String = request >>> "Subscription-Id" >> "Subscription-Id-Data"
  
    
    val subscriptionIdAVP: GroupedAVP = ("Subscription-Id" -> List(("Subscription-Id-Type" -> subscriptionIdType), ("Subscription-Id-Data" -> subscriptionIdData)))
    val serverRequest = DiameterMessage.request("Credit-Control", "Credit-Control") << 
    subscriptionIdAVP <<
    ("Destination-Realm" -> "yaassuperserver") <<
    (request >> "Session-Id") <<
    (request >> "Auth-Application-Id") <<
    (request >> "CC-Request-Type") <<
    (request >> "CC-Request-Number")
    
    sendDiameterRequest(serverRequest, 1000).onComplete {
      case Success(upstreamAnswer) =>
        
        val answer = ctx.diameterRequest.answer << 
        ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS) <<
        upstreamAnswer.get("User-Name") <<
        upstreamAnswer.get("Granted-Service-Unit")
        
        sendDiameterAnswer(answer)
        
      case Failure(e) =>
        log.error(e.getMessage)
    }
    
  }
}