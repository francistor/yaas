package yaas.server

import akka.actor.{ActorSystem, Actor, ActorRef, Props, Cancellable}
import akka.actor.ActorLogging
import akka.event.{Logging, LoggingReceive}
import scala.concurrent.duration._
import scala.concurrent.Promise

import yaas.coding.diameter.{DiameterMessage}
import yaas.coding.radius.{RadiusPacket}
import yaas.server.Router._
import yaas.server.RadiusActorMessages._

// Diameter Exceptions
class DiameterResponseException(msg: String) extends Exception(msg)
class NoPeerAvailableResponseException(msg: String) extends DiameterResponseException(msg)
class DiameterTimeoutException(msg: String) extends DiameterResponseException(msg)

// Radius Exceptions
class RadiusResponseException(msg:String) extends Exception(msg)
class RadiusTimeoutException(msg: String) extends RadiusResponseException(msg)

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
  
  //type DiameterReplyCallback = (Option[DiameterMessage]) => Unit
  //type RadiusReplyCallback = (Option[RadiusPacket]) => Unit
  
  val radiusE2EIdGenerator = new yaas.util.IDGenerator
  
  ////////////////////////////////
  // Diameter 
  ////////////////////////////////
  
  def sendDiameterReply(diameterMessage: DiameterMessage, originActor: ActorRef) = {
    originActor ! diameterMessage
    
    log.debug("Sent diameter response message\n {}\n", diameterMessage.toString())
  }
  
  def sendDiameterRequest(diameterMessage: DiameterMessage, timeoutMillis: Int) = {
    
    val promise = Promise[DiameterMessage]
    
    // Publish in cache
    diameterCacheIn(diameterMessage.endToEndId, timeoutMillis, promise)
    
    // Send request using router
    context.parent ! diameterMessage
    
    log.debug("Sent diameter request message\n {}\n", diameterMessage.toString())
    
    promise.future
  }
  
  def handleDiameterMessage(diameterMessage: DiameterMessage, originActor: ActorRef) = {
    log.warning("Default diameter handleMessage does nothing")
  }
  
  ////////////////////////////////
  // Diameter Cache
  ////////////////////////////////
  
  case class DiameterRequestEntry(promise: Promise[DiameterMessage], timer: Cancellable)
  val diameterRequestCache = scala.collection.mutable.Map[Long, DiameterRequestEntry]()
  
  def diameterCacheIn(e2eId: Long, timeoutMillis: Int, promise: Promise[DiameterMessage]) = {
    // Schedule timer
    val timer = context.system.scheduler.scheduleOnce(timeoutMillis milliseconds, self, MessageHandler.CancelDiameterRequest(e2eId))
 
    // Add to map
    diameterRequestCache.put(e2eId, DiameterRequestEntry(promise, timer))
    
    log.debug("Diameter Request -> Added to request cache: {} {}", e2eId, diameterRequestCache(e2eId))
  }
  
  def diameterCacheOut(e2eId: Long, messageOrError: Either[DiameterMessage, Exception]) = {
    // Remove cache entry
    diameterRequestCache.remove(e2eId) match {
      case Some(requestEntry) =>
        requestEntry.timer.cancel()
        messageOrError match {
          case Left(diameterMessage) =>
            log.debug("Diameter Reply -> removed entry from request cache: {}", e2eId)
            requestEntry.promise.success(diameterMessage)
            
          case Right(e) =>
            log.debug("Timeout/Error -> removed entry from request cache: {}", e2eId)
            requestEntry.promise.failure(e)
        }
      
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
  
  def sendRadiusGroupRequest(serverGroupName: String, radiusPacket: RadiusPacket, timeoutMillis: Int) = {
    
    val promise = Promise[RadiusPacket]
    
    // Publish in cache
    radiusCacheIn(radiusPacket.authenticator, timeoutMillis, promise)
    
    // Send request using router
    context.parent ! RadiusGroupClientRequest(radiusPacket, serverGroupName, radiusPacket.authenticator)
    
    log.debug("Sent radius request message\n {}\n", radiusPacket.toString())
    
    promise.future
  }
  
  ////////////////////////////////
  // Radius Cache
  ////////////////////////////////
  case class RadiusRequestEntry(promise: Promise[RadiusPacket], timer: Cancellable)
  val radiusRequestCache = scala.collection.mutable.Map[Array[Byte], RadiusRequestEntry]()
  
  def radiusCacheIn(authenticator: Array[Byte], timeoutMillis: Int, promise: Promise[RadiusPacket]) = {
    // Schedule timer
    val timer = context.system.scheduler.scheduleOnce(timeoutMillis milliseconds, self, MessageHandler.CancelRadiusRequest(authenticator))
    
    // Add to map
    radiusRequestCache.put(authenticator, RadiusRequestEntry(promise, timer))
    
    log.debug("Radius Request -> Added to request cache: {} {}", authenticator.map(Integer.toString(_, 16)).mkString(","), radiusRequestCache(authenticator))
  }
  
  def radiusCacheOut(authenticator: Array[Byte], radiusPacketOrError: Either[RadiusPacket, Exception]) = {
    radiusRequestCache.remove(authenticator) match {
      case Some(requestEntry) =>
        requestEntry.timer.cancel()
        radiusPacketOrError match {
          case Left(radiusPacket) =>
            log.debug("Reply -> removed entry from request cache: {}", authenticator.map(Integer.toString(_, 16)).mkString(","))
            requestEntry.promise.success(radiusPacket)
            
          case Right(e) =>
            log.debug("Timeout -> removed entry from request cache: {}", authenticator.mkString(","))
            requestEntry.promise.failure(e)
        }
        
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
      diameterCacheOut(e2eId, Right(new DiameterTimeoutException("Timeout")))
    
    case RoutedDiameterMessage(diameterMessage, originActor) =>
      log.debug("Received Diameter Request message\n {}\n", diameterMessage.toString())
      handleDiameterMessage(diameterMessage, originActor)
      
    case diameterMessage: DiameterMessage =>
      log.debug("Received Diameter Response message\n {}\n", diameterMessage.toString())
      diameterCacheOut(diameterMessage.endToEndId, Left(diameterMessage))
      
    // Radius
    case CancelRadiusRequest(authenticator) =>
      radiusCacheOut(authenticator, Right(new RadiusTimeoutException("Timeout")))
        
    case RadiusServerRequest(radiusPacket, originActor, origin) =>
      log.debug("Received Radius Request message\n {}\n", radiusPacket.toString())
      handleRadiusMessage(radiusPacket, originActor, origin)
      
    case RadiusClientResponse(radiusPacket: RadiusPacket, authenticator: Array[Byte]) =>
      log.debug("Received Radius Response message\n {}\n", radiusPacket.toString())
      radiusCacheOut(authenticator, Left(radiusPacket))
	}
}