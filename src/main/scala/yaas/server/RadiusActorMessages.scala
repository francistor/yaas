package yaas.server

import akka.actor.{ActorRef}
import akka.util.ByteString

import yaas.coding.radius.RadiusPacket

/**
 * Holder of messages exchanged between Actors for Radius protocol
 */

object RadiusActorMessages {
  
  case class RadiusEndpoint(ipAddress: String, port: Int, secret: String)
  
  // Server <--> Handler
  case class RadiusServerRequest(radiusPacket: RadiusPacket, originActor: ActorRef, origin: RadiusEndpoint)
  case class RadiusServerResponse(radiusPacket: RadiusPacket, origin: RadiusEndpoint)
  
  // Router/Client <--> Handler
  case class RadiusGroupClientRequest(radiusPacket: RadiusPacket, serverGroupName: String, authenticator: Array[Byte])
  case class RadiusClientRequest(radiusPacket: RadiusPacket, destination: RadiusEndpoint, originActor: ActorRef, authenticator: Array[Byte])
  case class RadiusClientResponse(radiusPacket: RadiusPacket, authenticator: Array[Byte])
  
  // Client <--> ClientSocket
  case class RadiusClientSocketRequest(radiusPacket: RadiusPacket, destination: RadiusEndpoint)
  case class RadiusClientSocketResponse(radiusPacket: RadiusPacket, origin: RadiusEndpoint, clientPort: Int, rawPacket: ByteString)
}