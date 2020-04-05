package yaas.server

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.io.{IO, Udp}
import yaas.server.RadiusActorMessages._

object RadiusClientSocket {
    def props(bindIPAddress: String, bindPort: Int): Props = Props(new RadiusClientSocket(bindIPAddress, bindPort))
}

/**
 * Implements a sender of Radius requests using a specific address and port
 * @param bindIPAddress the IP address to which the radius client binds
 * @param bindPort the origin client Port
 */
class RadiusClientSocket(bindIPAddress: String, bindPort: Int) extends Actor with ActorLogging {
  
  import context.system
  
  // TODO: Handle bind error
  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(bindIPAddress, bindPort))
  
  def receive: Receive = {
    case Udp.Bound(localAddress: InetSocketAddress) =>
      log.info(s"Client socket bound to $localAddress")
      context.become(ready(sender))

    case Udp.CommandFailed(_) =>
      log.error("Could not bind radius client to {}:{}", bindIPAddress, bindPort)
      System.exit(-1)
  }
  
  def ready(udpEndPoint: ActorRef): Receive = {
    case Udp.Received(data, remote) =>
      val radiusEndpoint = RadiusEndpoint(remote.getAddress.getHostAddress, remote.getPort)
      context.parent ! RadiusClientSocketResponse(data, radiusEndpoint, bindPort)
      
    case RadiusClientSocketRequest(bytes, destination) =>
      udpEndPoint ! Udp.Send(bytes, new InetSocketAddress(destination.ipAddress, destination.port))
  }
}