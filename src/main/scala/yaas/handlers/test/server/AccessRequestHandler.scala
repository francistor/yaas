package yaas.handlers.test.server

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import scala.concurrent.Future

import yaas.server._
import yaas.coding._
import yaas.util.OctetOps
import yaas.coding.RadiusPacket._
import yaas.server.RadiusActorMessages._
import yaas.coding.RadiusConversions._
import yaas.config.ConfigManager._

import scala.util.{Success, Failure}
import yaas.server.MessageHandler

import slick.dbio.DBIO
import slick.jdbc.JdbcBackend.Database
// Generic JDBC is deprecated. Use any profile
import slick.jdbc.SQLiteProfile.api._

import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._


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
  
  override def handleRadiusMessage(ctx: RadiusRequestContext) = {
    // Should always be an access-request anyway
    ctx.requestPacket.code match {
      case RadiusPacket.ACCESS_REQUEST => handleAccessRequest(ctx)
    }
  }
  
  /**
   * Looks for client in database or file, depending on the realm. May set reject message if no permissiveService and not provisioned
   * Proxies also depending on the realm
   * Validates locally the password if configured for the realm
   * 
   * Rejects if there is a reason and the reject service is not configured
   * Otherwise sets the service to reject or to the assigned one and merges attributes
   */
  def handleAccessRequest(implicit ctx: RadiusRequestContext) = {
    
    /*
     * Returns the list of radius attributes from the specified object (jValue) for the specified key and 
     * property (typically, radiusAttrs or nonOverridableRadiusAttrs)
     */
    def getRadiusAttrs(jValue: JValue, key: Option[String], propName: String)  = {
      val nv = key match {
        case Some(v) => jValue \ v
        case None => jValue
      }
      
      for {
        JArray(attrs) <- (nv \ propName)
        JObject(attr) <- attrs
        JField(k, JString(v)) <- attr
      } yield Tuple2RadiusAVP((k, v))
    }
    
    
    
    /**
     * Read configuration
     */
    val jGlobalConfig = getConfigObject("handlerConf/globalConfig.json")
    val jRealmConfig = getConfigObject("handlerConf/realmConfig.json")
    val jServiceConfig = getConfigObject("handlerConf/serviceConfig.json")
    val jRadiusClientConfig = getConfigObject("handlerConf/radiusClientConfig.json")
    val jSpecialUsersConfig = getConfigObject("handlerConf/specialUsers.json")
    
    val request = ctx.requestPacket
    
    // Get request data. TODO: This throws exceptions
    val nasPort = request.L("NAS-Port")
    val nasIpAddress = request.S("NAS-IP-Address")
    val userName = request.S("User-Name")
    val userNameComponents = userName.split("@")
    val realm = if(userNameComponents.length > 1) userNameComponents(1) else "NONE"
      
    val permissiveService = (jGlobalConfig \ "permissiveService").extract[Option[String]]
    val rejectService = (jGlobalConfig \ "rejectService").extract[Option[String]]
    
    // Priorities Client --> Realm --> Global
    val jConfig = jGlobalConfig.merge(jRealmConfig.key(realm, "DEFAULT")).merge(jRadiusClientConfig.key(nasIpAddress, "DEFAULT"))
    
    // Lookup client
    val provisionType = jConfig.jStr("provisionType").getOrElse("database")
    val subscriberFuture = provisionType match {
      case "database" =>
        // userName, password, serviceName, addonServiceName, legacyClientId
        // if UserName or password, they have to be verified
        // Stored procedure example: val clientQuery = sql"""{call getClient($nasPort, $nasIpAddress)}""".as[(Option[String], Option[String], Option[String], Option[String])]
        log.debug("Executing query with $nasIpAddress : $nasPort")
        val clientQuery = sql"""select UserName, Password, ServiceName, AddonServiceName, LegacyClientId from "CLIENT" where NASIPAddress=$nasIpAddress and NASPort=$nasPort""".as[(Option[String], Option[String], Option[String], Option[String], Option[String])]
        db.run(clientQuery)

      case "file" => 
        log.debug(s"Searching in file using $userName")
        val subscriberEntry = jSpecialUsersConfig \ userName
          Future.successful(
            if(subscriberEntry == JNothing) Vector() else
            Vector((
                Some(userName), 
                (subscriberEntry \ "password").extract[Option[String]], 
                (subscriberEntry \ "serviceName").extract[Option[String]], 
                None,
                (subscriberEntry \ "legacyClientId").extract[Option[String]]
            ))
          )
                
      case _ =>
        Future.failed(new Exception("Invalid provision type $provisionType"))
    }
    
    
    subscriberFuture.onComplete {
        case Failure(error) => 
          // Database error. Drop packet to signal something is wrong
          dropRadiusPacket
          log.error(error.getMessage)
          
        case Success(queryResult) => 
          log.debug("Query executed")
          
          var rejectReason: Option[String] = None
          
          val (userNameOption, passwordOption, serviceNameOption, addonServiceNameOption, legacyClientIdOption) = 
            // Client found
            if(queryResult.length > 0){
              queryResult(0) 
            }
            else 
            {
              // Client not found.
              log.warning(s"Client not found $nasIpAddress : $nasPort - $userName")
              if(permissiveService.isEmpty) rejectReason = Some("Client not provisioned")
              // userName, password, serviceName, addonServiceName
              (None, None, permissiveService, None, None)
            }
          
          log.debug(s"Client: ${legacyClientIdOption.getOrElse("")}), serviceName: ${serviceNameOption.getOrElse("")}, addonServiceName: ${addonServiceNameOption.getOrElse("")}")
          
          // Check password if provisioned locally
          passwordOption match {
            case Some(provisionedPassword) =>
              if (! (request >>++ "User-Password").equals(OctetOps.fromUTF8ToHex(provisionedPassword))){
                log.debug("Incorrect password")
                rejectReason = Some("Incorrect User-Name or User-Password")
              }
              
            case None => 
          }

          // Proxy if requested for this realm
          val proxyGroup = jConfig.jStr("proxyServerGroup")
          val proxyAVPFuture = proxyGroup match {
            
            case _ if(rejectReason.nonEmpty) =>
              // If to be rejected, do not ever proxy
              Future(List[RadiusAVP[Any]]())
              
            case None =>
              // No proxy required. Return empty list of attributes
              Future(List[RadiusAVP[Any]]())
              
            case Some(group) => 
              // Remove sensitive information
              val proxyRequest = request.proxyRequest.
                removeAll("NAS-IP-Address").
                removeAll("NAS-Port").
                removeAll("NAS-Identifier").
                removeAll("NAS-Port-Id")
                
              val proxyTimeoutMillis = jGlobalConfig.jInt("proxyTimeoutMillis").getOrElse(3000)
              val proxyRetries = jGlobalConfig.jInt("proxyRetries").getOrElse(1)
              
              log.debug(s"Proxy to $group, timeout: $proxyTimeoutMillis, retries: $proxyRetries")
              
              sendRadiusGroupRequest(group, proxyRequest, proxyTimeoutMillis, proxyRetries).map { packet => 
                if(packet.code == RadiusPacket.ACCESS_REJECT){
                  log.info(s"$userName rejected by proxy server")
                  rejectReason = Some("Proxy: " + (packet >>+ "Reply-Message"))
                } else log.debug("received response")
                
                // Filter the valid AVPs from proxy
                packet.avps.filter(avp =>
                      avp.getName == "Class" || 
                      avp.getName == "Framed-IP-Address" || 
                      avp.getName == "Reply-Message" || 
                      avp.getName == "User-Password"
                  )
              } recover {
                case e: Exception =>
                  log.error(s"Timeout in ServerGroup $group. Will continue due to permissive proxy policy")
                  // Permissive policy. Return empty list of attributes
                  List[RadiusAVP[Any]]()
              }
          }
         
          proxyAVPFuture.onComplete {
            case Success(proxyAVPList) =>
              
              // Decide whether to reject
              val response = rejectReason match {
                case Some(reason) if(rejectService.isEmpty) =>
                  // Real reject, since there is no rejectService configured
                  RadiusPacket.responseFailure(request) << ("Reply-Message", reason)
                  
                case Some(reason) =>
                  // Use the rejectService
                  RadiusPacket.response(request) << getRadiusAttrs(jServiceConfig, rejectService, "radiusAttrs")
                  
                case None =>
                  // Accept. Add proxied attributes and service attributes
                  RadiusPacket.response(request) << proxyAVPList << getRadiusAttrs(jServiceConfig, serviceNameOption, "radiusAttrs")
              }
              
              if(response.code == RadiusPacket.ACCESS_ACCEPT){
                // Add the rest of the attributes
                
                // Get domain attributes
                val realmAVPList = getRadiusAttrs(jRealmConfig, Some(realm), "radiusAttrs")
                val noRealmAVPList = getRadiusAttrs(jRealmConfig, Some(realm), "nonOverridableRadiusAttrs")
                    
                // Get global attributes
                val globalAVPList = getRadiusAttrs(jGlobalConfig, Some(realm), "radiusAttrs")
                val noGlobalAVPList = getRadiusAttrs(jGlobalConfig, Some(realm), "nonOverridableRadiusAttrs")
                
                // Insert into packet
                realmAVPList.foreach(avp => response <<? avp)
                globalAVPList.foreach(avp => response <<? avp)
                noRealmAVPList.foreach(avp => response << avp)
                noGlobalAVPList.foreach(avp => response << avp)
                
                // Add class. TODO: Add one class attribute per attribute, and code as A=
                legacyClientIdOption.map(lcid => response << ("Class" -> lcid))
              }
              
            sendRadiusResponse(response)
              
            case Failure(e) =>
              // Never happens because of the recover (permissive policy if proxy does not answer)
          }
    }
  }
  
  override def postStop = {
    db.close()
  }
}

class AccessRequestHandler2(statsServer: ActorRef) extends MessageHandler(statsServer) {
  
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
  val clientQuery = sql"""select legacy_client_id from Client where UserName = 'user_1'""".as[String]
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
      val clientQuery = sql"""select legacy_client_id from Client where UserName = $login""".as[String]
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
            // Response will depend on the realm (accept by default, @reject or @drop)
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