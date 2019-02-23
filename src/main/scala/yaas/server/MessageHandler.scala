package yaas.server

import akka.actor.{Actor, ActorRef, Props, Cancellable}
import akka.actor.ActorLogging
import akka.event.{LoggingReceive}
import scala.concurrent.duration._
import scala.concurrent.{Promise, Future}
import scala.util.{Success, Failure}
import akka.actor.actorRef2Scala
import scala.{Left, Right}
import yaas.coding.{DiameterMessage, DiameterMessageKey, RadiusPacket}
import yaas.server.Router._
import yaas.server.RadiusActorMessages._
import yaas.instrumentation.StatOps

// Diameter Exceptions
class DiameterResponseException(msg: String) extends Exception(msg)
class NoPeerAvailableResponseException(msg: String) extends DiameterResponseException(msg)
class DiameterTimeoutException(msg: String) extends DiameterResponseException(msg)

// Radius Exceptions
class RadiusResponseException(msg:String) extends Exception(msg)
class RadiusTimeoutException(msg: String) extends RadiusResponseException(msg)

// Context classes. Just to avoid passing too many opaque parameters from request to response
case class DiameterRequestContext(diameterRequest: DiameterMessage, originActor: ActorRef, requestTimestamp: Long)
case class RadiusRequestContext(requestPacket: RadiusPacket, origin: RadiusEndpoint, originActor: ActorRef, receivedTimestamp: Long)

object MessageHandler {
  // Messages
  case class DiameterRequestTimeout(e2eId: Long) 
  case class RadiusRequestTimeout(radiusId: Long)
}

/**
 * Base class for all handlers, including also methods for sending messages
 */
class MessageHandler(statsServer: ActorRef) extends Actor with ActorLogging {
  
  import MessageHandler._
  
  implicit val executionContext = context.system.dispatcher
  
  ////////////////////////////////
  // Diameter 
  ////////////////////////////////
  def sendDiameterAnswer(answerMessage: DiameterMessage) (implicit ctx: DiameterRequestContext) = {
    ctx.originActor ! answerMessage
    
    StatOps.pushDiameterHandlerServer(statsServer, ctx.diameterRequest, answerMessage, ctx.requestTimestamp)
    log.debug(">> Diameter answer sent\n {}\n", answerMessage.toString())
  }
  
  def sendDiameterRequest(requestMessage: DiameterMessage, timeoutMillis: Int) = {
    val requestTimestamp = System.currentTimeMillis
    val promise = Promise[DiameterMessage]
    
    // Publish in request map
    diameterRequestMapIn(requestMessage.endToEndId, timeoutMillis, promise)
    
    // Send request using router
    context.parent ! requestMessage
    log.debug(">> Diameter request sent\n {}\n", requestMessage.toString())
    
    // Side-effect action when future is resolved
    promise.future.andThen {
      case Success(ans) =>
        StatOps.pushDiameterHandlerClient(statsServer, requestMessage.key, ans, requestTimestamp)
      case Failure(ex) =>
        StatOps.pushDiameterHandlerClientTimeout(statsServer, requestMessage.key)
    }
  }
  
  // To be overriden in Handler Classes
  def handleDiameterMessage(ctx: DiameterRequestContext) = {
    log.warning("Default diameter handleMessage does nothing")
  }
  
  ////////////////////////////////
  // Diameter Request Map
  ////////////////////////////////
  case class DiameterRequestMapEntry(promise: Promise[DiameterMessage], timer: Cancellable)
  val diameterRequestMap = scala.collection.mutable.Map[Long, DiameterRequestMapEntry]()
  
  def diameterRequestMapIn(e2eId: Long, timeoutMillis: Int, promise: Promise[DiameterMessage]) = {
    // Schedule timer
    val timer = context.system.scheduler.scheduleOnce(timeoutMillis milliseconds, self, DiameterRequestTimeout(e2eId))
 
    // Add to map
    diameterRequestMap.put(e2eId, DiameterRequestMapEntry(promise, timer))
    
    log.debug("Diameter RequestMap In -> {}", e2eId)
  }
  
  def diameterRequestMapOut(e2eId: Long, messageOrError: Either[DiameterMessage, Exception]) = {
    // Remove Map entry
    diameterRequestMap.remove(e2eId) match {
      case Some(requestEntry) =>
        // Cancel timer
        requestEntry.timer.cancel()
        messageOrError match {
          // If response received, fulfill promise with success
          case Left(diameterAnswer) =>
            log.debug("Diameter Request Map Out <- {}", e2eId)
            requestEntry.promise.success(diameterAnswer)
            
          // If error, fullfull promise with error
          case Right(e) =>
            log.debug("Diameter Timeout <- {}", e2eId)
            requestEntry.promise.failure(e)
        }
      
      case None =>
        log.warning("Diameter Request not found for E2EId: {}. Unsolicited or stalled answer", e2eId)
    }
  }
  
  ////////////////////////////////
  // Radius
  ////////////////////////////////
  def sendRadiusResponse(responsePacket: RadiusPacket)(implicit ctx: RadiusRequestContext) = {
    ctx.originActor ! RadiusServerResponse(responsePacket, ctx.origin)
    
    StatOps.pushRadiusHandlerResponse(statsServer, ctx.origin, ctx.requestPacket.code, responsePacket.code, ctx.receivedTimestamp)
    log.debug(">> Radius response sent\n {}\n", responsePacket.toString())
  }
  
