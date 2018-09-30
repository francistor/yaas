package yaas.server

import akka.actor.{Actor, ActorRef, Props, Cancellable}
import akka.actor.ActorLogging
import akka.event.{LoggingReceive}
import scala.concurrent.duration._
import scala.concurrent.Promise
import akka.actor.actorRef2Scala
import scala.{Left, Right}
import yaas.coding.{DiameterMessage, DiameterMessageKey, RadiusPacket}
import yaas.server.Router._
import yaas.server.RadiusActorMessages._
import yaas.stats.StatOps

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
class MessageHandler(statsServer: ActorRef) extends Actor with ActorLogging {
  
  import MessageHandler._
  
  implicit val executionContext = context.system.dispatcher
  
  val e2EIdGenerator = new yaas.util.IDGenerator
  
  ////////////////////////////////
  // Diameter 
  ////////////////////////////////
  
  def sendDiameterAnswer(answerMessage: DiameterMessage, requestMessage: DiameterMessage, originActor: ActorRef, requestTimestamp: Long) = {
    originActor ! answerMessage
    
    StatOps.pushDiameterHandlerServer(statsServer, requestMessage, answerMessage, System.currentTimeMillis - requestTimestamp)
    log.debug(">> Diameter answer sent\n {}\n", answerMessage.toString())
  }
  
  def sendDiameterRequest(requestMessage: DiameterMessage, timeoutMillis: Int) = {
    
    val promise = Promise[DiameterMessage]
    
    // Publish in cache
    diameterCacheIn(requestMessage, timeoutMillis, promise)
    
    // Send request using router
    context.parent ! requestMessage
    
    log.debug(">> Diameter request sent\n {}\n", requestMessage.toString())
    
    promise.future
  }
  
  def handleDiameterMessage(requestMessage: DiameterMessage, originActor: ActorRef, receivedTimestamp: Long) = {
    log.warning("Default diameter handleMessage does nothing")
  }
  
  ////////////////////////////////
  // Diameter Cache
  ////////////////////////////////
  
  case class DiameterRequestEntry(promise: Promise[DiameterMessage], timer: Cancellable, key: DiameterMessageKey, sentTimestamp: Long)
  val diameterRequestCache = scala.collection.mutable.Map[Long, DiameterRequestEntry]()
  
  def diameterCacheIn(diameterMessage: DiameterMessage, timeoutMillis: Int, promise: Promise[DiameterMessage]) = {
    // Schedule timer
    val timer = context.system.scheduler.scheduleOnce(timeoutMillis milliseconds, self, MessageHandler.CancelDiameterRequest(diameterMessage.endToEndId))
 
    // Add to map
    diameterRequestCache.put(diameterMessage.endToEndId, DiameterRequestEntry(promise, timer, diameterMessage.key, System.currentTimeMillis))
    
    log.debug("Diameter Cache In -> {}", diameterMessage.endToEndId)
  }
  
  def diameterCacheOut(e2eId: Long, messageOrError: Either[DiameterMessage, Exception]) = {
    // Remove cache entry
    diameterRequestCache.remove(e2eId) match {
      case Some(requestEntry) =>
        requestEntry.timer.cancel()
        messageOrError match {
          case Left(diameterAnswer) =>
            log.debug("Diameter Cache Out <- {}", e2eId)
            StatOps.pushDiameterHandlerClient(statsServer, requestEntry.key, diameterAnswer, System.currentTimeMillis - requestEntry.sentTimestamp) 
            requestEntry.promise.success(diameterAnswer)
            
          case Right(e) =>
            log.debug("Diameter Cache Timeout <- {}", e2eId)
            StatOps.pushDiameterHandlerClientTimeout(statsServer, requestEntry.key)
            requestEntry.promise.failure(e)
        }
      
      case None =>
        log.warning("Diameter Cache (Not found) {}. Unsolicited or stalled answer", e2eId)
    }
  }
  
  ////////////////////////////////
  // Radius
  ////////////////////////////////
  def sendRadiusResponse(responsePacket: RadiusPacket, requestPacket: RadiusPacket, originActor: ActorRef, origin: RadiusEndpoint, requestTimestamp: Long) = {
    originActor ! RadiusServerResponse(responsePacket, origin)
    
    StatOps.pushRadiusHandlerResponse(statsServer, origin, requestPacket, responsePacket, System.currentTimeMillis - requestTimestamp)
    log.debug(">> Radius response sent\n {}\n", responsePacket.toString())
  }
  
