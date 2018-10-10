package yaas.stats

import akka.actor.{ActorRef}

import yaas.coding.DiameterMessage
import yaas.coding.DiameterMessageKey
import yaas.coding.RadiusPacket
import yaas.server.RadiusActorMessages._
import yaas.coding.DiameterConversions._
import yaas.stats.StatsServer._
import yaas.config.DiameterPeerConfig

/**
 * Helper functions
 * 
 * 
 */
object StatOps {
  
  val toLog2 = 0.6931478
  
  /////////////////////////////////////////
  // Diameter
  /////////////////////////////////////////
  
  case class DiameterPeerStat(config: DiameterPeerConfig, status: Int)
  
  // Peer
  def pushDiameterRequestReceived(statsActor: ActorRef, peerName: String, diameterMessage: DiameterMessage)  = {
    statsActor !  DiameterRequestReceivedKey(peerName, diameterMessage >> "Origin-Host", diameterMessage >> "Origin-Realm", diameterMessage >> "Destination-Host", diameterMessage >> "Destination-Realm", 
        diameterMessage.applicationId.toString, diameterMessage.commandCode.toString)
  }
  
  def pushDiameterAnswerReceived(statsActor: ActorRef, peerName: String, diameterMessage: DiameterMessage, timestamp: Long) = {
    val rt : String = Math.ceil(Math.log(System.currentTimeMillis - timestamp) * toLog2).toString
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
    val rt : String = Math.ceil(Math.log(responseTime) * toLog2).toString
    statsActor ! DiameterHandlerServerKey(requestMessage >> "Origin-Host", requestMessage >> "Origin-Realm", requestMessage >> "Destination-Host", requestMessage >> "Destination-Realm", 
        requestMessage.applicationId.toString, requestMessage.commandCode.toString, responseMessage >> "Result-Code", rt)
  }
  
  def pushDiameterHandlerClient(statsActor: ActorRef, requestKey: DiameterMessageKey, responseMessage: DiameterMessage, responseTime: Long) = {
    val rt : String = Math.ceil(Math.log(responseTime) * toLog2).toString
    statsActor ! DiameterHandlerClientKey(requestKey.originHost, requestKey.originRealm, requestKey.destinationHost, requestKey.destinationRealm, requestKey.applicationId, requestKey.commandCode, responseMessage >> "Result-Code", rt)
  }
  
  def pushDiameterHandlerClientTimeout(statsActor: ActorRef, requestKey: DiameterMessageKey) = {
    statsActor ! DiameterHandlerClientTimeoutKey(requestKey.originHost, requestKey.originRealm, requestKey.destinationHost, requestKey.destinationRealm, requestKey.applicationId, requestKey.commandCode)
  }
  
  /////////////////////////////////////////
  // Radius
  /////////////////////////////////////////
  
  // Server
  def pushRadiusServerRequest(statsActor: ActorRef, org: RadiusEndpoint, reqCode: Int) = {
    statsActor ! RadiusServerRequestKey(s"${org.ipAddress}:${org.port}", reqCode.toString)
  }
  
  def pushRadiusServerDrop(statsActor: ActorRef, ipAddress: String, port: Int) = {
    statsActor ! RadiusServerDropKey(s"${ipAddress}")
  }
  
  def pushRadiusServerResponse(statsActor: ActorRef, org: RadiusEndpoint, resCode: Int) = {
    statsActor ! RadiusServerResponseKey(s"${org.ipAddress}", resCode.toString)
  }
  
  // Client
  def pushRadiusClientRequest(statsActor: ActorRef, dest: RadiusEndpoint, reqCode: Int) = {
    statsActor ! RadiusClientRequestKey(s"${dest.ipAddress}:${dest.port}", reqCode.toString)
  }
  
  def pushRadiusClientResponse(statsActor: ActorRef, dest: RadiusEndpoint, reqCode: Int, resCode: Int, responseTime: Long) = {
    val rt : String = Math.ceil(Math.log(responseTime)).toString
    statsActor ! RadiusClientResponseKey(s"${dest.ipAddress}:${dest.port}", reqCode.toString, resCode.toString, rt)
  }
  
  def pushRadiusClientTimeout(statsActor: ActorRef, dest: RadiusEndpoint, reqCode: Int) = {
    statsActor ! RadiusClientTimeoutKey(s"${dest.ipAddress}:${dest.port}", reqCode.toString)
  }
  
  def pushRadiusClientDrop(statsActor: ActorRef, ipAddress: String, port: Int) = {
    statsActor ! RadiusClientDroppedKey(s"${ipAddress}:${port}")
  }
  
  // Handler server
  def pushRadiusHandlerResponse(statsActor: ActorRef, org: RadiusEndpoint, reqCode: Int, resCode: Int, responseTime: Long) = {
    val rt : String = Math.ceil(Math.log(responseTime)).toString
    statsActor ! RadiusHandlerResponseKey(s"${org.ipAddress}", reqCode.toString, resCode.toString, rt)
  }
    
  def pushRadiusHandlerDrop(statsActor: ActorRef, org: RadiusEndpoint, reqCode: Int) = {
    statsActor ! RadiusHandlerDroppedKey(s"${org.ipAddress}", reqCode.toString)
  }
  
  // Handler client
  def pushRadiusHandlerRequest(statsActor: ActorRef, group: String, reqCode: Int, resCode: Int, responseTime: Long) = {
    val rt : String = Math.ceil(Math.log(responseTime) * toLog2).toString
    statsActor ! RadiusHandlerRequestKey(group, reqCode.toString, resCode.toString, rt)
  }
  
  def pushRadiusHandlerRetransmission(statsActor: ActorRef, group: String, reqCode: Int) = {
    statsActor ! RadiusHandlerRetransmissionKey(group, reqCode.toString)
  }
  
  def pushRadiusHandlerTimeout(statsActor: ActorRef, group: String, reqCode: Int) = {
    statsActor ! RadiusHandlerTimeoutKey(group, reqCode.toString)
  }
}