  def dropRadiusPacket(implicit ctx: RadiusRequestContext) = {
    StatOps.pushRadiusHandlerDrop(statsServer, ctx.origin, ctx.requestPacket.code)
  }
  
  def sendRadiusGroupRequest(serverGroupName: String, requestPacket: RadiusPacket, timeoutMillis: Int, retries: Int): Future[RadiusPacket] = {
    sendRadiusGroupRequestInternal(serverGroupName, requestPacket, timeoutMillis, retries, 0)
  }
  
  def sendRadiusGroupRequestInternal(serverGroupName: String, requestPacket: RadiusPacket, timeoutMillis: Int, retries: Int, retryNum: Int): Future[RadiusPacket] = {
    val promise = Promise[RadiusPacket]
    
    val sentTimestamp = System.currentTimeMillis
    
    // Publish in request Map
    val radiusId = yaas.util.IDGenerator.nextRadiusId
    radiusRequestMapIn(radiusId, timeoutMillis, promise)
    
    // Send request using router
    context.parent ! RadiusGroupClientRequest(requestPacket, serverGroupName, radiusId, retryNum)
    
    log.debug(">> Radius request sent\n {}\n", requestPacket.toString())
    
    promise.future.recoverWith {
      case e if(retries > 0) =>
        StatOps.pushRadiusHandlerRetransmission(statsServer, serverGroupName, requestPacket.code)
        sendRadiusGroupRequestInternal(serverGroupName, requestPacket, timeoutMillis, retries - 1, retryNum + 1)
    }.andThen {
      case Failure(e) =>
        StatOps.pushRadiusHandlerTimeout(statsServer, serverGroupName, requestPacket.code)
      case Success(responsePacket) =>
        StatOps.pushRadiusHandlerRequest(statsServer, serverGroupName, requestPacket.code, responsePacket.code, sentTimestamp) 
    }
  }
  
  // To be overriden in Handler Classes
  def handleRadiusMessage(radiusRequestContext: RadiusRequestContext) = {
    log.warning("Default radius handleMessage does nothing")
  }
  
  ////////////////////////////////
  // Radius Request Map
  ////////////////////////////////
  case class RadiusRequestMapEntry(promise: Promise[RadiusPacket], timer: Cancellable, sentTimestamp: Long)
  val radiusRequestMap = scala.collection.mutable.Map[Long, RadiusRequestMapEntry]()
  
  def radiusRequestMapIn(radiusId: Long, timeoutMillis: Int, promise: Promise[RadiusPacket]) = {
    // Schedule timer
    val timer = context.system.scheduler.scheduleOnce(timeoutMillis milliseconds, self, MessageHandler.RadiusRequestTimeout(radiusId))
    
    // Add to map
    radiusRequestMap.put(radiusId, RadiusRequestMapEntry(promise, timer, System.currentTimeMillis))
    
    log.debug("Radius Request Map In -> {}", radiusId)
  }
  
  def radiusRequestMapOut(radiusId: Long, radiusPacketOrError: Either[RadiusPacket, Exception]) = {
    radiusRequestMap.remove(radiusId) match {
      case Some(requestEntry) =>
        requestEntry.timer.cancel()
        radiusPacketOrError match {
          case Left(radiusPacket) =>
            log.debug("Radius Request Map Out <- {}", radiusId)
            requestEntry.promise.success(radiusPacket)
            
          case Right(e) =>
            log.debug("Radius Request Map Timeout <- {}", radiusId)
            requestEntry.promise.failure(e)
        }
        
      case None =>
        log.warning("Radius Request (Not Found) {}. Unsolicited or stalled response", radiusId)
    }
  }
  
  ////////////////////////////////
  // Actor receive method
  ////////////////////////////////
  def receive  = LoggingReceive {
    
    // Diameter
    case DiameterRequestTimeout(e2eId) => 
      diameterRequestMapOut(e2eId, Right(new DiameterTimeoutException("Timeout")))
    
    case RoutedDiameterMessage(diameterRequest, originActor) =>
      log.debug("<< Diameter request received\n {}\n", diameterRequest.toString())
      handleDiameterMessage(DiameterRequestContext(diameterRequest, originActor, System.currentTimeMillis))
      
    case diameterAnswer: DiameterMessage =>
      log.debug("<< Diameter anwer received\n {}\n", diameterAnswer.toString())
      diameterRequestMapOut(diameterAnswer.endToEndId, Left(diameterAnswer))
      
    // Radius
    case RadiusRequestTimeout(radiusId) =>
      log.debug("<< Radius timeout\n {}\n", radiusId)
      radiusRequestMapOut(radiusId, Right(new RadiusTimeoutException("Timeout")))
        
    case RadiusServerRequest(requestPacket, originActor, origin) =>
      log.debug("<< Radius request received\n {}\n", requestPacket.toString())
      handleRadiusMessage(RadiusRequestContext(requestPacket, origin, originActor, System.currentTimeMillis))
      
    case RadiusClientResponse(radiusResponse: RadiusPacket, radiusId: Long) =>
      log.debug("<< Radius response received\n {}\n", radiusResponse.toString())
      radiusRequestMapOut(radiusId, Left(radiusResponse))
	}
}