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
  case class CancelRadiusRequest(authenticator: Array[Byte])
}

/**
 * Base class for all handlers, including also methods for sending messages
 */
class MessageHandler extends Actor with ActorLogging {
  
  import MessageHandler._
  
  implicit val executionContext = context.system.dispatcher
  
  type DiameterReplyCallback = (Option[DiameterMessage]) => Unit
  type RadiusReplyCallback = (Option[RadiusPacket]) => Unit
  
  val radiusE2EIdGenerator = new yaas.util.IDGenerator
  
  ////////////////////////////////
  // Diameter 
  ////////////////////////////////
  
  def sendDiameterReply(diameterMessage: DiameterMessage, originActor: ActorRef) = {
    originActor ! diameterMessage
    
    log.debug("Sent diameter response message\n {}\n", diameterMessage.toString())
  }
  
  def sendDiameterRequest(diameterMessage: DiameterMessage, timeoutMillis: Int, callback: DiameterReplyCallback) = {
    // Publish in cache
    diameterCacheIn(diameterMessage.endToEndId, timeoutMillis, callback)
    // Send request using router
    context.parent ! diameterMessage
    
    log.debug("Sent diameter request message\n {}\n", diameterMessage.toString())
  }
  
  def handleDiameterMessage(diameterMessage: DiameterMessage, originActor: ActorRef) = {
    log.warning("Default diameter handleMessage does nothing")
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
    
    log.debug("Diameter Request -> Added to request cache: {} {}", e2eId, diameterRequestCache(e2eId))
  }
  
  def diameterCacheOut(e2eId: Long, diameterMessageOption: Option[DiameterMessage]) = {
    // Remove cache entry
    diameterRequestCache.remove(e2eId) match {
      case Some(requestEntry) =>
        if(diameterMessageOption.isDefined) log.debug("Diameter Reply -> removed entry from request cache: {}", e2eId) else log.debug("Timeout -> removed entry from request cache: {}", e2eId)
        requestEntry.timer.cancel()
        requestEntry.callback(diameterMessageOption)
      
      case None =>
        log.warning("Diameter Reply -> no entry found in cache. Unsolicited or stalled response")
    }
  }
  
  ////////////////////////////////
  // Radius
  ////////////////////////////////
  def sendRadiusReply(radiusPacket: RadiusPacket, originActor: ActorRef, origin: RadiusEndpoint) = {
    originActor ! RadiusServerResponse(radiusPacket, origin)
    
    log.debug("Sent radius response message\n {}\n", radiusPacket.toString())
  }
  
  def handleRadiusMessage(radiusPacket: RadiusPacket, originActor: ActorRef, origin: RadiusEndpoint) = {
    log.warning("Default radius handleMessage does nothing")
  }
  
  def sendRadiusGroupRequest(serverGroupName: String, radiusPacket: RadiusPacket, timeoutMillis: Int, callback: RadiusReplyCallback) = {
    
    // Publish in cache
    radiusCacheIn(radiusPacket.authenticator, timeoutMillis, callback)
    
    // Send request using router
    context.parent ! RadiusGroupClientRequest(radiusPacket, serverGroupName, radiusPacket.authenticator)
    
    log.debug("Sent radius request message\n {}\n", radiusPacket.toString())
  }
  
  ////////////////////////////////
  // Radius Cache
  ////////////////////////////////
  case class RadiusRequestEntry(callback: RadiusReplyCallback, timer: Cancellable)
  val radiusRequestCache = scala.collection.mutable.Map[Array[Byte], RadiusRequestEntry]()
  
  def radiusCacheIn(authenticator: Array[Byte], timeoutMillis: Int, callback: RadiusReplyCallback) = {
    // Schedule timer
    val timer = context.system.scheduler.scheduleOnce(timeoutMillis milliseconds, self, MessageHandler.CancelRadiusRequest(authenticator))
    
    // Add to map
    radiusRequestCache.put(authenticator, RadiusRequestEntry(callback, timer))
    
    log.debug("Radius Request -> Added to request cache: {} {}", authenticator.map(Integer.toString(_, 16)).mkString(","), radiusRequestCache(authenticator))
  }
  
  def radiusCacheOut(authenticator: Array[Byte], radiusPacketOption: Option[RadiusPacket]) = {
    radiusRequestCache.remove(authenticator) match {
      case Some(requestEntry) =>
        if(radiusPacketOption.isDefined) log.debug("Reply -> removed entry from request cache: {}", authenticator.map(Integer.toString(_, 16)).mkString(",")) else log.debug("Timeout -> removed entry from request cache: {}", authenticator.mkString(","))
        requestEntry.timer.cancel()
        requestEntry.callback(radiusPacketOption)
        
      case None =>
        log.warning("Radius Reply -> no entry found in cache. Unsolicited or stalled response")
    }
  }
  
  ////////////////////////////////
  // Actor receive method
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
    case CancelRadiusRequest(authenticator) =>
        radiusCacheOut(authenticator, None)
        
    case RadiusServerRequest(radiusPacket, originActor, origin) =>
      log.debug("Received Radius Request message\n {}\n", radiusPacket.toString())
      handleRadiusMessage(radiusPacket, originActor, origin)
      
    case RadiusClientResponse(radiusPacket: RadiusPacket, authenticator: Array[Byte]) =>
      log.debug("Received Radius Response message\n {}\n", radiusPacket.toString())
      radiusCacheOut(authenticator, Some(radiusPacket))
	}
}