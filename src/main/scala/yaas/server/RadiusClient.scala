package yaas.server

import scala.collection.mutable.Map
import scala.concurrent.duration._

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill, Cancellable }
import akka.event.{ Logging, LoggingReceive }
import yaas.config.RadiusServerConfig
import yaas.server.RadiusActorMessages._
import yaas.coding.RadiusPacket
import yaas.util._
import yaas.instrumentation.StatOps
import com.typesafe.config.ConfigFactory

// This Actor handles the communication with upstream radius servers
object RadiusClient {
  def props(bindIPAddress: String, basePort: Int, numPorts: Int, statsServer: ActorRef) = Props(new RadiusClient(bindIPAddress, basePort, numPorts, statsServer))
 
  // Messages
  case object Clean
}

case class RadiusPortId(port: Int, id: Int)
case class RadiusRequestRef(originActor: ActorRef, authenticator: Array[Byte], radiusId: Long, endPoint: RadiusEndpoint, secret: String, requestCode: Int, requestTimestamp: Long)

class RadiusClient(bindIPAddress: String, basePort: Int, numPorts: Int, statsServer: ActorRef) extends Actor with ActorLogging {
  
  import RadiusClient._
  
  val config = ConfigFactory.load().getConfig("aaa.radius")
  
  val cleanMapIntervalMillis = config.getInt("clientMapIntervalMillis")
  val clientMapTimeoutMillis = config.getInt("clientMapTimeoutMillis")
  var cleanTimer: Option[Cancellable] = None
  
  implicit val executionContext = context.system.dispatcher
    
  // For each radius destination, the map of identifiers (port + id) to requests
  val requestMap = Map[RadiusEndpoint, Map[RadiusPortId, RadiusRequestRef]]().withDefaultValue(Map())
  
  // Stores the last identifier used for the key RadiusDestination
  val lastRadiusPortIds = Map[RadiusEndpoint, RadiusPortId]().withDefaultValue(RadiusPortId(basePort, 0))
  
  // For each radius destination, store the stats
  var endpointSuccesses = Map[RadiusEndpoint, Int]().withDefaultValue(0)
  var endpointErrors = Map[RadiusEndpoint, Int]().withDefaultValue(0)
  
  // Span actors. One for each socket
  val socketActors = (for(i <- basePort to basePort + numPorts) yield context.actorOf(RadiusClientSocket.props(bindIPAddress, i), "RadiusClientSocket-"+ i))
  
  def receive = {
    
    case RadiusClientRequest(requestPacket, endpoint, secret, originActor, radiusId) =>
      // Get port and id
      val radiusPortId = nextRadiusPortId(endpoint)
      
      // Get packet to send
      val bytes = requestPacket.getRequestBytes(secret, radiusPortId.id)
      
      // Populate request Map
      pushToRequestMap(endpoint, radiusPortId, RadiusRequestRef(originActor, requestPacket.authenticator, radiusId, endpoint, secret, requestPacket.code, System.currentTimeMillis))
      log.debug(s"Pushed entry to request Map: $endpoint - $radiusPortId")
      
      // Send message to socket Actor
      socketActors(radiusPortId.port - basePort) ! RadiusClientSocketRequest(bytes, endpoint)
      
      // Add stats
      StatOps.pushRadiusClientRequest(statsServer, endpoint, requestPacket.code)
      
    case RadiusClientSocketResponse(bytes, endpoint, clientPort) =>
      val identifier = UByteString.getUnsignedByte(bytes.slice(1, 2))
      // Look in request Map
      val radiusPortId = RadiusPortId(clientPort, identifier)
      log.debug(s"Looking for entry in request Map: $endpoint - $radiusPortId")
      
      requestMap(endpoint).remove(radiusPortId) match {
        case Some(RadiusRequestRef(originActor, reqAuthenticator, radiusId, ep, secret, reqCode, requestTimestamp)) =>
          val responsePacket = RadiusPacket(bytes, Some(reqAuthenticator), secret)
          
          // Check authenticator
          val code = responsePacket.code
          if((code != RadiusPacket.ACCOUNTING_RESPONSE) && !RadiusPacket.checkAuthenticator(bytes, reqAuthenticator, secret)){
            log.warning("Bad authenticator from {}. Request-Authenticator: {}. Response-Authenticator: {}", 
                ep, reqAuthenticator.map(b => "%02X".format(UByteString.fromUnsignedByte(b))).mkString(","), bytes.slice(4, 20).toArray.map(b => "%02X".format(UByteString.fromUnsignedByte(b))).mkString(","))
            StatOps.pushRadiusClientDrop(statsServer, ep.ipAddress, ep.port)
          }
          else {
            originActor ! RadiusClientResponse(responsePacket, radiusId)
            StatOps.pushRadiusClientResponse(statsServer, ep, reqCode, responsePacket.code, requestTimestamp)
          }
          
          // Update stats
          endpointSuccesses(ep) = endpointSuccesses(ep) + 1
          
        case None =>
          log.warning(s"Radius request not found for response received from endpoint $endpoint to $radiusPortId")
          StatOps.pushRadiusClientDrop(statsServer, endpoint.ipAddress, endpoint.port)
      }
      
    case Clean =>
      val thresholdTimestamp = System.currentTimeMillis - clientMapTimeoutMillis
      
      // Gather the requests that that are older than the timeout, and remove them
      for {
        (endpoint, endpointRequestMap) <- requestMap
        (portId, requestRef) <- endpointRequestMap
      } if(requestRef.requestTimestamp < thresholdTimestamp) {
        endpointErrors(endpoint) = endpointErrors(endpoint) + 1
        requestMap(endpoint).remove(portId).foreach(reqRef => StatOps.pushRadiusClientTimeout(statsServer, reqRef.endPoint, reqRef.requestCode))
      }
      
      // Build immutable map to send as message
      val endpoints = endpointSuccesses.keys.toSet ++ endpointErrors.keys.toSet
      val stats = for{
        endpoint <- endpoints.toList
      } yield (endpoint, (endpointSuccesses(endpoint), endpointErrors(endpoint)))
      
      // Report to the router the stats for the last interval
      if(stats.size > 0 ) context.parent ! Router.RadiusClientStats(stats.toMap)
      
      // Clear
      endpointSuccesses = Map().withDefaultValue(0)
      endpointErrors = Map().withDefaultValue(0)
      cleanTimer = Some(context.system.scheduler.scheduleOnce(cleanMapIntervalMillis milliseconds, self, Clean))
      
    case _ =>
  }
  
  override def preStart = {
    cleanTimer = Some(context.system.scheduler.scheduleOnce(cleanMapIntervalMillis milliseconds, self, Clean))
  }
  
  override def postStop = {
    cleanTimer.map(_.cancel)
  }
  
  // Helper function
  def pushToRequestMap(destination: RadiusEndpoint, radiusPortId: RadiusPortId, reqRef: RadiusRequestRef) = {
    requestMap.get(destination) match {
      case Some(endpointRequestMap) => endpointRequestMap.put(radiusPortId, reqRef)
      case None =>
        requestMap.put(destination, Map[RadiusPortId, RadiusRequestRef]((radiusPortId, reqRef)))
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