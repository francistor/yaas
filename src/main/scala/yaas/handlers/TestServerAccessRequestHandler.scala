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

import slick.dbio.DBIO
import slick.jdbc.JdbcBackend.Database
// Generic JDBC is deprecated. Use any profile
import slick.jdbc.SQLiteProfile.api._

class TestServerAccessRequestHandler(statsServer: ActorRef) extends MessageHandler(statsServer) {
  
  log.info("Instantiated AccessRequestHandler")
  log.info("Populating Clients Database")
  
  val dbConf = yaas.config.ConfigManager.getConfigObject("clientsDatabase.json")
  val nThreads = (dbConf \ "numThreads").extract[Int]
  
  val db = Database.forURL(
      (dbConf \ "url").extract[String], 
      driver=(dbConf \ "driver").extract[String], 
      executor = slick.util.AsyncExecutor("test1", numThreads=nThreads, queueSize=nThreads)
      )
      
  // Warm-up
  val clientQuery = sql"""select legacy_client_id from CLIENTS where CLIENT_ID = 1""".as[String]
  db.run(clientQuery)
  
  override def handleRadiusMessage(ctx: RadiusRequestContext) = {
    // Should always be an access-request anyway
    ctx.requestPacket.code match {
      case RadiusPacket.ACCESS_REQUEST => handleAccessRequest(ctx)
    }
  }
  
  def handleAccessRequest(implicit ctx: RadiusRequestContext) = {
    
    // Look for client in database
    val clientQuery = sql"""select legacy_client_id from CLIENTS where CLIENT_ID = 1""".as[String]
    db.run(clientQuery).onComplete {
        case Success(queryResult) => 
          // If client not found, drop
          if(queryResult.length == 0){
            log.error("Client not found")
            dropRadiusPacket
          }
          else {
            // Proxy to upstream server group "superserver" with single server and random policy
            // if domain is not @drop, will respond correctly
            // Note: Use group "allServers" if needed to force a previous failed request to "not-existing-server"
            sendRadiusGroupRequest("superServer", ctx.requestPacket.proxyRequest, 500, 1).onComplete {
              
            case Success(response) =>
              // Add legacy_client_id in the Class attribute
              response << ("Class" -> queryResult.head)
              sendRadiusResponse(ctx.requestPacket.proxyResponse(response))
              
            case Failure(e) =>
              dropRadiusPacket
              log.error(e.getMessage)
            }
          }
          
        case Failure(error) => 
          // Database error
          dropRadiusPacket
          log.error(error.getMessage)
    }
    
  }
  
  override def postStop = {
    db.close()
  }
}