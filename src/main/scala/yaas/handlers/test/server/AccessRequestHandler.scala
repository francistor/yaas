package yaas.handlers.test.server

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._
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

class AccessRequestHandler(statsServer: ActorRef) extends MessageHandler(statsServer) {
  
  log.info("Instantiated AccessRequestHandler")
  
  // Get the database configuration
  val dbConf = yaas.config.ConfigManager.getConfigObject("clientsDatabase.json")
  val nThreads = (dbConf \ "numThreads").extract[Int]
  
  val db = Database.forURL(
      (dbConf \ "url").extract[String], 
      driver=(dbConf \ "driver").extract[String], 
      executor = slick.util.AsyncExecutor("db-executor", numThreads=nThreads, queueSize=1000)
      )
      
  // Warm-up database connection
  val clientQuery = sql"""select legacy_client_id from CLIENTS where USERNAME = 'user_1'""".as[String]
  db.run(clientQuery)
  
  override def handleRadiusMessage(ctx: RadiusRequestContext) = {
    // Should always be an access-request anyway
    ctx.requestPacket.code match {
      case RadiusPacket.ACCESS_REQUEST => handleAccessRequest(ctx)
    }
  }
  
  def handleAccessRequest(implicit ctx: RadiusRequestContext) = {
    
    // Initial action depending on the login
    val request = ctx.requestPacket
    val userName = request >>++ "User-Name"
    val login = userName.split("@")(0)
    
    // Lookup username
    val legacyClientIdFuture = if(userName.contains("clientdb")){
      // Look in the database and get Future
      val clientQuery = sql"""select legacy_client_id from CLIENTS where USERNAME = $login""".as[String]
      db.run(clientQuery)
    } else {
      // Successful Future
      scala.concurrent.Future.successful(Vector("unprovisioned_legacy_client_id"))
    }
    
    legacyClientIdFuture.onComplete {
        case Success(queryResult) => 
          // If client not found, drop
          if(queryResult.length == 0){
            log.error(s"Client not found $login")
            dropRadiusPacket
          }
          else {
            // Proxy to upstream server group "superserver" with single server and random policy
            // Response will depend on the realm (accept by default, @reject or @grop)
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