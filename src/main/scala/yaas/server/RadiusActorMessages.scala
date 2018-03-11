package yaas.server

import akka.actor.{ActorRef}

import yaas.coding.radius.RadiusPacket

/**
 * Holder of messages exchanged between Actors for Radius protocol
 */

object RadiusActorMessages {
  
  case class RadiusOrigin(ipAddress: String, port: Int, secret: String)
  
  case class RadiusServerRequest(radiusPacket: RadiusPacket, originActor: ActorRef, origin: RadiusOrigin)
  case class RadiusServerResponse(radiusPacket: RadiusPacket, origin: RadiusOrigin)
  
  case class RadiusGroupClientRequest(radiusPacket: RadiusPacket, serverGroupName: String, e2eId: Int)
  case class RadiusClientRequest(radiusPacket: RadiusPacket, originActor: ActorRef, e2eId: Int)
  case class RadiusClientResponse(radiusPacket: RadiusPacket, e2eId: Int)
}