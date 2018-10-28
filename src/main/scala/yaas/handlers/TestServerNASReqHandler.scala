package yaas.handlers

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._
import yaas.coding._
import yaas.util.IDGenerator
import yaas.coding.DiameterConversions._
import yaas.config.DiameterConfigManager
import yaas.dictionary.DiameterDictionary

import scala.util.{Success, Failure}
import yaas.server.MessageHandler

/**
 * This application is proxied. A new request is generated for the upstream server
 */
class TestServerNASREQHandler(statsServer: ActorRef) extends MessageHandler(statsServer) {
  
  log.info("Instantiated NASREQHandler")
  
  implicit val idGen = new IDGenerator
  
  override def handleDiameterMessage(ctx: DiameterRequestContext) = {
    
    ctx.diameterRequest.command match {
      case "AA" => handleAAR(ctx)
    }
  }

  /*
   * Sends a message to the super-server with NAS-IP-Adddress and passwords as received
   * The answer will contain a result code and all the Class attributes send from the super-server
   */
  def handleAAR(implicit ctx: DiameterRequestContext) = {
    
    val request = ctx.diameterRequest
    val password = (request >> "User-Password").get.toString
    val proxyRequest = DiameterMessage.request("NASREQ", "AA") 
    proxyRequest << ("Destination-Realm" -> "yaassuperserver") << (request >> "NAS-IP-Address") << ("User-Password" -> password)
    
    sendDiameterRequest(proxyRequest, 1000).onComplete{
      case Success(proxyAnswer) =>
        log.info("Received proxy answer {}", proxyAnswer)
        val answer = DiameterMessage.answer(ctx.diameterRequest)
        answer << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS) << (answer >>+ "Class")
        sendDiameterAnswer(answer)
        
      case Failure(e) =>
        log.error("Proxy timeout")
    }
  }
}