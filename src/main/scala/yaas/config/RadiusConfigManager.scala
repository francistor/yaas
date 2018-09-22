package yaas.config

import org.json4s._
import org.json4s.jackson.JsonMethods._

// TODO: Change the name to RadiusProxies, etc.
case class RadiusThisServerConfig(bindAddress: String, authBindPort: Int, acctBindPort: Int, coABindPort: Int, clientBasePort: Int, numClientPorts: Int)
case class RadiusPorts(auth: Int, acct: Int, coA: Int) // TODO: Move to Options
case class RadiusServerConfig(name: String, IPAddress: String, secret: String, ports: RadiusPorts, errorLimit: Int, quarantineTimeMillis: Int)
case class RadiusServerGroupConfig(name: String, servers: IndexedSeq[String], policy: String)
case class RadiusClientConfig(name: String, IPAddress: String, secret: String)

object RadiusConfigManager {
  // For deserialization of Json
  private implicit val formats = DefaultFormats

  // General config
  private var radiusConfig = ConfigManager.getConfigObject("radiusServer.json").extractOrElse[RadiusThisServerConfig](RadiusThisServerConfig("0", 0, 0, 0, 0, 0))
  
  // Radius servers
  private var radiusServers = (for {
    server <- (ConfigManager.getConfigObject("radiusServers.json") \ "servers").extract[List[RadiusServerConfig]]
  } yield (server.name -> server)).toMap
  
  // RadiusServer groups
  private var radiusServerGroups = (for {
    group <- (ConfigManager.getConfigObject("radiusServers.json") \ "serverGroups").extract[List[RadiusServerGroupConfig]]
  } yield (group.name -> group)).toMap
  
  // Radius clients
  private var radiusClients = (for {
    client <- ConfigManager.getConfigObject("radiusClients.json").extract[List[RadiusClientConfig]]
  } yield (client.IPAddress -> client)).toMap
  
  def getRadiusConfig = radiusConfig
  def getRadiusServers = radiusServers
  def getRadiusServerGroups = radiusServerGroups
  def getRadiusClients = radiusClients
}
