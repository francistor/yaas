package yaas.server

import akka.actor.{ActorSystem, Actor, ActorRef, Props, Cancellable}
import akka.actor.ActorLogging
import akka.event.{Logging, LoggingReceive}
import scala.concurrent.duration._

import yaas.coding.diameter.{DiameterMessage}
import yaas.coding.radius.{RadiusPacket}
import yaas.server.Router._
import yaas.server.RadiusActorMessages._


object MessageHandler {
  // Messages
  case class CancelDiameterRequest(e2eId: Long) 
}

class MessageHandler extends Actor with ActorLogging {
  
  import MessageHandler._
  
  implicit val executionContext = context.system.dispatcher
  
  type DiameterReplyCallback = (Option[DiameterMessage]) => Unit
  type RadiusReplyCallback = (Option[RadiusPacket]) => Unit
  
  ////////////////////////////////
  // Diameter 
  ////////////////////////////////
  
  def sendDiameterReply(diameterMessage: DiameterMessage, originActor: ActorRef) = {
    originActor ! diameterMessage
    
    log.debug("Sent response message\n {}\n", diameterMessage.toString())
  }
  
  def sendDiameterRequest(diameterMessage: DiameterMessage, timeoutMillis: Int, callback: DiameterReplyCallback) = {
    // Publish in cache
    diameterCacheIn(diameterMessage.endToEndId, timeoutMillis, callback)
    // Send request using router
    context.parent ! diameterMessage
    
    log.debug("Sent request message\n {}\n", diameterMessage.toString())
  }
  
  def handleDiameterMessage(diameterMessage: DiameterMessage, originActor: ActorRef) = {
    log.warning("Default handleMessage does nothing")
  }
  
  ////////////////////////////////
  // Diameter Cache
  ////////////////////////////////
  
  case class DiameterRequestEntry(callback: DiameterReplyCallback, timer: Cancellable)
  val diameterRequestCache = scala.collection.mutable.Map[Long, DiameterRequestEntry]()
  
  def diameterCacheIn(e2eId: Long, timeoutMillis: Int, callback: DiameterReplyCallback) = {
    // Schedule timer
    val timer = context.system.scheduler.scheduleOnce(timeoutMillis milliseconds, self, MessageHandler.CancelDiameterRequest(e2eId))
 
    // Add to map
    diameterRequestCache.put(e2eId, DiameterRequestEntry(callback, timer))
    
    log.debug("Request -> Added to request cache: {} {}", e2eId, diameterRequestCache(e2eId))
  }
  
  def diameterCacheOut(e2eId: Long, diameterMessageOption: Option[DiameterMessage]) = {
    // Remove cache entry
    diameterRequestCache.remove(e2eId) match {
      case Some(requestEntry) =>
        if(diameterMessageOption.isDefined) log.debug("Reply -> removed entry from request cache: {}", e2eId) else log.debug("Timeout -> removed entry from request cache: {}", e2eId)
        requestEntry.timer.cancel()
        requestEntry.callback(diameterMessageOption)
      
      case None =>
        log.warning("Reply -> no entry found in cache")
    }
  }
  
  ////////////////////////////////
  // Radius
  ////////////////////////////////
  def sendRadiusReply(radiusPacket: RadiusPacket, originActor: ActorRef, origin: RadiusOrigin) = {
    originActor ! RadiusServerResponse(radiusPacket, origin)
    
    log.debug("Sent response message\n {}\n", radiusPacket.toString())
  }
  
  def handleRadiusMessage(radiusPacket: RadiusPacket, originActor: ActorRef, origin: RadiusOrigin) = {
    log.warning("Default handleMessage does nothing")
  }
  
  ////////////////////////////////
  // Radius Cache
  ////////////////////////////////
  
  def receive  = LoggingReceive {
    
    // Diameter
    case CancelDiameterRequest(e2eId) => 
      diameterCacheOut(e2eId, None)
    
    case RoutedDiameterMessage(diameterMessage, originActor) =>
      log.debug("Received Diameter Request message\n {}\n", diameterMessage.toString())
      handleDiameterMessage(diameterMessage, originActor)
      
    case diameterMessage: DiameterMessage =>
      log.debug("Received Diameter Response message\n {}\n", diameterMessage.toString())
      diameterCacheOut(diameterMessage.endToEndId, Some(diameterMessage))
      
    // Radius
    case RadiusServerRequest(radiusPacket, originActor, origin) =>
      log.debug("Received Radius Request message\n {}\n", radiusPacket.toString())
      handleRadiusMessage(radiusPacket, originActor, origin)
	}
}