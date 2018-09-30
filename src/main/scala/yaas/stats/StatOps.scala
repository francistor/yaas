package yaas.stats

import akka.actor.{ActorRef}

import yaas.coding.DiameterMessage
import yaas.coding.DiameterMessageKey
import yaas.coding.RadiusPacket
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
  def pushDiameterReceivedDropped(statsActor: ActorRef, diameterMessage: DiameterMessage) = {
    statsActor ! DiameterRequestDroppedKey(diameterMessage >> "Origin-Host", diameterMessage >> "Origin-Realm", diameterMessage >> "Destination-Host", diameterMessage >> "Destination-Realm", 
        diameterMessage.applicationId.toString, diameterMessage.commandCode.toString)
  }
  
  // Handler
  def pushDiameterHandlerServer(statsActor: ActorRef, requestMessage: DiameterMessage, responseMessage: DiameterMessage, responseTime: Long) = {
    val rt : String = Math.ceil(Math.log(responseTime)).toString
    statsActor ! DiameterHandlerServerKey(requestMessage >> "Origin-Host", requestMessage >> "Origin-Realm", requestMessage >> "Destination-Host", requestMessage >> "Destination-Realm", 
        requestMessage.applicationId.toString, requestMessage.commandCode.toString, responseMessage >> "Result-Code", rt)
  }
  
  def pushDiameterHandlerClient(statsActor: ActorRef, requestKey: DiameterMessageKey, responseMessage: DiameterMessage, responseTime: Long) = {
    val rt : String = Math.ceil(Math.log(responseTime)).toString
    statsActor ! DiameterHandlerClientKey(requestKey.originHost, requestKey.originRealm, requestKey.destinationHost, requestKey.destinationRealm, requestKey.applicationId, requestKey.commandCode, responseMessage >> "Result-Code", rt)
  }
  
  def pushDiameterHandlerClientTimeout(statsActor: ActorRef, requestKey: DiameterMessageKey) = {
    statsActor ! DiameterHandlerClientTimeoutKey(requestKey.originHost, requestKey.originRealm, requestKey.destinationHost, requestKey.destinationRealm, requestKey.applicationId, requestKey.commandCode)
  }
  
  /////////////////////////////////////////
  // Radius
  /////////////////////////////////////////
  
  // Server
  def pushRadiusServerRequest(statsActor: ActorRef, org: RadiusEndpoint, reqPacket: RadiusPacket) = {
    statsActor ! RadiusServerRequestKey(s"${org.ipAddress}:${org.port}", reqPacket.code.toString)
  }
  
  def pushRadiusServerDrop(statsActor: ActorRef, ipAddress: String, port: Int) = {
    statsActor ! RadiusServerDropKey(s"${ipAddress}:${port}")
  }
  
  def pushRadiusServerResponse(statsActor: ActorRef, org: RadiusEndpoint, resPacket: RadiusPacket) = {
    statsActor ! RadiusServerResponseKey(s"${org.ipAddress}:${org.port}", resPacket.code.toString)
  }
  
  // Client
  def pushRadiusClientRequest(statsActor: ActorRef, dest: RadiusEndpoint, reqPacket: RadiusPacket) = {
    statsActor ! RadiusClientRequestKey(s"${dest.ipAddress}:${dest.port}", reqPacket.code.toString)
  }
  
  def pushRadiusClientResponse(statsActor: ActorRef, dest: RadiusEndpoint, reqCode: Int, resPacket: RadiusPacket, responseTime: Long) = {
    val rt : String = Math.ceil(Math.log(responseTime)).toString
    statsActor ! RadiusClientResponseKey(s"${dest.ipAddress}:${dest.port}", reqCode.toString, resPacket.code.toString, rt)
  }
  
  def pushRadiusClientTimeout(statsActor: ActorRef, dest: RadiusEndpoint, reqCode: Int) = {
    statsActor ! RadiusClientTimeoutKey(s"${dest.ipAddress}:${dest.port}", reqCode.toString)
  }
  
  def pushRadiusClientDrop(statsActor: ActorRef, ipAddress: String, port: Int) = {
    statsActor ! RadiusClientDroppedKey(s"${ipAddress}:${port}")
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
  def pushRadiusHandlerRequest(statsActor: ActorRef, group: String, reqCode: Int, resPacket: RadiusPacket, responseTime: Long) = {
    val rt : String = Math.ceil(Math.log(responseTime)).toString
    statsActor ! RadiusHandlerRequestKey(group, reqCode.toString, resPacket.code.toString, rt)
  }
  
  def pushRadiusHandlerRetransmission(statsActor: ActorRef, group: String, reqCode: Int) = {
    statsActor ! RadiusHandlerDropKey(group, reqCode.toString)
  }
  
  def pushRadiusHandlerTimeout(statsActor: ActorRef, group: String, reqCode: Int) = {
    statsActor ! RadiusHandlerTimeoutKey(group, reqCode.toString)
  }
}