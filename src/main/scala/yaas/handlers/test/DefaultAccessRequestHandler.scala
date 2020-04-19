package yaas.handlers.test

import akka.actor.ActorRef
import yaas.coding._
import yaas.server._

import scala.util.{Failure, Success}

/**
 * Simple AccessRequest handler, which does proxy to yaas-superserver-group
 * @param statsServer the stats server
 * @param configObject the config object (unused)
 */
class DefaultAccessRequestHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {
  
  log.info("Instantiated AccessRequestHandler")
  
  override def handleRadiusMessage(ctx: RadiusRequestContext): Unit = {
    // Should always be an access-request anyway
    ctx.requestPacket.code match {
      case RadiusPacket.ACCESS_REQUEST => handleAccessRequest(ctx)
    }
  }
  
  def handleAccessRequest(implicit ctx: RadiusRequestContext): Unit = {
    
    // Proxy to superserver
    sendRadiusGroupRequest("yaas-superserver-group", ctx.requestPacket.proxyRequest, 500, 1).onComplete {
              
        case Success(response) =>
          sendRadiusResponse(ctx.requestPacket.proxyResponse(response))
          
        case Failure(e) =>
          dropRadiusPacket
          log.error(e.getMessage)
        }
  }

}