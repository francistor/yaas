package yaas.handlers.test.server

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._
import yaas.coding._
import yaas.coding.RadiusPacket._
import yaas.server.RadiusActorMessages._
import yaas.coding.RadiusConversions._
import yaas.config.ConfigManager._
import yaas.database._
import yaas.handlers.RadiusAttrParser._

import scala.util.{Success, Failure}
import yaas.server.MessageHandler

import org.json4s.JsonDSL._

class AccountingRequestHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {
  
  log.info("Instantiated AccountingRequestHandler")

    
  /**
   * Read configuration
   */
  val jGlobalConfig = getConfigObject("handlerConf/globalConfig.json")
  val sessionCDRDir = (jGlobalConfig \ "cdrDir").extract[String] + "/session"
  val serviceCDRDir = (jGlobalConfig \ "cdrDir").extract[String] + "/service"
  
  val sessionCDRWriter = new yaas.cdr.CDRFileWriter(sessionCDRDir, "cdr_%d{yyyyMMdd-HHmm}.txt")
  val serviceCDRWriter = new yaas.cdr.CDRFileWriter(serviceCDRDir, "cdr_%d{yyyyMMdd-HHmm}.txt")
  
  // Write all attributes
  val format = RadiusSerialFormat.newLivingstoneFormat(List())
  
  override def handleRadiusMessage(ctx: RadiusRequestContext) = {
    // Should always be an accounting-request anyway
    ctx.requestPacket.code match {
      case RadiusPacket.ACCOUNTING_REQUEST => handleAccountingRequest(ctx)
    }
  }
  
  def handleAccountingRequest(implicit ctx: RadiusRequestContext) = {
    
    /**
     * Read configuration
     */
    val jGlobalConfig = getConfigObject("handlerConf/globalConfig.json")
    val jRealmConfig = getConfigObject("handlerConf/realmConfig.json")
    val jServiceConfig = getConfigObject("handlerConf/serviceConfig.json")
    val jRadiusClientConfig = getConfigObject("handlerConf/radiusClientConfig.json")
    
    val request = ctx.requestPacket
    
    val nasIpAddress = request.S("NAS-IP-Address")
    val userName = request.S("User-Name")
    val userNameComponents = userName.split("@")
    val realm = if(userNameComponents.length > 1) userNameComponents(1) else "NONE"
    
    val jConfig = jGlobalConfig.merge(jRealmConfig.key(realm, "DEFAULT")).merge(jRadiusClientConfig.key(nasIpAddress, "DEFAULT"))

    // Check whether it is session or serviceCDR, and in that case, get serviceName
    val serviceName = 
      if((jConfig \ "isSRC").extract[Option[Boolean]].getOrElse(false) == true){
        // Radius clients is an SRC
        Some(request >>++ "Class")
      } else {
        val redbackServiceName = (request >> "Service-Name").map(_.stringValue)
        val hwServiceInfo = (request >> "Service-Info").map(_.stringValue)
        val alcServiceActivate = (request >> "Sub-Serv-Activate").map(_.stringValue.split(":")(0))
        val ciscoServiceName = {
          getFromCiscoAVPair(request, "echo-string-1").orElse(getFromCiscoAVPair(request, "service-name"))
        }
        List(redbackServiceName, hwServiceInfo, alcServiceActivate, ciscoServiceName).find(_.isDefined).flatten
      }
    
    // Write CDR to file
    val writeSessionCDR = (jConfig \ "writeSessionCDR").extract[Option[Boolean]].getOrElse(false)
    val writeServiceCDR = (jConfig \ "writeServiceCDR").extract[Option[Boolean]].getOrElse(false)
    if(serviceName.isDefined && writeServiceCDR) 
      serviceCDRWriter.writeCDR(ctx.requestPacket.getCDR(format))
    else if(writeSessionCDR) sessionCDRWriter.writeCDR(ctx.requestPacket.getCDR(format))
      
    // Store in session database
    if(!userName.contains("nosession") && serviceName.isEmpty){
      if((request >> "Acct-Status-Type").contentEquals("Start")){
        
        // Store in sessionDatabase
        SessionDatabase.putSession(new JSession(
          request >> "Acct-Session-Id",
          request >> "Framed-IP-Address",
          getFromClass(request, "C").getOrElse("<UNKNOWN>"),
          getFromClass(request, "M").getOrElse("<UNKNOWN>"),
          List(nasIpAddress, realm),
          System.currentTimeMillis,
          System.currentTimeMillis,
          ("a" -> "aval") ~ ("b" -> 2)))
      } 
      else if((request >> "Acct-Status-Type").contentEquals("Stop")){
        
        // Remove session
         SessionDatabase.removeSession(request >>++ "Acct-Session-Id")
      } else if((request >> "Acct-Status-Type").contentEquals("Interim-Update")){
      
        // Update Session
        SessionDatabase.updateSession(request >> "Acct-Session-Id", Some(("interim" -> true)), true)
      }
    }
    
    val proxyGroup = jConfig.jStr("proxyServerGroup")
    val proxyAVPFuture = proxyGroup match {
      case None =>
        // No proxy
        sendRadiusResponse(request.response())
        
      case Some(proxyGroup) =>
        val proxyRequest = request.proxyRequest.
          removeAll("NAS-IP-Address").
          removeAll("NAS-Port").
          removeAll("NAS-Identifier").
          removeAll("NAS-Port-Id")
                
          val proxyTimeoutMillis = jGlobalConfig.jInt("proxyTimeoutMillis").getOrElse(3000)
          val proxyRetries = jGlobalConfig.jInt("proxyRetries").getOrElse(1)
        
          sendRadiusGroupRequest(proxyGroup, request.proxyRequest, proxyTimeoutMillis, proxyRetries).onComplete{
            case Success(response) =>
              sendRadiusResponse(request.response())
              
            case Failure(e) =>
              log.error(e.getMessage)
              
              // Only for testing purposes. In a production handler there should always be an answer, even
              // before doing proxy
              dropRadiusPacket
          }   
    }
  }
  
  override def postStop = {
    super.postStop
  }
}