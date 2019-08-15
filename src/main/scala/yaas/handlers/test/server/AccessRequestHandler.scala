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


class AccessRequestHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {
  
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
  val warmupQuery = sql"""select sysdate from dual""".as[String]
  db.run(warmupQuery)
  
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
        JField(k, v) <- attr
      } yield JField2RadiusAVP((k, v))
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
      
    // Priorities Client --> Realm --> Global
    val jConfig = jGlobalConfig.merge(jRealmConfig.key(realm, "DEFAULT")).
      merge(jRadiusClientConfig.key(nasIpAddress, "DEFAULT"))
      
    if(false){
      log.debug("jGlobalConfig: {}\\n", pretty(jGlobalConfig))
      log.debug("jRealmConfig: {}\\n", pretty(jRealmConfig))
      log.debug("jRadiusClientConfig: {}\\n", pretty(jRadiusClientConfig))
      log.debug("jConfig: {}\\n", pretty(jConfig))
    }
    
    // Cook some configuration variables
    val blockedServiceOption = (jConfig \ "blockedService").extract[Option[String]]
    val permissiveServiceOption = (jConfig \ "permissiveService").extract[Option[String]]
    val sendReject = (jConfig \ "sendReject").extract[Option[Boolean]].getOrElse(true)
    val rejectServiceOption = if(!sendReject) (jConfig \ "rejectService").extract[Option[String]] else None
    val acceptOnProxyError = (jConfig \ "acceptOnProxyError").extract[Option[Boolean]].getOrElse(false)
    val authLocalOption = (jConfig \ "authLocal").extract[Option[String]]
    
    if(log.isDebugEnabled) log.debug("permissiveServiceOption: {}, sendReject: {}", permissiveServiceOption, sendReject)
    if(log.isDebugEnabled) log.debug("rejectServiceOption: {}, acceptOnProxyError: {}, authLocalOption: {}", rejectServiceOption, acceptOnProxyError, authLocalOption)
    
    // Lookup client
    val provisionType = jConfig.jStr("provisionType").getOrElse("database")
    val subscriberFuture = provisionType match {
      case "database" =>
        // userName, password, serviceName, addonServiceName, legacyClientId
        // if UserName and password, they have to be verified
        // Stored procedure example: val clientQuery = sql"""{call getClient($nasPort, $nasIpAddress)}""".as[(Option[String], Option[String], Option[String], Option[String])]
        log.debug(s"Executing query with $nasIpAddress : $nasPort")
        val clientQuery = sql"""select UserName, Password, ServiceName, AddonServiceName, LegacyClientId, Status from "CLIENT" where NASIPAddress=$nasIpAddress and NASPort=$nasPort""".as[(Option[String], Option[String], Option[String], Option[String], Option[String], Int)]
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
                (subscriberEntry \ "legacyClientId").extract[Option[String]],
                0
            ))
          )
          
      case "none" =>
        Future.successful(Vector((None, None, None, None, None, 0)))
                
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
          
          val (userNameOption, passwordOption, serviceNameOption, addonServiceNameOption, legacyClientIdOption, status) = 
            // Client found
            if(queryResult.length > 0){
              queryResult(0) 
            }
            // Client not found
            else 
            {
              log.warning(s"Client not found $nasIpAddress : $nasPort - $userName")
              if(permissiveServiceOption.isEmpty) rejectReason = Some("Client not provisioned")
              // userName, password, serviceName, addonServiceName
              (None, None, permissiveServiceOption, None, None, 0)
            }
          
          if(log.isDebugEnabled) log.debug(s"Client: ${legacyClientIdOption.getOrElse("")}, serviceName: ${serviceNameOption.getOrElse("")}, addonServiceName: ${addonServiceNameOption.getOrElse("")}")
          
          // Verify password
          authLocalOption match {
            case Some("database") =>
              passwordOption match {
                case Some(provisionedPassword) =>
                  if (! (request >>++ "User-Password").equals(OctetOps.fromUTF8ToHex(provisionedPassword))){
                    log.debug("Incorrect password")
                    rejectReason = Some("Incorrect User-Name or User-Password")
                  }
                  
                case None => 
                  // Not provisioned. Do not verify
              }
              
            case Some("file") =>
              val subscriberEntry = jSpecialUsersConfig \ userName
              if(subscriberEntry == JNothing){
                log.warning(s"User-Name $userName not found in file")
                rejectReason = Some(s"User-Name $userName not found in file")
              } else {
                subscriberEntry \ "password" match {
                  case JString(password) =>
                    if (! (request >>++ "User-Password").equals(OctetOps.fromUTF8ToHex(password))){
                      log.debug("Incorrect password")
                      rejectReason = Some(s"Incorrect Password for $userName")
                    }
                    
                  case _ =>
                    // Not provisioned. Do not verify
                }
              }
            
            case _ =>
              // Do not verify
            
          }
          
          // Override service
          val oServiceNameOption = jConfig \ "overrideService" match {
            case JString(overrideServiceName) => Some(overrideServiceName)
            case _ => serviceNameOption
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
                  rejectReason = Some("Proxy: " + (packet >>++ "Reply-Message"))
                } else log.debug("received response")
                
                // Filter the valid AVPs from proxy
                packet.avps.filter(avp =>
                      avp.getName == "Class" || 
                      avp.getName == "Framed-IP-Address" || 
                      avp.getName == "Reply-Message" || 
                      avp.getName == "User-Password" ||
                      avp.getName == "Framed-Protocol"
                  )
              } recover {
                case e: Exception if acceptOnProxyError =>
                  log.error(s"Timeout in ServerGroup $group. Will continue due to permissive proxy policy")
                  // Permissive policy. Return empty list of attributes
                  List[RadiusAVP[Any]]()
              }
          }
         
          proxyAVPFuture.onComplete {
            case Success(proxyAVPList) =>
              
              // Decide whether to reject
              val response = rejectReason match {
                case Some(reason) if(rejectServiceOption.isEmpty) =>
                  // Real reject, since there is no rejectService configured
                  RadiusPacket.responseFailure(request) << ("Reply-Message", reason)
                  
                case Some(reason) =>
                  // Use the rejectService
                  RadiusPacket.response(request) << 
                    getRadiusAttrs(jServiceConfig, rejectServiceOption, "radiusAttrs") <<
                    ("Class" -> s"S=${rejectServiceOption.get}") <<
                    ("Class" -> s"R=1")
                    
                  
                case None =>
                  val serviceAVPList = getRadiusAttrs(jServiceConfig, oServiceNameOption, "radiusAttrs")
                  if(log.isDebugEnabled){
                    log.debug("Adding proxy attributes: {}", proxyAVPList.map(_.pretty).mkString)
                    log.debug("Adding service attributes: {}", serviceAVPList.map(_.pretty).mkString)
                  }
                  
                  // Accept. Add proxied attributes and service attributes
                  val radiusResponse = RadiusPacket.response(request) << 
                    proxyAVPList << 
                    serviceAVPList <<
                    ("Class" -> s"S=${oServiceNameOption.get}")
                  
                  // Add addon service attributes. May be blocked, or else the configured one (e.g. advertising)
                  if(status == 2){
                    val blockedServiceAttributes = getRadiusAttrs(jServiceConfig, blockedServiceOption, "radiusAttrs")
                    if(log.isDebugEnabled) log.debug("Adding blocked service attributes: {}", blockedServiceAttributes.map(_.pretty).mkString)
                    radiusResponse << blockedServiceAttributes
                  }
                  else {
                    addonServiceNameOption match {
                      case Some(addOnServiceName) =>
                        val addonServiceAttributes = getRadiusAttrs(jServiceConfig, addonServiceNameOption, "radiusAttrs")
                        val noAddonServiceAttributes = getRadiusAttrs(jServiceConfig, addonServiceNameOption, "nonOverridableRadiusAttrs")
                        if(log.isDebugEnabled) log.debug("Adding addon service attributes: {}", addonServiceAttributes.map(_.pretty).mkString)
                        if(log.isDebugEnabled) log.debug("Adding non overridable addon service attributes: {}", noAddonServiceAttributes.map(_.pretty).mkString)
                        
                        radiusResponse :<< addonServiceAttributes << noAddonServiceAttributes
                      case None =>
                    }
                  }
                  
                  radiusResponse
              }
              
              if(response.code == RadiusPacket.ACCESS_ACCEPT){
                // Add the rest of the attributes
                
                // Get domain attributes
                val realmAVPList = getRadiusAttrs(jRealmConfig, Some(realm), "radiusAttrs")
                val noRealmAVPList = getRadiusAttrs(jRealmConfig, Some(realm), "nonOverridableRadiusAttrs")
                
                // Get global attributes
                val globalAVPList = getRadiusAttrs(jGlobalConfig, None, "radiusAttrs")
                val noGlobalAVPList = getRadiusAttrs(jGlobalConfig, None, "nonOverridableRadiusAttrs")
                
                if(log.isDebugEnabled){
                  log.debug("Adding realm attributes: {}", realmAVPList.map(_.pretty).mkString)
                  log.debug("Adding non overridable realm attributes: {}", noRealmAVPList.map(_.pretty).mkString)
                  log.debug("Adding global attributes: {}", globalAVPList.map(_.pretty).mkString)
                  log.debug("Adding non overridable global attributes: {}", noGlobalAVPList.map(_.pretty).mkString)
                }
                
                // Insert into packet
                response <<? realmAVPList <<? globalAVPList << noRealmAVPList << noGlobalAVPList
                
                // Add another class attribute
                legacyClientIdOption.map(lcid => response << ("Class" -> s"C=$lcid"))
              }
              
            sendRadiusResponse(response)
              
            case Failure(e) =>
              dropRadiusPacket
          }
    }
  }
  
  override def postStop = {
    db.close()
  }
}
