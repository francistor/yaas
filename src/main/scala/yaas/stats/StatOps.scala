package yaas.stats

import akka.actor.{ActorRef}

import yaas.coding.DiameterMessage
import yaas.coding.DiameterMessageKey
import yaas.coding.RadiusPacket
import yaas.coding.RadiusPacketKey
import yaas.server.RadiusActorMessages._
import yaas.coding.DiameterConversions._
import yaas.stats.StatsServer._

/**
 * Helper functions
 * 
 * 
 */
object StatOps {
  
  /////////////////////////////////////////
  // Diameter
  /////////////////////////////////////////
  
  // Peer
  def pushDiameterRequestReceived(statsActor: ActorRef, peerName: String, diameterMessage: DiameterMessage)  = {
    statsActor !  DiameterRequestReceivedKey(peerName, diameterMessage >> "Origin-Host", diameterMessage >> "Origin-Realm", diameterMessage >> "Destination-Host", diameterMessage >> "Destination-Realm", 
        diameterMessage.applicationId.toString, diameterMessage.commandCode.toString)
  }
  
  def pushDiameterAnswerReceived(statsActor: ActorRef, peerName: String, diameterMessage: DiameterMessage, timestamp: Long) = {
    val rt : String = Math.ceil(Math.log(System.currentTimeMillis - timestamp)).toString
    statsActor !  DiameterAnswerReceivedKey(peerName, diameterMessage >> "Origin-Host", diameterMessage >> "Origin-Realm", diameterMessage >> "Destination-Host", diameterMessage >> "Destination-Realm", 
        diameterMessage.applicationId.toString, diameterMessage.commandCode.toString, (diameterMessage >> "Result-Code"), rt)
  }
  
  def pushDiameterRequestTimeout(statsActor: ActorRef, peerName: String, key: DiameterMessageKey) = {
    statsActor ! DiameterRequestTimeoutKey(peerName, key.originHost, key.originRealm, key.destinationHost, key.destinationRealm, key.applicationId, key.commandCode)
  }
  
  def pushDiameterAnswerSent(statsActor: ActorRef, peerName: String, diameterMessage: DiameterMessage) = {
    statsActor !  DiameterAnswerSentKey(peerName, diameterMessage >> "Origin-Host", diameterMessage >> "Origin-Realm", diameterMessage >> "Destination-Host", diameterMessage >> "Destination-Realm", 
        diameterMessage.applicationId.toString, diameterMessage.commandCode.toString, (diameterMessage >> "Result-Code"))
  }
  
  def pushDiameterRequestSent(statsActor: ActorRef, peerName: String, diameterMessage: DiameterMessage) = {
    statsActor !  DiameterRequestSentKey(peerName, diameterMessage >> "Origin-Host", diameterMessage >> "Origin-Realm", diameterMessage >> "Destination-Host", diameterMessage >> "Destination-Realm", 
        diameterMessage.applicationId.toString, diameterMessage.commandCode.toString)
  }
  
  // Router
  def pushDiameterRequestReceivedDropped(statsActor: ActorRef, diameterMessage: DiameterMessage) = {
    statsActor ! DiameterRequestReceivedDroppedKey(diameterMessage >> "Origin-Host", diameterMessage >> "Origin-Realm", diameterMessage >> "Destination-Host", diameterMessage >> "Destination-Realm", 
        diameterMessage.applicationId.toString, diameterMessage.commandCode.toString)
  }
  def pushDiameterRequestSentDropped(statsActor: ActorRef, diameterMessage: DiameterMessage) = {
    statsActor ! DiameterRequestSentDroppedKey(diameterMessage >> "Origin-Host", diameterMessage >> "Origin-Realm", diameterMessage >> "Destination-Host", diameterMessage >> "Destination-Realm", 
        diameterMessage.applicationId.toString, diameterMessage.commandCode.toString)
  }
  def pushDiameterRequestRetransmittedDropped(statsActor: ActorRef, diameterMessage: DiameterMessage) = {
    statsActor ! DiameterRequestRetransmittedKey(diameterMessage >> "Origin-Host", diameterMessage >> "Origin-Realm", diameterMessage >> "Destination-Host", diameterMessage >> "Destination-Realm", 
        diameterMessage.applicationId.toString, diameterMessage.commandCode.toString)
  }
  
  // Handler
  
