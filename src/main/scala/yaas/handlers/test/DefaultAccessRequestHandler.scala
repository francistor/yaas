package yaas.handlers.test

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

import slick.dbio.DBIO
import slick.jdbc.JdbcBackend.Database
// Generic JDBC is deprecated. Use any profile
import slick.jdbc.SQLiteProfile.api._

class DefaultAccessRequestHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {
  
  log.info("Instantiated AccessRequestHandler")
  
  override def handleRadiusMessage(ctx: RadiusRequestContext) = {
    // Should always be an access-request anyway
    ctx.requestPacket.code match {
      case RadiusPacket.ACCESS_REQUEST => handleAccessRequest(ctx)
    }
  }
  
  def handleAccessRequest(implicit ctx: RadiusRequestContext) = {
    
    // Proxy to superserver
    sendRadiusGroupRequest("yaas-superserver-group", ctx.requestPacket.proxyRequest, 500, 1).onComplete {
              
        case Success(response) =>
          sendRadiusResponse(ctx.requestPacket.proxyResponse(response))
          
        case Failure(e) =>
          dropRadiusPacket
          log.error(e.getMessage)
        }
  }
    
  override def postStop = {

  }
}