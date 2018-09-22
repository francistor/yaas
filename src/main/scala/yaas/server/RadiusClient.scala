package yaas.server

import scala.collection.mutable.Map
import scala.concurrent.duration._

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill, Cancellable }
import akka.event.{ Logging, LoggingReceive }
import yaas.config.RadiusServerConfig
import yaas.server.RadiusActorMessages._
import yaas.coding.RadiusPacket
import yaas.util._

// This Actor handles the communication with upstream radius servers

object RadiusClient {
  def props(bindIPAddress: String, basePort: Int, numPorts: Int, statsServer: ActorRef) = Props(new RadiusClient(bindIPAddress, basePort, numPorts, statsServer))
 
  // Messages
  case object Clean
}

class RadiusClient(bindIPAddress: String, basePort: Int, numPorts: Int, statsServer: ActorRef) extends Actor with ActorLogging {
  
  import RadiusClient._
  
  val cleanIntervalMillis = 250
  val clientTimeoutMillis = 6000
  var cleanTimer: Option[Cancellable] = None
  
  implicit val executionContext = context.system.dispatcher
  
  case class RadiusPortId(port: Int, id: Int)
  case class RadiusOriginatorRef(originActor: ActorRef, authenticator: Array[Byte], radiusId: Long, timestamp: Long)
  
  // Stores the last identifier used for the key RadiusDestination
  val lastRadiusPortIds = Map[RadiusEndpoint, RadiusPortId]().withDefaultValue(RadiusPortId(basePort, 0))
  
  // For each radius destination, the map of identifiers to originators
  val requestCache = Map[RadiusEndpoint, Map[RadiusPortId, RadiusOriginatorRef]]().withDefaultValue(Map())
  
  // For each radius destination, store the stats
  var endpointSuccesses = Map[RadiusEndpoint, Int]().withDefaultValue(0)
  var endpointErrors = Map[RadiusEndpoint, Int]().withDefaultValue(0)
  
  // Span actors
  val socketActors = (for(i <- basePort to basePort + numPorts) yield context.actorOf(RadiusClientSocket.props(bindIPAddress, i), "RadiusClientSocket-"+ i))
  def receive = {
    
    case RadiusClientRequest(radiusPacket, destination, originActor, radiusId) =>
      // Get port and id
      val radiusPortId = nextRadiusPortId(destination)
      
      // Get packet to send
      val bytes = radiusPacket.getRequestBytes(destination.secret, radiusPortId.id)
      
      // Populate cache
      pushToRequestCache(destination, radiusPortId, RadiusOriginatorRef(originActor, radiusPacket.authenticator, radiusId, System.currentTimeMillis()))
      log.debug(s"Pushed entry to request cache: $destination -> $radiusPortId")
      
      // Send message to socket Actor
      socketActors(radiusPortId.port - basePort) ! RadiusClientSocketRequest(bytes, destination)
      
    case RadiusClientSocketResponse(bytes, origin, clientPort) =>
      val identifier = UByteString.getUnsignedByte(bytes.slice(1, 2))
      // Look in cache
      val radiusPortId = RadiusPortId(clientPort, identifier)
      log.debug(s"Looking for entry in request cache: $origin -> $radiusPortId")
      requestCache(origin).remove(radiusPortId) match {
        case Some(RadiusOriginatorRef(originActor, authenticator, radiusId, timestamp)) =>
          val radiusPacket = RadiusPacket(bytes, Some(authenticator), origin.secret)
          // Check authenticator
          val code = radiusPacket.code
          // Verify authenticator
          if((code != RadiusPacket.ACCOUNTING_RESPONSE) && !RadiusPacket.checkAuthenticator(bytes, authenticator, origin.secret))  
            log.warning("Bad authenticator from {}. Request-Authenticator: {}. Response-Authenticator: {}", 
                origin, authenticator.map(b => "%02X".format(UByteString.fromUnsignedByte(b))).mkString(","), bytes.slice(4, 20).toArray.map(b => "%02X".format(UByteString.fromUnsignedByte(b))).mkString(","))
          else {
            originActor ! RadiusClientResponse(radiusPacket, radiusId)
          }
          
          // Update stats
          endpointSuccesses(origin) = endpointSuccesses(origin) + 1
          
        case None =>
          log.warning("Radius request not found for response received")
      }
      
    case Clean =>
      val thresholdTimestamp = System.currentTimeMillis - clientTimeoutMillis
      
      // Gather the requests that that are older than the timeout, and remove them
      for {
        (endpoint, requestMap) <- requestCache
        (portId, originator) <- requestMap
      } if(originator.timestamp < thresholdTimestamp) {
        endpointErrors(endpoint) = endpointErrors(endpoint) + 1
        requestCache(endpoint).remove(portId)
      }
      
      // Build inmmutable map to send as message
      val endpoints = endpointSuccesses.keys.toSet ++ endpointErrors.keys.toSet
      val stats = for{
        endpoint <- endpoints.toList
      } yield (endpoint, (endpointSuccesses(endpoint), endpointErrors(endpoint)))
      
      // Report to the router the stats for the last interval
      if(stats.size > 0 ) context.parent ! Router.RadiusClientStats(stats.toMap)
      
      // Clear
      endpointSuccesses = Map().withDefaultValue(0)
      endpointErrors = Map().withDefaultValue(0)
      
      cleanTimer = Some(context.system.scheduler.scheduleOnce(cleanIntervalMillis milliseconds, self, Clean))
      
    case _ =>
  }
  
  override def preStart = {
    cleanTimer = Some(context.system.scheduler.scheduleOnce(cleanIntervalMillis milliseconds, self, Clean))
  }
  
  override def postStop = {
    cleanTimer.map(_.cancel)
  }
  
  // Helper function
  def pushToRequestCache(destination: RadiusEndpoint, radiusPortId: RadiusPortId, origin: RadiusOriginatorRef) = {
    requestCache.get(destination) match {
      case Some(destinationMap) => destinationMap.put(radiusPortId, origin)
      case None =>
        requestCache.put(destination, Map[RadiusPortId, RadiusOriginatorRef]((radiusPortId, origin)))
    }
  }
  
  def nextRadiusPortId(destination: RadiusEndpoint) = {
    // Get item (with default value)
    val radiusPortId = lastRadiusPortIds(destination)
    
    // Increment and push to map
    val _id = (radiusPortId.id + 1) % 256
    val _port = if(_id == 0) radiusPortId.port + 1 else radiusPortId.port
    val _radiusPortId = RadiusPortId(if(_port == basePort + numPorts) basePort else _port, _id)
    lastRadiusPortIds.put(destination, _radiusPortId)
    
    radiusPortId
  }
}