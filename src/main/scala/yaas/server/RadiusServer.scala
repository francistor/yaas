package yaas.server

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.io.{IO, Udp}
import yaas.coding._
import yaas.config.RadiusConfigManager
import yaas.instrumentation.MetricsOps
import yaas.server.RadiusActorMessages._

object RadiusServer {
  def props(bindIPAddress: String, bindPort: Int, statsServer: ActorRef): Props = Props(new RadiusServer(bindIPAddress, bindPort, statsServer))
}

/**
 * Implements the Radius Server socket and
 * @param bindIPAddress the IP address to bind the server socket
 * @param bindPort the port number to bind the server socket
 * @param statsServer the Actor where to send the stats events
 */
class RadiusServer(bindIPAddress: String, bindPort: Int, statsServer: ActorRef) extends Actor with ActorLogging {
  
  import context.system
  
  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(bindIPAddress, bindPort))
  
  // TODO: Catch errors
  
  def receive: Receive = {
    case Udp.Bound(localAddress: InetSocketAddress) =>
      log.info(s"Server socket bound to $localAddress")
      context.become(ready(sender))
    case Udp.CommandFailed(command) =>
      log.error("Could not bind to {}:{}", bindIPAddress, bindPort)
      System.exit(-1)
  }
  
  def ready(udpEndPoint: ActorRef): Receive = {

    /**
     * Request
     */
    case Udp.Received(data, remote) =>
      // Check origin
      val remoteIPAddress = remote.getAddress.getHostAddress
      val remotePort = remote.getPort
      val radiusClient = RadiusConfigManager.findRadiusClient(remoteIPAddress)
      log.debug(s"Radius datagram from $remoteIPAddress")
      
      radiusClient match {
        case Some(radiusClientConfig) =>
          try {
            val origin = RadiusActorMessages.RadiusEndpoint(remoteIPAddress, remotePort)
            val requestPacket = RadiusPacket(data, None, radiusClientConfig.secret)
            context.parent ! RadiusActorMessages.RadiusServerRequest(requestPacket, self, origin, radiusClientConfig.secret)
            MetricsOps.pushRadiusServerRequest(statsServer, origin, requestPacket.code)
            if(log.isDebugEnabled) log.debug(s">> Received radius request $requestPacket")
          } catch {
            case e: Exception =>
              log.warning(s"Error decoding packet from $remoteIPAddress :{}", e.getMessage)
          }
          
        case None =>
          log.warning(s"Discarding packet from $remoteIPAddress")
          MetricsOps.pushRadiusServerDrop(statsServer: ActorRef, remoteIPAddress, remotePort)
      }

    /**
     * Reply
     */
    case RadiusServerResponse(responsePacket, origin, secret) =>
      log.debug(s"Sending radius response to $origin")
      val responseBytes = responsePacket.getResponseBytes(secret)
      udpEndPoint ! Udp.Send(responseBytes, new InetSocketAddress(origin.ipAddress, origin.port))
      MetricsOps.pushRadiusServerResponse(statsServer, origin, responsePacket.code)
      if(log.isDebugEnabled) log.debug(s"<< Sending radius response $responsePacket")
  }
}