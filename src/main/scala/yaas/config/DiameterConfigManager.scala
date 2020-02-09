package yaas.config

import org.json4s._

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
  private implicit val jsonFormats: DefaultFormats.type = DefaultFormats
  
  /*
   * Values get the last known configuration
   */

  /**
   * Holds the static <code>DiameterServerConfig</code> object.
   * 
   * Cannot change.
   */
  val diameterConfig: DiameterServerConfig = ConfigManager.getConfigObjectAsJson("diameterServer.json").extract[DiameterServerConfig]

  /**
   * Whether the Diameter stack must start up.
   *
   * It is so if the bindAddress is a valid address or 0.0.0.0
   * @return true if Diameter is enabled
   */
  def isDiameterEnabled: Boolean = diameterConfig.bindAddress.contains(".") && (diameterConfig.bindPort > 0)
  
  /**
   * Holds a map from peer host names to peer configurations
   */
  var diameterPeerConfig: Map[String, DiameterPeerConfig] = Map[String, DiameterPeerConfig]()
  if(isDiameterEnabled) updateDiameterPeerConfig(true)
  
  /**
   * Holds the sequence of routes
   */
  var diameterRouteConfig: Seq[DiameterRouteConfig] = Seq[DiameterRouteConfig]()
  if(isDiameterEnabled) updateDiameterRouteConfig(true)

  /**
   * Reloads the Diameter peer configuration reading it from the JSON configuration object. If <code>withReload</code>
   * is true, forces reading from the origin, which may not be needed if a previous
   * <code>ConfigManager.reloadAllConfigObjects</code> has been invoked
   * @param withReload true to force reading from source
   */
  def updateDiameterPeerConfig(withReload: Boolean): Unit = {
    val jPeers = if(withReload) ConfigManager.reloadConfigObjectAsJson("diameterPeers.json")
                 else ConfigManager.getConfigObjectAsJson("diameterPeers.json")
    diameterPeerConfig = (for {
      peer <- jPeers.extract[Seq[DiameterPeerConfig]]
    } yield peer.diameterHost -> peer).toMap
  }
  
   /**
   * Reloads the Diameter routes configuration reading it from the JSON configuration object. If <code>withReload</code>
   * is true, forces reading from the origin, which may not be needed if a previous
   * <code>ConfigManager.reloadAllConfigObjects</code> has been invoked
   * @param withReload true to force reading from source
   */
  def updateDiameterRouteConfig(withReload: Boolean): Unit = {
    val jRoutes = if(withReload) ConfigManager.reloadConfigObjectAsJson("diameterRoutes.json")
                  else ConfigManager.getConfigObjectAsJson("diameterRoutes.json")
    diameterRouteConfig = jRoutes.extract[Seq[DiameterRouteConfig]]
  }

  /**
   * Gets the first Diameter Peer that conforms to the specification
   * @param remoteIPAddress the address of the remote diameter server
   * @param diameterHost the Origin-Host reported
   * @return the DiameterPeerConfiguration if found, or None
   */
  def findDiameterPeer(remoteIPAddress: String, diameterHost: String): Option[DiameterPeerConfig] = {
    diameterPeerConfig.collectFirst {
      case (_, diameterPeer) if Net.isAddressInNetwork(remoteIPAddress, diameterPeer.originNetwork) && diameterHost == diameterPeer.diameterHost => diameterPeer
    } 
  }

  /**
   * Checks that there is at least one Peer with the specified IP address
   * @param remoteIPAddress the IP address
   * @return true if there is one peer in that range
   */
  def validatePeerIPAddress(remoteIPAddress: String): Boolean = {
    diameterPeerConfig.collectFirst {
      case (_, diameterPeer) if Net.isAddressInNetwork(remoteIPAddress, diameterPeer.originNetwork) => diameterPeer
    } .nonEmpty
  }
}

