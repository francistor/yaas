package diameterServer

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import akka.actor.ActorLogging
import akka.event.{Logging, LoggingReceive}
import scala.concurrent.duration._

import diameterServer.coding.{DiameterMessage}
import diameterServer.DiameterRouter._


object DiameterMessageHandler {
  case class CancelRequest(e2eId: Long) 
}

class DiameterMessageHandler extends Actor with ActorLogging {
  
  import DiameterMessageHandler._
  
  implicit val executionContext = context.system.dispatcher
  
  type ReplyCallback = (Option[DiameterMessage]) => Unit
  
  def sendReply(message: DiameterMessage, originActor: ActorRef) = {
    originActor ! message
  }
  
  def sendRequest(message: DiameterMessage, timeoutMillis: Int, callback: ReplyCallback) = {
    // Publish in cache
    cacheIn(message.endToEndId, timeoutMillis, callback)
    // Send request
    context.parent ! message
  }
  
  def handleMessage(diameterMessage: DiameterMessage, originActor: ActorRef) = {
    log.warning("Default handleMessage does nothing")
  }
  
  def receive  = LoggingReceive {
    case CancelRequest(e2eId) => 
      cacheOut(e2eId, None)
    
    case RoutedDiameterMessage(diameterMessage, originActor) =>
      handleMessage(diameterMessage, originActor)
      
    case message: DiameterMessage =>
      cacheOut(message.endToEndId, Some(message))
      
		case any: Any => Nil
	}
  
  ///////////////////
  // Cache functions
  ///////////////////
  val requestCache = scala.collection.mutable.Map[Long, ReplyCallback]()
  
  def cacheIn(e2eId: Long, timeoutMillis: Int, callback: ReplyCallback) = {
    // Add to map
    requestCache.put(e2eId, callback)
    
    // Schedule timer
    context.system.scheduler.scheduleOnce(timeoutMillis milliseconds, self, DiameterMessageHandler.CancelRequest(e2eId))
 
    log.debug("Added to request cache: {}", e2eId)
  }
  
  def cacheOut(e2eId: Long, message: Option[DiameterMessage]) = {
    if(requestCache.get(e2eId).isDefined){
      // Invoke callback with replied message (may be None if timeout)
      requestCache(e2eId)(message)
      // Remove cache entry
      requestCache.remove(e2eId)
      log.debug("Removed from request cache: {}", e2eId)
    } else log.warning("Unsolicited or cancelled reply with e2eId: {}", e2eId)
  }
  
}