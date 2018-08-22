package yaas.config

import org.json4s._
import org.json4s.jackson.JsonMethods._

// Global diameter properties
case class DiameterServerConfig(bindAddress: String, bindPort: Int, connectionInterval: Int, diameterHost: String, diameterRealm: String, vendorId: Int, productName: String, firmwareRevision: Int)

// This class represents a diameter peer as read from the configuration file
case class DiameterPeerConfig(diameterHost: String, IPAddress: String, port: Int, connectionPolicy: String, watchdogIntervalMillis: Int)

// This class represents a diameter route
case class DiameterRouteConfig(realm: String, applicationId: String, peers: Option[List[String]], policy: Option[String], handler: Option[String])

object DiameterConfigManager {

  // For deserialization of Json
  private implicit val formats = DefaultFormats

  // General config
  private var diameterConfig = ConfigManager.getConfigObject("diameterServer.json").extract[DiameterServerConfig]

  // Peers
  private var diameterPeerConfig = (for {
    peer <- ConfigManager.getConfigObject("diameterPeers.json").extract[Seq[DiameterPeerConfig]]
  } yield (peer.diameterHost -> peer)).toMap

  // Routes
  private var diameterRouteConfig = ConfigManager.getConfigObject("diameterRoutes.json").extract[Seq[DiameterRouteConfig]]
  
  def getDiameterConfig = diameterConfig
  def getDiameterPeerConfig = diameterPeerConfig
  def getDiameterRouteConfig = diameterRouteConfig
}

