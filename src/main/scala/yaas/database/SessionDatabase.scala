package yaas.database

import com.typesafe.config._

import scala.collection.JavaConversions._

import yaas.coding.RadiusAVP
import yaas.coding.DiameterAVP

import org.apache.ignite.scalar.scalar
import org.apache.ignite.scalar.scalar._
import org.apache.ignite.cache.CacheMode._
import org.apache.ignite.cache.query.SqlFieldsQuery
import org.apache.ignite.logger.slf4j.Slf4jLogger
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder
import org.apache.ignite.spi.communication.tcp.TcpCommunicationSpi
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi
import org.apache.ignite.configuration.IgniteConfiguration
import org.apache.ignite.configuration.DataStorageConfiguration
import org.apache.ignite.configuration.DataRegionConfiguration
import org.apache.ignite.cache.CacheMode._

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

/**
 * Builder for JSession instances from Session instances.
 */
object JSession {
  def apply(session: Session) = new JSession(
      session.acctSessionId, 
      session.ipAddress, 
      session.clientId, 
      session.macAddress, 
      session.startTimestampUTC, 
      parse(session.jData))
}

/**
 * Represents a Radius/Diameter Session as used by the handlers.
 * jData is a JSON object that has an arbitrary structure
 */
class JSession(
    val acctSessionId: String, 
    val ipAddress: String, 
    val clientId: String,
    val macAddress: String,
    val startTimestampUTC: Long,
    val jData: JValue = JObject()
) {
  def toSession = {
    Session(acctSessionId, ipAddress, clientId, macAddress, startTimestampUTC, compact(render(jData)))
  }
}

// Serializers for List of attributes. We sometimes want them not as JSON arrays, but as property sets
class JSessionSerializer extends CustomSerializer[JSession](implicit jsonFormats => (
  {
    case jv: JValue =>
      new JSession(
          (jv \ "acctSessionId").extract[String],
          (jv \ "ipAddress").extract[String],
          (jv \ "clientId").extract[String],
          (jv \ "macAddress").extract[String],
          (jv \ "startTimestampUTC").extract[Long],
          (jv \ "data")
      )
  },
  {
    case jSession: JSession =>
      ("acctSessionId" -> jSession.acctSessionId) ~
      ("ipAddress" -> jSession.ipAddress) ~
      ("clientId" -> jSession.clientId) ~
      ("macAddress" -> jSession.macAddress) ~
      ("startTimestampUTC" -> jSession.startTimestampUTC) ~
      ("data" -> jSession.jData)
  }
))

/**
 * Represents a Radius/Diameter Session as stored in the in-memory sessions database.
 * jData is stringified JSON
 */
case class Session(
    @ScalarCacheQuerySqlField(index = true) acctSessionId: String, 
    @ScalarCacheQuerySqlField(index = true) ipAddress: String, 
    @ScalarCacheQuerySqlField(index = true) clientId: String,
    @ScalarCacheQuerySqlField(index = true) macAddress: String,
    @ScalarCacheQuerySqlField startTimestampUTC: Long,
    jData: String
)
    

/**
 * This object starts an iginte instance in client or server mode depending on configuration (aaa.sessionsDatabase), and
 * provides helper functions to interact with the caches
 */
object SessionDatabase {
  
  val log = LoggerFactory.getLogger(SessionDatabase.getClass)
  
  // Configure Ignite client
  val config = ConfigFactory.load().getConfig("aaa.sessionsDatabase")
  
  val role = config.getString("role")
  if(!role.equalsIgnoreCase("client") && !role.equalsIgnoreCase("server") && !role.equalsIgnoreCase("none")) throw new IllegalArgumentException("role is not <client> or <server> or <none>")

  // If configured as "0" or not configured, will not start ignite client
  // 6 characters isorg.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder the minimum for having configured an IP address in the igniteAddresses parameter
  if(!role.equalsIgnoreCase("none")) {
    
    val igniteAddresses = config.getString("igniteAddresses")
    val localIgniteAddress = config.getString("localIgniteAddress").split(":")
    val memoryMegabytes = config.getLong("memoryMegabytes")
    
    val dsConfiguration = new DataStorageConfiguration
    val dsRegionConfiguration = new DataRegionConfiguration
    dsRegionConfiguration.setName("custom")
    dsRegionConfiguration.setInitialSize(memoryMegabytes * 1024L * 1024L)
    dsRegionConfiguration.setMaxSize(memoryMegabytes * 1024L * 1024L)
    dsConfiguration.setDefaultDataRegionConfiguration(dsRegionConfiguration)

    val finder = new TcpDiscoveryVmIpFinder
    finder.setAddresses(igniteAddresses.split(",").toList)
    
    val discSpi = new TcpDiscoverySpi
    discSpi.setIpFinder(finder)
    discSpi.setLocalAddress(localIgniteAddress(0))
    discSpi.setLocalPort(localIgniteAddress(1).toInt)
    
    val commSpi = new TcpCommunicationSpi();
    
    val igniteConfiguration = new IgniteConfiguration
    igniteConfiguration.setCommunicationSpi(commSpi)
    igniteConfiguration.setDiscoverySpi(discSpi)
    igniteConfiguration.setGridLogger(new org.apache.ignite.logger.slf4j.Slf4jLogger)
    igniteConfiguration.setDataStorageConfiguration(dsConfiguration)
    
    // Start ignite
    if(role.equalsIgnoreCase("client")){
      org.apache.ignite.Ignition.setClientMode(true)
      log.info("Node is a database client")
    } else log.info("Node is a database server")
    
    scalar.start(igniteConfiguration)
    
    // Make sure cache exist
    if(cache$("SESSIONS") == None) createCache$("SESSIONS", REPLICATED, Seq(classOf[String], classOf[Session]))
  }
  
  def init() = {
    // Starts ignite if configured so
  }
  
  def putSession(jSession: JSession) = {
    val sessionsCache = ignite$.getOrCreateCache[String, Session]("SESSIONS")
    sessionsCache.put(jSession.acctSessionId, jSession.toSession)
  }
  
  def removeSession(acctSessionId: String) = {
    val sessionsCache = ignite$.getOrCreateCache[String, Session]("SESSIONS")
    sessionsCache.remove(acctSessionId)
  }
  
  def findSessionsByIPAddress(ipAddress: String) = {
    val sessionsCache = ignite$.getOrCreateCache[String, Session]("SESSIONS")
    sessionsCache.sql("select * from \"SESSIONS\".Session where ipAddress = ?", ipAddress).getAll.map(entry => JSession(entry.getValue)).toList
  }
  
  def findSessionsByClientId(clientId: String) = {
    val sessionsCache = ignite$.getOrCreateCache[String, Session]("SESSIONS")
    sessionsCache.sql("select * from \"SESSIONS\".Session where clientId = ?", clientId).getAll.map(entry => JSession(entry.getValue)).toList
  }
  
  def findSessionsByMACAddress(macAddress: String) = {
    val sessionsCache = ignite$.getOrCreateCache[String, Session]("SESSIONS")
    sessionsCache.sql("select * from \"SESSIONS\".Session where MACAddress = ?", macAddress).getAll.map(entry => JSession(entry.getValue)).toList
  }
  
  def findSessionsByAcctSessionId(acctSessionId: String) = {
    val sessionsCache = ignite$.getOrCreateCache[String, Session]("SESSIONS")
    sessionsCache.sql("select * from \"SESSIONS\".Session where acctSessionId = ?", acctSessionId).getAll.map(entry => JSession(entry.getValue)).toList
  }
  
}