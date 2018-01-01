package diameterServer.config

import akka.actor.{ ActorSystem, Actor, ActorRef, Props }
import akka.event.{ Logging, LoggingReceive }

import org.json4s._
import org.json4s.jackson.JsonMethods._

import diameterServer.DiameterRouter._

// This class represents a diameter peer as read from the configuration file
case class DiameterServerConfig(bindAddress: String, bindPort: Int)
case class DiameterPeerConfig(diameterHost: String, IPAddress: String, port: Int, connectionPolicy: String, watchdogIntervalMillis: Int)
case class DiameterHandlerConfig(applicationName: String, handlerObject: String)
case class DiameterRouteConfig(realm: String, applicationId: String, peers: Option[Array[String]], policy: Option[String], handlerObject: Option[String])

// Best practise
object DiameterConfigManager {
  def props() = Props(new DiameterConfigManager())
}

class DiameterConfigManager extends Actor {
  
  // Initialize logger
  val logger = Logging.getLogger(context.system, this)

  // For deserialization of Json
  implicit val formats = DefaultFormats

  // General config
  var diameterConfig = ConfigManager.getConfigObject("diameterServer.json").extract[DiameterServerConfig]

  // diameterHost -> DiameterPeerConfig
  var peerHostConfig = (for {
    peer <- ConfigManager.getConfigObject("diameterPeers.json").extract[Seq[DiameterPeerConfig]]
  } yield (peer.diameterHost -> peer)).toMap

  // Read routes and build routing table
  var diameterRouteConfig = ConfigManager.getConfigObject("diameterRoutes.json").extract[Seq[DiameterRouteConfig]]
  for (diameterRoute <- diameterRouteConfig) logger.debug("Added route: {}", diameterRoute)
  
  override def preStart(): Unit = {
    context.parent ! PeerConfigMsg(peerHostConfig)
    context.parent ! RouteConfigMsg(diameterRouteConfig)
    // This last one will start the server
    context.parent ! DiameterServerConfigMsg(diameterConfig.bindAddress, diameterConfig.bindPort)
  }
  
  def receive  = LoggingReceive {
		case any: Any =>
		  logger.error("Unknown message %s", any)
		  Nil
	}

}