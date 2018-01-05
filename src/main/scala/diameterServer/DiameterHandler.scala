package diameterServer

import akka.actor.{ActorSystem, Actor, ActorRef, Props, Cancellable}
import akka.actor.ActorLogging
import akka.event.{Logging, LoggingReceive}
import scala.concurrent.duration._

import diameterServer.coding.{DiameterMessage}
import diameterServer.DiameterRouter._


object DiameterMessageHandler {
  // Messages
  case class CancelRequest(e2eId: Long) 
}

class DiameterMessageHandler extends Actor with ActorLogging {
  
  import DiameterMessageHandler._
  
  implicit val executionContext = context.system.dispatcher
  
  type ReplyCallback = (Option[DiameterMessage]) => Unit
  
  def sendReply(diameterMessage: DiameterMessage, originActor: ActorRef) = {
    originActor ! diameterMessage
    
    log.debug("Sent response message\n {}\n", diameterMessage.toString())
  }
  
  def sendRequest(diameterMessage: DiameterMessage, timeoutMillis: Int, callback: ReplyCallback) = {
    // Publish in cache
    cacheIn(diameterMessage.endToEndId, timeoutMillis, callback)
    // Send request
    context.parent ! diameterMessage
    
    log.debug("Sent request message\n {}\n", diameterMessage.toString())
  }
  
  def handleMessage(diameterMessage: DiameterMessage, originActor: ActorRef) = {
    log.warning("Default handleMessage does nothing")
  }
  
  def receive  = LoggingReceive {
    case CancelRequest(e2eId) => 
      cacheOut(e2eId, None)
    
    case RoutedDiameterMessage(diameterMessage, originActor) =>
      log.debug("Received request message\n {}\n", diameterMessage.toString())
      handleMessage(diameterMessage, originActor)
      
    case diameterMessage: DiameterMessage =>
      log.debug("Received response message\n {}\n", diameterMessage.toString())
      cacheOut(diameterMessage.endToEndId, Some(diameterMessage))
      
		case any: Any => Nil
	}
  
  ///////////////////
  // Cache functions
  ///////////////////
  case class RequestEntry(callback: ReplyCallback, timer: Cancellable)
  val requestCache = scala.collection.mutable.Map[Long, RequestEntry]()
  
  def cacheIn(e2eId: Long, timeoutMillis: Int, callback: ReplyCallback) = {
    // Schedule timer
    val timer = context.system.scheduler.scheduleOnce(timeoutMillis milliseconds, self, DiameterMessageHandler.CancelRequest(e2eId))
 
    // Add to map
    requestCache.put(e2eId, RequestEntry(callback, timer))
    
    log.debug("Request -> Added to request cache: {} {}", e2eId, requestCache(e2eId))
  }
  
  def cacheOut(e2eId: Long, diameterMessageOption: Option[DiameterMessage]) = {
    // Remove cache entry
    requestCache.remove(e2eId) match {
      case Some(requestEntry) =>
        if(diameterMessageOption.isDefined) log.debug("Reply -> removed entry from request cache: {}", e2eId) else log.debug("Timeout -> removed entry from request cache: {}", e2eId)
        requestEntry.timer.cancel()
        requestEntry.callback(diameterMessageOption)
      
      case None =>
        log.warning("Reply -> no entry found in cache")
    }
  }
}