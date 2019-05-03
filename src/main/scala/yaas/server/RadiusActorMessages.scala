package yaas.server

import akka.actor.{ActorRef}
import akka.util.ByteString

import yaas.coding.RadiusPacket

/**
 * Holder of messages exchanged between Actors for Radius protocol
 */

object RadiusActorMessages {
  
  case class RadiusEndpoint(ipAddress: String, port: Int)
  
  // Server <--> Handler
  case class RadiusServerRequest(radiusPacket: RadiusPacket, originActor: ActorRef, origin: RadiusEndpoint, secret: String)
  case class RadiusServerResponse(radiusPacket: RadiusPacket, origin: RadiusEndpoint, secret: String)
  
  // Router/Client <--> Handler
  case class RadiusGroupClientRequest(radiusPacket: RadiusPacket, serverGroupName: String, radiusId: Long, retryNum: Int)
  case class RadiusClientRequest(radiusPacket: RadiusPacket, destination: RadiusEndpoint, secret: String, originActor: ActorRef, radiusId: Long)
  case class RadiusClientResponse(radiusPacket: RadiusPacket, radiusId: Long)
  
  // Client <--> ClientSocket
  case class RadiusClientSocketRequest(bytes: ByteString, destination: RadiusEndpoint)
  case class RadiusClientSocketResponse(bytes: ByteString, origin: RadiusEndpoint, clientPort: Int)
}