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
  case class CancelRadiusRequest(radiusId: Long)
}

/**
 * Base class for all handlers, including also methods for sending messages
 */
class MessageHandler extends Actor with ActorLogging {
  
  import MessageHandler._
  
  implicit val executionContext = context.system.dispatcher
  
  val e2EIdGenerator = new yaas.util.IDGenerator
  
  ////////////////////////////////
  // Diameter 
  ////////////////////////////////
  
  def sendDiameterAnswer(diameterMessage: DiameterMessage, originActor: ActorRef) = {
    originActor ! diameterMessage
    
    log.debug(">> Diameter answer sent\n {}\n", diameterMessage.toString())
  }
  
  def sendDiameterRequest(diameterMessage: DiameterMessage, timeoutMillis: Int) = {
    
    val promise = Promise[DiameterMessage]
    
    // Publish in cache
    diameterCacheIn(diameterMessage.endToEndId, timeoutMillis, promise)
    
    // Send request using router
    context.parent ! diameterMessage
    
    log.debug(">> Diameter request sent\n {}\n", diameterMessage.toString())
    
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
    
    log.debug("Diameter Cache In -> {}", e2eId)
  }
  
  def diameterCacheOut(e2eId: Long, messageOrError: Either[DiameterMessage, Exception]) = {
    // Remove cache entry
    diameterRequestCache.remove(e2eId) match {
      case Some(requestEntry) =>
        requestEntry.timer.cancel()
        messageOrError match {
          case Left(diameterMessage) =>
            log.debug("Diameter Cache Out <- {}", e2eId)
            requestEntry.promise.success(diameterMessage)
            
          case Right(e) =>
            log.debug("Diameter Cache Timeout <- {}", e2eId)
            requestEntry.promise.failure(e)
        }
      
      case None =>
        log.warning("Diameter Cache (Not found) {}. Unsolicited or stalled answer", e2eId)
    }
  }
  
  ////////////////////////////////
  // Radius
  ////////////////////////////////
  def sendRadiusResponse(radiusPacket: RadiusPacket, originActor: ActorRef, origin: RadiusEndpoint) = {
    originActor ! RadiusServerResponse(radiusPacket, origin)
    
    log.debug(">> Radius response sent\n {}\n", radiusPacket.toString())
  }
  
  def handleRadiusMessage(radiusPacket: RadiusPacket, originActor: ActorRef, origin: RadiusEndpoint) = {
    log.warning("Default radius handleMessage does nothing")
  }
  
  def sendRadiusGroupRequest(serverGroupName: String, radiusPacket: RadiusPacket, timeoutMillis: Int) = {
    
    val promise = Promise[RadiusPacket]
    
    // Publish in cache
    val radiusId = e2EIdGenerator.nextRadiusId
    radiusCacheIn(radiusId, timeoutMillis, promise)
    
    // Send request using router
    context.parent ! RadiusGroupClientRequest(radiusPacket, serverGroupName, radiusId)
    
    log.debug(">> Radius request sent\n {}\n", radiusPacket.toString())
    
    promise.future
  }
  
  ////////////////////////////////
  // Radius Cache
  ////////////////////////////////
  case class RadiusRequestEntry(promise: Promise[RadiusPacket], timer: Cancellable)
  val radiusRequestCache = scala.collection.mutable.Map[Long, RadiusRequestEntry]()
  
  def radiusCacheIn(radiusId: Long, timeoutMillis: Int, promise: Promise[RadiusPacket]) = {
    // Schedule timer
    val timer = context.system.scheduler.scheduleOnce(timeoutMillis milliseconds, self, MessageHandler.CancelRadiusRequest(radiusId))
    
    // Add to map
    radiusRequestCache.put(radiusId, RadiusRequestEntry(promise, timer))
    
    log.debug("Radius Cache In -> {}", radiusId)
  }
  
  def radiusCacheOut(radiusId: Long, radiusPacketOrError: Either[RadiusPacket, Exception]) = {
    radiusRequestCache.remove(radiusId) match {
      case Some(requestEntry) =>
        requestEntry.timer.cancel()
        radiusPacketOrError match {
          case Left(radiusPacket) =>
            log.debug("Radius Cache Out <- {}", radiusId)
            requestEntry.promise.success(radiusPacket)
            
          case Right(e) =>
            log.debug("Radius Cache Timeout <- {}", radiusId)
            requestEntry.promise.failure(e)
        }
        
      case None =>
        log.warning("Radius Cache (Not Found) {}. Unsolicited or stalled response", radiusId)
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
      log.debug("<< Diameter request received\n {}\n", diameterMessage.toString())
      handleDiameterMessage(diameterMessage, originActor)
      
    case diameterMessage: DiameterMessage =>
      log.debug("<< Diameter anwer received\n {}\n", diameterMessage.toString())
      diameterCacheOut(diameterMessage.endToEndId, Left(diameterMessage))
      
    // Radius
    case CancelRadiusRequest(radiusId) =>
      radiusCacheOut(radiusId, Right(new RadiusTimeoutException("Timeout")))
        
    case RadiusServerRequest(radiusPacket, originActor, origin) =>
      log.debug("<< Radius request received\n {}\n", radiusPacket.toString())
      handleRadiusMessage(radiusPacket, originActor, origin)
      
    case RadiusClientResponse(radiusPacket: RadiusPacket, radiusId: Long) =>
      log.debug("<< Radius response received\n {}\n", radiusPacket.toString())
      radiusCacheOut(radiusId, Left(radiusPacket))
	}
}