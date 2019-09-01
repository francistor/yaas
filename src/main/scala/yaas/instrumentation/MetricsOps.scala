package yaas.instrumentation

import akka.actor.{ActorRef}

import yaas.coding.DiameterMessage
import yaas.coding.DiameterMessageKey
import yaas.coding.RadiusPacket
import yaas.server.RadiusActorMessages._
import yaas.coding.DiameterConversions._
import yaas.config.DiameterPeerConfig
import MetricsServer._

/**
 * Helper functions
 * 
 * 
 */
object MetricsOps {
  
  val toLog2 = 1.44269504089
  
  def crt(timestamp: Long) = {
    val now = System.currentTimeMillis
    if(timestamp <= now) "0"
    else Math.ceil(Math.log(System.currentTimeMillis - timestamp) * toLog2).toString
  }
  
  /////////////////////////////////////////
  // Diameter
  /////////////////////////////////////////
  
  case class DiameterPeerStatus(config: DiameterPeerConfig, status: Int)
  
  def updateDiameterPeerQueueGauge(metricsActor: ActorRef, peerName: String, size: Int) = {
    metricsActor ! DiameterPeerQueueSize(peerName, size)
  }
  
  // Peer
  def pushDiameterRequestReceived(metricsActor: ActorRef, peerName: String, diameterMessage: DiameterMessage)  = {
    metricsActor !  DiameterRequestReceivedKey(peerName, diameterMessage >> "Origin-Host", diameterMessage >> "Origin-Realm", diameterMessage >> "Destination-Host", diameterMessage >> "Destination-Realm", 
        diameterMessage.applicationId.toString, diameterMessage.commandCode.toString)
  }
  
  def pushDiameterAnswerReceived(metricsActor: ActorRef, peerName: String, diameterMessage: DiameterMessage, requestTimestamp: Long) = {
    metricsActor !  DiameterAnswerReceivedKey(peerName, diameterMessage >> "Origin-Host", diameterMessage >> "Origin-Realm", diameterMessage >> "Destination-Host", diameterMessage >> "Destination-Realm", 
        diameterMessage.applicationId.toString, diameterMessage.commandCode.toString, (diameterMessage >> "Result-Code"), crt(requestTimestamp))
  }
  
  def pushDiameterRequestTimeout(metricsActor: ActorRef, peerName: String, key: DiameterMessageKey) = {
    metricsActor ! DiameterRequestTimeoutKey(peerName, key.originHost, key.originRealm, key.destinationHost, key.destinationRealm, key.applicationId, key.commandCode)
  }
  
  def pushDiameterAnswerSent(metricsActor: ActorRef, peerName: String, diameterMessage: DiameterMessage) = {
    metricsActor !  DiameterAnswerSentKey(peerName, diameterMessage >> "Origin-Host", diameterMessage >> "Origin-Realm", diameterMessage >> "Destination-Host", diameterMessage >> "Destination-Realm", 
        diameterMessage.applicationId.toString, diameterMessage.commandCode.toString, (diameterMessage >> "Result-Code"))
  }
  
  def pushDiameterRequestSent(metricsActor: ActorRef, peerName: String, diameterMessage: DiameterMessage) = {
    metricsActor !  DiameterRequestSentKey(peerName, diameterMessage >> "Origin-Host", diameterMessage >> "Origin-Realm", diameterMessage >> "Destination-Host", diameterMessage >> "Destination-Realm", 
        diameterMessage.applicationId.toString, diameterMessage.commandCode.toString)
  }
  
  def pushDiameterDiscardedAnswer(metricsActor: ActorRef, peerName: String, diameterMessage: DiameterMessage) = {
    metricsActor !  DiameterAnswerDiscardedKey(peerName, diameterMessage >> "Origin-Host", diameterMessage >> "Origin-Realm", diameterMessage >> "Destination-Host", diameterMessage >> "Destination-Realm", 
        diameterMessage.applicationId.toString, diameterMessage.commandCode.toString, diameterMessage >> "Result-Code")
  }
  
  // Router
  def pushDiameterReceivedDropped(metricsActor: ActorRef, diameterMessage: DiameterMessage) = {
    metricsActor ! DiameterRequestDroppedKey(diameterMessage >> "Origin-Host", diameterMessage >> "Origin-Realm", diameterMessage >> "Destination-Host", diameterMessage >> "Destination-Realm", 
        diameterMessage.applicationId.toString, diameterMessage.commandCode.toString)
  }
  
  // Handler
  def pushDiameterHandlerServer(metricsActor: ActorRef, requestMessage: DiameterMessage, responseMessage: DiameterMessage, requestTimestamp: Long) = {
    metricsActor ! DiameterHandlerServerKey(requestMessage >> "Origin-Host", requestMessage >> "Origin-Realm", requestMessage >> "Destination-Host", requestMessage >> "Destination-Realm", 
        requestMessage.applicationId.toString, requestMessage.commandCode.toString, responseMessage >> "Result-Code", crt(requestTimestamp))
  }
  
