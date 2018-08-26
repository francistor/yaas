package yaas.server

import scala.collection.mutable.Map

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill }
import akka.event.{ Logging, LoggingReceive }
import yaas.config.RadiusServerConfig
import yaas.server.RadiusActorMessages._
import yaas.coding.radius.RadiusPacket
import yaas.util._

// This Actor handles the communication with upstream radius servers

object RadiusClient {
  def props(bindIPAddress: String, basePort: Int, numPorts: Int) = Props(new RadiusClient(bindIPAddress, basePort, numPorts))
  
  case class RadiusOriginatorRef(originActor: ActorRef, authenticator: Array[Byte])
}

class RadiusClient(bindIPAddress: String, basePort: Int, numPorts: Int) extends Actor with ActorLogging {
  
  import RadiusClient._
  
  // TODO: Clean the request cache?
  
  case class RadiusPortId(port: Int, id: Int)
  
  // Stores the last identifier used for the key RadiusDestination
  val radiusPortIds = Map[RadiusEndpoint, RadiusPortId]().withDefaultValue(RadiusPortId(basePort, 0))
  
  // For each radius destination, the map of identifiers to originators
  val requestCache = Map[RadiusEndpoint, Map[RadiusPortId, RadiusOriginatorRef]]().withDefaultValue(Map())
  
  // Span actors
  val socketActors = (for(i <- basePort to basePort + numPorts) yield context.actorOf(RadiusClientSocket.props(bindIPAddress, i), "RadiusClientSocket-"+ i))
  
  def receive = {
    
    case RadiusClientRequest(radiusPacket, destination, originActor) =>
      // Get port and id
      val radiusPortId = nextRadiusPortId(destination)
      
      // Populate cache
      pushToRequestCache(destination, radiusPortId, RadiusOriginatorRef(originActor, radiusPacket.authenticator))
      log.debug(s"Pushed entry to request cache: $destination -> $radiusPortId")
      // Send message to socket Actor
      radiusPacket.identifier = radiusPortId.id
      socketActors(radiusPortId.port - basePort) ! RadiusClientSocketRequest(radiusPacket.getBytes(destination.secret), destination)
      
    case RadiusClientSocketResponse(bytes, origin, clientPort) =>
      val identifier = UByteString.getUnsignedByte(bytes.slice(1, 2))
      // Look in cache
      val radiusPortId = RadiusPortId(clientPort, identifier)
      log.debug(s"Looking for entry in request cache: $origin -> $radiusPortId")
      requestCache(origin).remove(radiusPortId) match {
        case Some(RadiusOriginatorRef(originActor, authenticator)) =>
          val radiusPacket = RadiusPacket(bytes, Some(authenticator), origin.secret)
          // Check authenticator
          val code = radiusPacket.code
          // TODO: Only those types of packets?
          if(((code == RadiusPacket.ACCESS_ACCEPT) || (code == RadiusPacket.ACCESS_REJECT)) && !RadiusPacket.checkAuthenticator(bytes, authenticator, origin.secret))  
            log.warning("Bad authenticator from {}. Request-Authenticator: {}. Response-Authenticator: {}", 
                origin, authenticator.map(b => "%02X".format(UByteString.fromUnsignedByte(b))).mkString(","), bytes.slice(4, 20).toArray.map(b => "%02X".format(UByteString.fromUnsignedByte(b))).mkString(","))
          else {
            originActor ! RadiusClientResponse(radiusPacket, authenticator)
          }
          
        case None =>
          log.warning("Radius request not found for response received")
      }
      
    case _ =>
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
    val radiusPortId = radiusPortIds(destination)
    
    // Increment and push to map
    val _id = (radiusPortId.id + 1) % 256
    val _port = if(_id == 0) radiusPortId.port + 1 else radiusPortId.port
    val _radiusPortId = RadiusPortId(if(_port == basePort + numPorts) basePort else _port, _id)
    radiusPortIds.put(destination, _radiusPortId)
    
    radiusPortId
  }
}