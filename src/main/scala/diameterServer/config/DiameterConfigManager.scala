package diameterServer.config

import akka.actor.{ ActorSystem, Actor, ActorRef, Props }
import akka.event.{ Logging, LoggingReceive }

import org.json4s._
import org.json4s.jackson.JsonMethods._

import diameterServer.DiameterRouter._

// This class represents a diameter peer as read from the configuration file
case class DiameterServerConfig(bindAddress: String, bindPort: Int, connectionInterval: Int, diameterHost: String, diameterRealm: String, vendorId: Int, productName: String, firmwareRevision: Int)
case class DiameterPeerConfig(diameterHost: String, IPAddress: String, port: Int, connectionPolicy: String, watchdogIntervalMillis: Int)
case class DiameterRouteConfig(realm: String, applicationId: String, peers: Option[Array[String]], policy: Option[String], handlerObject: Option[String])

// Best practise
object DiameterConfigManager {

  // For deserialization of Json
  implicit val formats = DefaultFormats

  // General config
  private var diameterConfig = ConfigManager.getConfigObject("diameterServer.json").extract[DiameterServerConfig]

  // diameterHost -> DiameterPeerConfig
  private var diameterPeerConfig = (for {
    peer <- ConfigManager.getConfigObject("diameterPeers.json").extract[Seq[DiameterPeerConfig]]
  } yield (peer.diameterHost -> peer)).toMap

  // Read routes and build routing table
  private var diameterRouteConfig = ConfigManager.getConfigObject("diameterRoutes.json").extract[Seq[DiameterRouteConfig]]
  
  def getDiameterConfig = diameterConfig
  def getDiameterPeerConfig = diameterPeerConfig
  def getDiameterRouteConfig = diameterRouteConfig

}