  def pushDiameterHandlerClient(metricsActor: ActorRef, requestKey: DiameterMessageKey, responseMessage: DiameterMessage, requestTimestamp: Long) = {
    metricsActor ! DiameterHandlerClientKey(requestKey.originHost, requestKey.originRealm, requestKey.destinationHost, requestKey.destinationRealm, requestKey.applicationId, requestKey.commandCode, responseMessage >> "Result-Code", crt(requestTimestamp))
  }
  
  def pushDiameterHandlerClientTimeout(metricsActor: ActorRef, requestKey: DiameterMessageKey) = {
    metricsActor ! DiameterHandlerClientTimeoutKey(requestKey.originHost, requestKey.originRealm, requestKey.destinationHost, requestKey.destinationRealm, requestKey.applicationId, requestKey.commandCode)
  }
  
  /////////////////////////////////////////
  // Radius
  /////////////////////////////////////////
  
  def updateRadiusClientRequestQueueGauges(metricsActor: ActorRef, queueSizes: Map[RadiusEndpoint, Int]) = {
    metricsActor ! RadiusClientQueueSizes(queueSizes)
  }
  
  // Server
  def pushRadiusServerRequest(metricsActor: ActorRef, org: RadiusEndpoint, reqCode: Int) = {
    metricsActor ! RadiusServerRequestKey(s"${org.ipAddress}", reqCode.toString)
  }
  
  def pushRadiusServerDrop(metricsActor: ActorRef, ipAddress: String, port: Int) = {
    metricsActor ! RadiusServerDropKey(s"${ipAddress}")
  }
  
  def pushRadiusServerResponse(metricsActor: ActorRef, org: RadiusEndpoint, resCode: Int) = {
    metricsActor ! RadiusServerResponseKey(s"${org.ipAddress}", resCode.toString)
  }
  
  // Client
  def pushRadiusClientRequest(metricsActor: ActorRef, dest: RadiusEndpoint, reqCode: Int) = {
    metricsActor ! RadiusClientRequestKey(s"${dest.ipAddress}:${dest.port}", reqCode.toString)
  }
  
  def pushRadiusClientResponse(metricsActor: ActorRef, dest: RadiusEndpoint, reqCode: Int, resCode: Int, requestTimestamp: Long) = {
    metricsActor ! RadiusClientResponseKey(s"${dest.ipAddress}:${dest.port}", reqCode.toString, resCode.toString, crt(requestTimestamp))
  }
  
  def pushRadiusClientTimeout(metricsActor: ActorRef, dest: RadiusEndpoint, reqCode: Int) = {
    metricsActor ! RadiusClientTimeoutKey(s"${dest.ipAddress}:${dest.port}", reqCode.toString)
  }
  
  def pushRadiusClientDrop(metricsActor: ActorRef, ipAddress: String, port: Int) = {
    metricsActor ! RadiusClientDroppedKey(s"${ipAddress}:${port}")
  }
  
  // Handler server
  def pushRadiusHandlerResponse(metricsActor: ActorRef, org: RadiusEndpoint, reqCode: Int, resCode: Int, requestTimestamp: Long) = {
    metricsActor ! RadiusHandlerResponseKey(s"${org.ipAddress}", reqCode.toString, resCode.toString, crt(requestTimestamp))
  }
    
  def pushRadiusHandlerDrop(metricsActor: ActorRef, org: RadiusEndpoint, reqCode: Int) = {
    metricsActor ! RadiusHandlerDroppedKey(s"${org.ipAddress}", reqCode.toString)
  }
  
  // Handler client
  def pushRadiusHandlerRequest(metricsActor: ActorRef, group: String, reqCode: Int, resCode: Int, requestTimestamp: Long) = {
    metricsActor ! RadiusHandlerRequestKey(group, reqCode.toString, resCode.toString, crt(requestTimestamp))
  }
  
  def pushRadiusHandlerRetransmission(metricsActor: ActorRef, group: String, reqCode: Int) = {
    metricsActor ! RadiusHandlerRetransmissionKey(group, reqCode.toString)
  }
  
  def pushRadiusHandlerTimeout(metricsActor: ActorRef, group: String, reqCode: Int) = {
    metricsActor ! RadiusHandlerTimeoutKey(group, reqCode.toString)
  }
  
  /////////////////////////////////////////
  // Http
  /////////////////////////////////////////
  def pushHttpOperation(metricsActor: ActorRef, oh: String, method: String, path: String, resCode: Int) = {
    metricsActor ! HttpOperationKey(oh, method, path, resCode.toString)
  }
}