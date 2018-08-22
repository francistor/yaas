package yaas.server

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill }
import akka.event.{ Logging, LoggingReceive }
import akka.io.{IO, Udp}

import java.net.InetSocketAddress

import yaas.coding.radius._
import yaas.config.RadiusConfigManager
import yaas.server.RadiusActorMessages._

// This class implements radius server basic functions

object RadiusServer {
  def props(bindIPAddress: String, bindPort: Int) = Props(new RadiusServer(bindIPAddress, bindPort))
}

class RadiusServer(bindIPAddress: String, bindPort: Int) extends Actor with ActorLogging {
  
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
      val radiusClient = RadiusConfigManager.getRadiusClients.get(remoteIPAddress)
      
      radiusClient match {
        case Some(radiusClientConfig) =>
          try {
            val origin = RadiusActorMessages.RadiusEndpoint(remoteIPAddress, remotePort, radiusClientConfig.secret)
            context.parent ! RadiusActorMessages.RadiusServerRequest(RadiusPacket(data, origin.secret), self, origin)
          } catch {
            case e: Exception =>
              log.warning(s"Error decoding packet from $remoteIPAddress")
          }
          
        case None =>
          log.warning(s"Discarding packet from $remoteIPAddress")
      }
      
    case RadiusServerResponse(radiusPacket, origin) =>
      log.debug(s"Sending radius response to $origin")
      val response = radiusPacket.getResponseBytes(origin.secret)
      udpEndPoint ! Udp.Send(response, new InetSocketAddress(origin.ipAddress, origin.port))
  }
}