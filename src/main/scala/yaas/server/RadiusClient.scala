package yaas.server

import scala.collection.mutable.Map

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill }
import akka.event.{ Logging, LoggingReceive }
import yaas.config.RadiusServerConfig
import yaas.server.RadiusActorMessages._
import yaas.coding.radius.RadiusPacket
import yaas.util._

// This Actor handles the communication with upstream radius servers

// TODO: Change names
//  OriginData -> RadiusClientActor
//  PortId -> RadiusPortId
//  RadiusEndpoint --> RadiusProxyEndpoint
//  authenticator --> reqAuthenticator / respAuthenticator
//  Remove authenticator from messages

object RadiusClient {
  def props(bindIPAddress: String, basePort: Int, numPorts: Int) = Props(new RadiusClient(bindIPAddress, basePort, numPorts))
  
  case class OriginData(originActor: ActorRef, authenticator: Array[Byte])
}

class RadiusClient(bindIPAddress: String, basePort: Int, numPorts: Int) extends Actor with ActorLogging {
  
  import RadiusClient._
  
  // TODO: Clean the request cache
  
  case class PortId(port: Int, id: Int)
  
  // Stores the last identifier used for the key RadiusDestination
  val portIds = Map[RadiusEndpoint, PortId]().withDefaultValue(PortId(basePort, 0))
  
  // For each radius destination, the map of identifiers to originators
  val requestCache = Map[RadiusEndpoint, Map[PortId, OriginData]]().withDefaultValue(Map())
  
  // Span actors
  val socketActors = (for(i <- basePort to basePort + numPorts) yield context.actorOf(RadiusClientSocket.props(bindIPAddress, i), "RadiusClientSocket-"+ i))
  
  def receive = {
    
    case RadiusClientRequest(radiusPacket, destination, originActor, authenticator) =>
      // Get port and id
      val portId = nextPortId(destination)
      
      // Populate cache
      pushToRequestCache(destination, portId, OriginData(originActor, authenticator))
      log.debug(s"Pushed entry to request cache: $destination -> $portId")
      // Send message to socket Actor
      radiusPacket.identifier = portId.id
      socketActors(portId.port - basePort) ! RadiusClientSocketRequest(radiusPacket, destination)
      
    case RadiusClientSocketResponse(radiusPacket, origin, clientPort, data) =>
      // Look in cache
      val portId = PortId(clientPort, radiusPacket.identifier)
      log.debug(s"Looking for entry in request cache: $origin -> $portId")
      // TODO: Replace by .remove
      requestCache(origin).get(portId) match {
        case Some(OriginData(originActor, authenticator)) =>
          // Check authenticator
          val code = radiusPacket.code
          // TODO: Only those types of packets? Shouldn't the call to checkAuthenticator be negated
          if(((code == RadiusPacket.ACCESS_ACCEPT) || (code == RadiusPacket.ACCESS_REJECT)) && !RadiusPacket.checkAuthenticator(data, authenticator, origin.secret))  
            log.warning("Bad authenticator from {}. Request-Authenticator: {}. Response-Authenticator: {}", 
                origin, authenticator.map(b => "%02X".format(UByteString.fromUnsignedByte(b))).mkString(","), data.slice(4, 20).toArray.map(b => "%02X".format(UByteString.fromUnsignedByte(b))).mkString(","))
          else {
            originActor ! RadiusClientResponse(radiusPacket, authenticator)
          }
          
        case None =>
          log.warning("Radius request not found for response received")
      }
      
    case _ => Nil
  }
  
  // Helper function
  def pushToRequestCache(destination: RadiusEndpoint, portId: PortId, origin: OriginData) = {
    requestCache.get(destination) match {
      case Some(destinationMap) => destinationMap.put(portId, origin)
      case None =>
        requestCache.put(destination, Map[PortId, OriginData]((portId, origin)))
    }
  }
  
  def nextPortId(destination: RadiusEndpoint) = {
    // Get item (with default value)
    val portId = portIds(destination)
    
    // Increment and push to map
    val _id = (portId.id + 1) % 256
    val _port = if(_id == 0) portId.port + 1 else portId.port
    val _portId = PortId(if(_port == basePort + numPorts) basePort else _port, _id)
    portIds.put(destination, _portId)
    
    portId
  }
}