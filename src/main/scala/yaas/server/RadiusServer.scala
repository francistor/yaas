package yaas.server

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill }
import akka.event.{ Logging, LoggingReceive }
import akka.io.{IO, Udp}

import java.net.InetSocketAddress

import yaas.coding._
import yaas.config.RadiusConfigManager
import yaas.server.RadiusActorMessages._
import yaas.instrumentation.StatOps

// This class implements radius server basic functions

object RadiusServer {
  def props(bindIPAddress: String, bindPort: Int, statsServer: ActorRef) = Props(new RadiusServer(bindIPAddress, bindPort, statsServer))
}

class RadiusServer(bindIPAddress: String, bindPort: Int, statsServer: ActorRef) extends Actor with ActorLogging {
  
  import context.system
  
  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(bindIPAddress, bindPort))
  
  // TODO: Catch errors
  
  def receive = {
    case Udp.Bound(localAddress: InetSocketAddress) =>
      log.info(s"Server socket bound to $localAddress")
      context.become(ready(sender))
  }
  
  def ready(udpEndPoint: ActorRef): Receive = {
    case Udp.Received(data, remote) =>
      // Check origin
      val remoteIPAddress = remote.getAddress().getHostAddress
      val remotePort = remote.getPort
      val radiusClient = RadiusConfigManager.findRadiusClient(remoteIPAddress)
      log.debug(s"Radius datagram from $remoteIPAddress")
      
      radiusClient match {
        case Some(radiusClientConfig) =>
          try {
            val origin = RadiusActorMessages.RadiusEndpoint(remoteIPAddress, remotePort)
            val requestPacket = RadiusPacket(data, None, radiusClientConfig.secret)
            context.parent ! RadiusActorMessages.RadiusServerRequest(requestPacket, self, origin, radiusClientConfig.secret)
            StatOps.pushRadiusServerRequest(statsServer, origin, requestPacket.code)
          } catch {
            case e: Exception =>
              log.warning(s"Error decoding packet from $remoteIPAddress")
          }
          
        case None =>
          log.warning(s"Discarding packet from $remoteIPAddress")
          StatOps.pushRadiusServerDrop(statsServer: ActorRef, remoteIPAddress, remotePort)
      }
      
    case RadiusServerResponse(responsePacket, origin, secret) =>
      log.debug(s"Sending radius response to $origin")
      val responseBytes = responsePacket.getResponseBytes(secret)
      udpEndPoint ! Udp.Send(responseBytes, new InetSocketAddress(origin.ipAddress, origin.port))
      StatOps.pushRadiusServerResponse(statsServer, origin, responsePacket.code)
  }
}