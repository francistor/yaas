package yaas.server

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill }
import akka.event.{ Logging, LoggingReceive }
import akka.io.{IO, Udp}

import java.net.InetSocketAddress

import yaas.config.RadiusConfigManager
import yaas.coding.radius.RadiusPacket
import yaas.server.RadiusActorMessages._

object RadiusClientSocket {
    def props(bindIPAddress: String, bindPort: Int) = Props(new RadiusClientSocket(bindIPAddress, bindPort))
}

class RadiusClientSocket(bindIPAddress: String, bindPort: Int) extends Actor with ActorLogging {
  
  import RadiusClientSocket._
  
  import context.system
  
  // TODO: Handle bind error
  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(bindIPAddress, bindPort))
  
  def receive = {
    case Udp.Bound(localAddress: InetSocketAddress) =>
      log.info(s"Client socket bound to $localAddress")
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
            val origin = RadiusEndpoint(remoteIPAddress, remotePort, radiusClientConfig.secret)
            context.parent ! RadiusClientSocketResponse(data, origin, bindPort)
          } catch {
            case e: Exception =>
              log.warning(e.getMessage)
          }
          
        case None =>
          log.warning(s"Discarding packet from $remoteIPAddress")
      }
      
    case RadiusClientSocketRequest(bytes, destination) =>
      log.debug(s"Sending radius request to $destination")
      val request = bytes
      udpEndPoint ! Udp.Send(request, new InetSocketAddress(destination.ipAddress, destination.port))
  }
}