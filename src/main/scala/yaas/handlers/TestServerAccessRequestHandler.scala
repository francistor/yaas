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

class TestServerAccessRequestHandler(statsServer: ActorRef) extends MessageHandler(statsServer) {
  
  log.info("Instantiated AccessRequestHandler")
  
  override def handleRadiusMessage(ctx: RadiusRequestContext) = {
    // Should always be an access-request anyway
    ctx.requestPacket.code match {
      case RadiusPacket.ACCESS_REQUEST => handleAccessRequest(ctx)
    }
  }
  
  def handleAccessRequest(implicit ctx: RadiusRequestContext) = {
    
    // Proxy to upstream server
    // Uses random policy. "superserver" points to a single server and should respond correctly
    // Use group "allServers" to force a previous failed request to "not-existing-server"
    sendRadiusGroupRequest("superServer", ctx.requestPacket.proxyRequest, 500, 1).onComplete{
      case Success(response) => sendRadiusResponse(ctx.requestPacket.proxyResponse(response))
      case Failure(e) =>
        log.error(e.getMessage)
    }
  }
}