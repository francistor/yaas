package yaas.config

import org.json4s._
import org.json4s.jackson.JsonMethods._

import yaas.util.Net

/**
 * Represents the basic properties of the Radius Server, as defined in the <code>radiusServer.json</code> configuration object
 */
case class RadiusThisServerConfig(bindAddress: String, authBindPort: Int, acctBindPort: Int, coABindPort: Int, clientBasePort: Int, numClientPorts: Int)

/**
 * Placeholder for the ports for authentication, accounting and CoA
 */
case class RadiusPorts(auth: Int, acct: Int, coA: Int) // TODO: Move to Options

/**
 * Holds the remote radius server configuration properties
 */
case class RadiusServerConfig(name: String, IPAddress: String, secret: String, ports: RadiusPorts, errorLimit: Int, quarantineTimeMillis: Int)

/**
 * Defines a redundant group of radius servers.
 * 
 * <code>policy</code> may be <code>fixed</code> or <code>random</code>
 */
case class RadiusServerGroupConfig(name: String, servers: IndexedSeq[String], policy: String)

/**
 * Placeholder for the radius client configuration
 */
case class RadiusClientConfig(name: String, IPAddress: String, secret: String)

/**
 * Placeholder for the Radius configuration.
 * 
 * The public values give access to the last retrieved configuration values.
 * If a refreshed configuration is required, use the <code>get</code> methods, after calling <code>ConfigManager.reloadXX</code>
 */
object RadiusConfigManager {
  // For deserialization of Json
  private implicit val formats = DefaultFormats

   /**
   * Holds the static <code>RadiusThisServerConfig</code> object.
   * 
   * Cannot change.
   */
  val radiusConfig = ConfigManager.getConfigObject("radiusServer.json").extractOrElse[RadiusThisServerConfig](RadiusThisServerConfig("0", 0, 0, 0, 0, 0))
  
  /**
   * Holds a map from radius server names to radius server configuration
   */
  var radiusServers = Map[String, RadiusServerConfig]()
  getRadiusServers
  
  /**
   * Holds a map from radius server IP addresses to radius server configuration
   */
  var radiusServerIPAddresses = Map[String, RadiusServerConfig]()
  getRadiusServerIPAddresses
  
  /**
   * Holds a map from radius server group name to its configuration
   */
  var radiusServerGroups = Map[String, RadiusServerGroupConfig]()
  getRadiusServerGroups
  
  /**
   * Holds a map of IP addresses to radius client configuration
   */
  var radiusClients = Map[String, RadiusClientConfig]()
  getRadiusClients
  
  /**
   * Obtains the general radius configuration reading it from the JSON configuration object.
   * 
   * Since this configuration cannot change, the value is fixed on startup
   */
  def getRadiusConfig = radiusConfig
  
  /**
   * Obtains the Radius server map configuration reading it from the JSON configuration object.
   * 
   * If an updated version is required, make sure to call <code>ConfigManager.refresh</code> before invoking 
   */
  def getRadiusServers = {
    radiusServers =  (for {
      server <- (ConfigManager.getConfigObject("radiusServers.json") \ "servers").extract[List[RadiusServerConfig]]
    } yield (server.name -> server)).toMap
    radiusServers
  }
  
  /**
   * Obtains the Radius IP address to configurations map reading it from the JSON configuration object.
   * 
   * If an updated version is required, make sure to call <code>ConfigManager.refresh</code> before invoking 
   */
  def getRadiusServerIPAddresses = {
    radiusServerIPAddresses = for {
      (serverName, serverConfig) <- radiusServers
    } yield (serverConfig.IPAddress, serverConfig)
    radiusServerIPAddresses
  }
  
   /**
   * Obtains the Radius server group configuration map reading it from the JSON configuration object.
   * 
   * If an updated version is required, make sure to call <code>ConfigManager.refresh</code> before invoking 
   */
  def getRadiusServerGroups = {
    radiusServerGroups = (for {
      group <- (ConfigManager.getConfigObject("radiusServers.json") \ "serverGroups").extract[List[RadiusServerGroupConfig]]
    } yield (group.name -> group)).toMap
    radiusServerGroups
  }
  
   /**
   * Obtains the Radius client map configuration reading it from the JSON configuration object.
   * 
   * If an updated version is required, make sure to call <code>ConfigManager.refresh</code> before invoking 
   */
  def getRadiusClients = {
    radiusClients = (for {
      client <- ConfigManager.getConfigObject("radiusClients.json").extract[List[RadiusClientConfig]]
    } yield (client.IPAddress -> client)).toMap
    radiusClients
  }
  
  /**
   * Gets the first radius client that conforms to the network specification
   */
  def findRadiusClient(remoteIPAddress: String) = {
    // First try exact match. For performance reasons
    val exactMatchedClient = radiusClients.get(remoteIPAddress)
    
    // Then try network match
    if(exactMatchedClient.isEmpty) radiusClients.collectFirst{
      case (name, radiusClient) if(radiusClient.IPAddress.contains("/") && Net.isAddressInNetwork(remoteIPAddress, radiusClient.IPAddress)) => radiusClient  
    } else exactMatchedClient
  }
}
