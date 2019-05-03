package yaas.server

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill }
import akka.event.{ Logging, LoggingReceive }
import akka.io.{IO, Udp}

import java.net.InetSocketAddress

import yaas.config.RadiusConfigManager
import yaas.coding.RadiusPacket
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
      val radiusEndpoint = RadiusEndpoint(remote.getAddress().getHostAddress, remote.getPort)
      context.parent ! RadiusClientSocketResponse(data, radiusEndpoint, bindPort)
      
    case RadiusClientSocketRequest(bytes, destination) =>
      log.debug(s"Sending radius request to $destination")
      val request = bytes
      udpEndPoint ! Udp.Send(request, new InetSocketAddress(destination.ipAddress, destination.port))
  }
}