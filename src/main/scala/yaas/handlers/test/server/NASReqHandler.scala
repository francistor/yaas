package yaas.handlers.test.server

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import yaas.server._
import yaas.coding._
import yaas.coding.DiameterConversions._
import yaas.config.DiameterConfigManager
import scala.util.{Success, Failure}
import yaas.server.MessageHandler

/**
 * This application is proxied. A new request is generated for the upstream server
 */
class NASReqHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {
  
  log.info("Instantiated NASREQHandler")
  
  val writer = new yaas.cdr.CDRFileWriter("cdr", "nasreq_%d{yyyyMMdd-HHmm}.txt")
  // Framed-IPv6-Pool not sent. Used to check behaviur in case of missing attributes
  //val format = DiameterSerialFormat.newCSVFormat(List("Origin-Host", "Session-Id", "Tunneling.Tunnel-Type", "Tunneling.Tunnel-Client-Endpoint", "Framed-IPv6-Pool"))
  val format = DiameterSerialFormat.newJSONFormat()
  
  override def handleDiameterMessage(ctx: DiameterRequestContext) = {
    
    ctx.diameterRequest.command match {
      case "AA" => handleAAR(ctx)
      case "AC" => handleACR(ctx)
    }
  }

  /*
   * Proxies a message to the super-server with NAS-IP-Address as received
   * The answer will contain a result code, the echoed NAS-IP-Address and all the Class attributes sent from the super-server
   * 
   * In this case a new request is created for the upstream server (instead of the message being routed)
   */
  def handleAAR(implicit ctx: DiameterRequestContext) = {
    
    val request = ctx.diameterRequest
    
    val proxyRequest = request.copy
    // Remove routing info
    proxyRequest.removeAll("Destination-Host")
    proxyRequest.removeAll("Destination-Realm")
    proxyRequest.removeAll("Origin-Host")
    proxyRequest.removeAll("Origin-Realm")
    // Add routing info
    proxyRequest << 
      ("Destination-Realm" -> "yaassuperserver") <<
      ("Origin-Host" -> DiameterConfigManager.diameterConfig.diameterHost) <<
      ("Origin-Realm" -> DiameterConfigManager.diameterConfig.diameterRealm)
    
    sendDiameterRequest(proxyRequest, 1000).onComplete {
      case Success(proxyAnswer) =>
        
        // Build the answer
        val answer = DiameterMessage.answer(ctx.diameterRequest)
        answer << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS) << (proxyAnswer >>+ "Class")
        sendDiameterAnswer(answer)
        
      case Failure(e) =>
        log.error("Proxy timeout")
    }
  }
  
  /*
   * Generates a new message to superserver
   */
  def handleACR(implicit ctx: DiameterRequestContext) = {
    
    val request = ctx.diameterRequest
    
    writer.writeCDR(request.getCDR(format))
    
    val proxyRequest = request.proxyRequest << 
    ("Destination-Realm" -> "yaassuperserver")
    
    sendDiameterRequest(proxyRequest, 1000).onComplete {
      case Success(proxyAnswer) =>
        
        // Build the answer
        val answer = DiameterMessage.answer(ctx.diameterRequest)
        answer << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
        sendDiameterAnswer(answer)
        
      case Failure(e) =>
        log.error("Timeout")
    }
  }
}