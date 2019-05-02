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
  
  def isRadiusServerEnabled = radiusConfig.bindAddress.contains(".")
  def isRadiusClientEnabled = radiusConfig.clientBasePort > 0
  
  /**
   * Holds a map from radius server names to radius server configuration
   */
  var radiusServers = Map[String, RadiusServerConfig]()
  
  /**
   * Holds a map from radius server IP addresses to radius server configuration
   */
  var radiusServerIPAddresses = Map[String, RadiusServerConfig]() 
  
  /**
   * Holds a map from radius server group name to its configuration
   */
  var radiusServerGroups = Map[String, RadiusServerGroupConfig]()
  
  if(isRadiusClientEnabled) updateRadiusServers
  
  /**
   * Holds a map of IP addresses to radius client configuration
   */
  var radiusClients = Map[String, RadiusClientConfig]()
  if(isRadiusServerEnabled) updateRadiusClients
  
  /**
   * Reloads the Radius server map configuration reading it from the JSON configuration object.
   * 
   */
  def updateRadiusServers = {
    radiusServers =  (for {
      server <- (ConfigManager.reloadConfigObject("radiusServers.json") \ "servers").extract[List[RadiusServerConfig]]
    } yield (server.name -> server)).toMap
    
    radiusServerIPAddresses = for {
      (serverName, serverConfig) <- radiusServers
    } yield (serverConfig.IPAddress, serverConfig)
    
    radiusServerGroups = (for {
      group <- (ConfigManager.getConfigObject("radiusServers.json") \ "serverGroups").extract[List[RadiusServerGroupConfig]]
    } yield (group.name -> group)).toMap
  }
  
   /**
   * Reloads the Radius client map configuration reading it from the JSON configuration object. 
   */
  def updateRadiusClients = {
    radiusClients = (for {
      client <- ConfigManager.getConfigObject("radiusClients.json").extract[List[RadiusClientConfig]]
    } yield (client.IPAddress -> client)).toMap
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