  def handleRadiusMessage(radiusPacket: RadiusPacket, originActor: ActorRef, origin: RadiusEndpoint, requestTimestamp: Long) = {
    log.warning("Default radius handleMessage does nothing")
  }
  
  def sendRadiusGroupRequest(serverGroupName: String, radiusPacket: RadiusPacket, timeoutMillis: Int, retries: Int) = {
    
    val promise = Promise[RadiusPacket]
    
    // Publish in cache
    val radiusId = e2EIdGenerator.nextRadiusId
    radiusCacheIn(radiusId, timeoutMillis, promise, radiusPacket.code, serverGroupName, retries)
    
    // Send request using router
    context.parent ! RadiusGroupClientRequest(radiusPacket, serverGroupName, radiusId)
    
    log.debug(">> Radius request sent\n {}\n", radiusPacket.toString())
    
    promise.future
  }
  
  ////////////////////////////////
  // Radius Cache
  ////////////////////////////////
  case class RadiusRequestEntry(promise: Promise[RadiusPacket], timer: Cancellable, reqCode: Int, sentTimestamp: Long, group: String, retries: Int, timeoutMillis: Int)
  val radiusRequestCache = scala.collection.mutable.Map[Long, RadiusRequestEntry]()
  
  def radiusCacheIn(radiusId: Long, timeoutMillis: Int, promise: Promise[RadiusPacket], reqCode: Int, group: String, retries: Int) = {
    // Schedule timer
    val timer = context.system.scheduler.scheduleOnce(timeoutMillis milliseconds, self, MessageHandler.CancelRadiusRequest(radiusId))
    
    // Add to map
    radiusRequestCache.put(radiusId, RadiusRequestEntry(promise, timer, reqCode, System.currentTimeMillis, group, retries, timeoutMillis))
    
    log.debug("Radius Cache In -> {}", radiusId)
  }
  
  def radiusCacheOut(radiusId: Long, radiusPacketOrError: Either[RadiusPacket, Exception]) = {
    radiusRequestCache.remove(radiusId) match {
      case Some(requestEntry) =>
        requestEntry.timer.cancel()
        radiusPacketOrError match {
          case Left(radiusPacket) =>
            log.debug("Radius Cache Out <- {}", radiusId)
            StatOps.pushRadiusHandlerRequest(statsServer, requestEntry.group, requestEntry.reqCode, radiusPacket, System.currentTimeMillis -requestEntry.sentTimestamp) 
            requestEntry.promise.success(radiusPacket)
            
          case Right(e) =>
            log.debug("Radius Cache Timeout <- {}", radiusId)
            if(requestEntry.retries == 0){
              StatOps.pushRadiusHandlerTimeout(statsServer, requestEntry.group, requestEntry.reqCode)
              requestEntry.promise.failure(e)
            } else {
              log.debug("Radius Cache Retry <- {}", radiusId)
              StatOps.pushRadiusHandlerRetransmission(statsServer, requestEntry.group, requestEntry.reqCode)
              radiusCacheIn(radiusId, requestEntry.timeoutMillis, requestEntry.promise, requestEntry.reqCode, requestEntry.group, requestEntry.retries -1)
            }
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
      handleDiameterMessage(diameterMessage, originActor, System.currentTimeMillis)
      
    case diameterMessage: DiameterMessage =>
      log.debug("<< Diameter anwer received\n {}\n", diameterMessage.toString())
      diameterCacheOut(diameterMessage.endToEndId, Left(diameterMessage))
      
    // Radius
    case CancelRadiusRequest(radiusId) =>
      radiusCacheOut(radiusId, Right(new RadiusTimeoutException("Timeout")))
        
    case RadiusServerRequest(radiusPacket, originActor, origin) =>
      log.debug("<< Radius request received\n {}\n", radiusPacket.toString())
      handleRadiusMessage(radiusPacket, originActor, origin, System.currentTimeMillis)
      
    case RadiusClientResponse(radiusPacket: RadiusPacket, radiusId: Long) =>
      log.debug("<< Radius response received\n {}\n", radiusPacket.toString())
      radiusCacheOut(radiusId, Left(radiusPacket))
	}
}