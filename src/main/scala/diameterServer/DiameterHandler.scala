package diameterServer

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import akka.actor.ActorLogging
import akka.event.{Logging, LoggingReceive}

import diameterServer.coding.{DiameterMessage}

import diameterServer.DiameterRouter._

trait DiameterApplicationHandler {
  def handleMessage(message: DiameterMessage, originActor: ActorRef)
}

object DiameterMessageHandler {
  def props(handlerObjectName: String) = Props(new DiameterMessageHandler(handlerObjectName))
  case class CancelRequest(e2eId: Long)
  
  def sendReply(message: DiameterMessage, originActor: ActorRef) = {
    println("-------------> sending reply")
    originActor ! message
  }
}

class DiameterMessageHandler(handlerObjectName: String) extends Actor with ActorLogging {
  
  import DiameterMessageHandler._
  import scala.reflect.runtime.universe
  
  def getApplicationHandlerObject(clsName: String) = {
    
    // http://3x14159265.tumblr.com/post/57543163324/playing-with-scala-reflection
    val mirror = universe.runtimeMirror(getClass.getClassLoader)
    val module = mirror.staticModule(clsName)
    mirror.reflectModule(module).instance.asInstanceOf[DiameterApplicationHandler]   
  }
  
  val handlerInstance = getApplicationHandlerObject(handlerObjectName).asInstanceOf[DiameterApplicationHandler]
  
  ///////////////////
  // Cache functions
  ///////////////////
  val cache = scala.collection.mutable.Map[Long, (Option[DiameterMessage]) => Unit]()
  
  def push(e2eId: Long, callback: (Option[DiameterMessage]) => Unit, timeout: Int) = {
    // Schedule timer
    import scala.concurrent.duration._
    implicit val executionContext = context.system.dispatcher
    context.system.scheduler.scheduleOnce(timeout milliseconds, self, DiameterMessageHandler.CancelRequest(e2eId))
    
    // Add to map
    cache.put(e2eId, callback)
    log.debug("Added to request cache: {}", e2eId)
  }
  
  def pull(e2eId: Long, message: Option[DiameterMessage]) = {
    if(cache.get(e2eId).isDefined){
      cache(e2eId)(message)
      cache.remove(e2eId)
      log.debug("Removed from request cache: {}", e2eId)
    } else log.warning("Unsolicited or cancelled reply with e2eId: {}", e2eId)
  }
  
  def receive  = {
    case CancelRequest(e2eId) => 
      pull(e2eId, None)
    
    case RoutedDiameterMessage(diameterMessage, originActor) =>
      handlerInstance.handleMessage(diameterMessage, originActor)
      
		case any: Any => Nil
	}
  
}