  def pushDiameterHandlerAnswer(statsActor: ActorRef, requestMessage: DiameterMessage, responseMessage: DiameterMessage, responseTime: Long) = {
    val rt : String = Math.ceil(Math.log(responseTime)).toString
    statsActor ! DiameterHandlerAnswerKey(requestMessage >> "Origin-Host", requestMessage >> "Origin-Realm", requestMessage >> "Destination-Host", requestMessage >> "Destination-Realm", 
        requestMessage.applicationId.toString, requestMessage.commandCode.toString, responseMessage >> "Result-Code", rt)
  }
  
  def pushDiameterHandlerRequest(statsActor: ActorRef, requestMessage: DiameterMessage, responseMessage: DiameterMessage, responseTime: Long) = {
    val rt : String = Math.ceil(Math.log(responseTime)).toString
    statsActor ! DiameterHandlerAnswerKey(requestMessage >> "Origin-Host", requestMessage >> "Origin-Realm", requestMessage >> "Destination-Host", requestMessage >> "Destination-Realm", 
        requestMessage.applicationId.toString, requestMessage.commandCode.toString, responseMessage >> "Result-Code", rt)
  }
  
  def pushDiameterHandlerRequestTimeout(statsActor: ActorRef, key: DiameterMessageKey) = {
    statsActor ! DiameterHandlerRequestTimeoutKey(key.originHost, key.originRealm, key.destinationHost, key.destinationRealm, key.applicationId, key.commandCode)
  }
 
  
  /////////////////////////////////////////
  // Radius
  /////////////////////////////////////////
  
  // Server
  def pushRadiusServerRequest(statsActor: ActorRef, dest: RadiusEndpoint, reqPacket: RadiusPacket) = {
    statsActor ! RadiusServerRequestKey(s"${dest.ipAddress}:${dest.port}", reqPacket.code.toString)
  }
  
  def pushRadiusServerResponse(statsActor: ActorRef, org: RadiusEndpoint, resPacket: RadiusPacket) = {
    statsActor ! RadiusServerResponseKey(s"${org.ipAddress}:${org.port}", resPacket.code.toString)
  }
  
  // Client
  def pushRadiusClientRequest(statsActor: ActorRef, dest: RadiusEndpoint, reqPacket: RadiusPacket) = {
    statsActor ! RadiusClientRequestKey(s"${dest.ipAddress}:${dest.port}", reqPacket.code.toString)
  }
  
  def pushRadiusClientResponse(statsActor: ActorRef, org: RadiusEndpoint, reqKey: RadiusPacketKey, resPacket: RadiusPacket, responseTime: Long) = {
    val rt : String = Math.ceil(Math.log(responseTime)).toString
    statsActor ! RadiusClientResponseKey(s"${org.ipAddress}:${org.port}", reqKey.code, resPacket.code.toString, rt)
  }
  
  def pushRadiusClientTimeout(statsActor: ActorRef, dest: RadiusEndpoint, reqKey: RadiusPacketKey) = {
    statsActor ! RadiusClientTimeoutKey(s"${dest.ipAddress}:${dest.port}", reqKey.code)
  }
  
  // Handler server
  def pushRadiusHandlerResponse(statsActor: ActorRef, org: RadiusEndpoint, reqPacket: RadiusPacket, resPacket: RadiusPacket, responseTime: Long) = {
    val rt : String = Math.ceil(Math.log(responseTime)).toString
    statsActor ! RadiusHandlerResponseKey(s"${org.ipAddress}:${org.port}", reqPacket.code.toString, resPacket.code.toString, rt)
  }
    
  def pushRadiusHandlerDrop(statsActor: ActorRef, org: RadiusEndpoint, reqPacket: RadiusPacket) = {
    statsActor ! RadiusHandlerDropKey(s"${org.ipAddress}:${org.port}", reqPacket.code.toString)
  }
  
  // Handler client
  def pushRadiusHandlerRequest(statsActor: ActorRef, group: String, reqPacket: RadiusPacket, resPacket: RadiusPacket, responseTime: Long) = {
    val rt : String = Math.ceil(Math.log(responseTime)).toString
    statsActor ! RadiusHandlerRequestKey(group, reqPacket.code.toString, resPacket.code.toString, rt)
  }
  
  def pushRadiusHandlerRestransmission(statsActor: ActorRef, group: String, reqPacket: RadiusPacket) = {
    statsActor ! RadiusHandlerDropKey(group, reqPacket.code.toString)
  }
  
  def pushRadiusHandlerTimeout(statsActor: ActorRef, group: String, reqPacket: RadiusPacket) = {
    statsActor ! RadiusHandlerTimeoutKey(group, reqPacket.code.toString)
  }
}