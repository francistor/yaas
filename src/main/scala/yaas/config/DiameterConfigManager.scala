package yaas.config

import org.json4s._
import org.json4s.jackson.JsonMethods._

import yaas.util.Net

/**
 * Represents the basic properties of the Diameter Server, as defined in the <code>diameterServer.json</code> configuration object
 */
case class DiameterServerConfig(bindAddress: String, bindPort: Int, peerCheckTimeSeconds: Int, diameterHost: String, diameterRealm: String, vendorId: Int, productName: String, firmwareRevision: Int)

/**
 * Represents a diameter peer as read from the <code>diameterPeers.json</code> configuration object
 */
case class DiameterPeerConfig(diameterHost: String, IPAddress: String, port: Int, connectionPolicy: String, watchdogIntervalMillis: Int, originNetwork: String)

/**
 * Represents a diameter route as read from the <code>diameterRoutes.json</code> configuration object.
 * 
 * realm may be "*".
 * applicationId may be "*".
 * 
 * If the action is to treat locally, then <code>peers</code> is empty and <code>handler</code> has a value.
 * If the action is to forward to another diameter server, then <code>handler</code> is empty and <code>peers</code> has a value.
 */
case class DiameterRouteConfig(realm: String, applicationId: String, peers: Option[List[String]], policy: Option[String], handler: Option[String])

/**
 * Placeholder for the Diameter configuration.
 * 
 * The values <code>diameterPeerConfig</code> and <code>diameterRouteConfig</code> give access to the last retrieved configuration values.
 * If a refreshed configuration is required, use the <code>get</code> methods, after calling <code>ConfigManager.reloadXX</code>
 */
object DiameterConfigManager {

  // For deserialization of Json
  private implicit val jsonFormats = DefaultFormats
  
  /*
   * Values retreive the last known configuration 
   */

  /**
   * Holds the static <code>DiameterServerConfig</code> object.
   * 
   * Cannot change.
   */
  val diameterConfig = ConfigManager.getConfigObject("diameterServer.json").extract[DiameterServerConfig]
  
  def isDiameterEnabled = diameterConfig.bindAddress.contains(".") && (diameterConfig.bindPort > 0)
  
  /**
   * Holds a map from peer host names to peer configurations
   */
  var diameterPeerConfig = Map[String, DiameterPeerConfig]()
  if(isDiameterEnabled) updateDiameterPeerConfig
  
  /**
   * Holds the sequence of routes
   */
  var diameterRouteConfig = Seq[DiameterRouteConfig]()
  if(isDiameterEnabled) updateDiameterRouteConfig
  
  /**
   * Reloads the Diameter peer configuration reading it from the JSON configuration object.
   */
  def updateDiameterPeerConfig = {
    diameterPeerConfig = (for {
      peer <- ConfigManager.reloadConfigObject("diameterPeers.json").extract[Seq[DiameterPeerConfig]]
    } yield (peer.diameterHost -> peer)).toMap
  }
  
   /**
   * Reloads the Diameter routes configuration reading it from the JSON configuration object. 
   */
  def updateDiameterRouteConfig = {
    diameterRouteConfig = ConfigManager.reloadConfigObject("diameterRoutes.json").extract[Seq[DiameterRouteConfig]]
  }
  
  /**
   * Gets the first Diameter Peer that conforms to the specification
   */
  def findDiameterPeer(remoteIPAddress: String, diameterHost: String) = {
    diameterPeerConfig.collectFirst {
      case (name, diameterPeer) if(Net.isAddressInNetwork(remoteIPAddress, diameterPeer.originNetwork) && diameterHost == diameterPeer.diameterHost) => diameterPeer
    } 
  }
}

