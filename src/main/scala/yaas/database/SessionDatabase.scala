package yaas.database

import com.typesafe.config._

import org.apache.ignite.scalar.scalar
import org.apache.ignite.scalar.scalar._
import org.apache.ignite.cache.CacheMode._
import org.apache.ignite.cache.query.SqlFieldsQuery
import org.apache.ignite.logger.slf4j.Slf4jLogger

import scala.collection.JavaConversions._

import yaas.coding.RadiusAVP
import yaas.coding.DiameterAVP

import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder
import org.apache.ignite.spi.communication.tcp.TcpCommunicationSpi
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi
import org.apache.ignite.configuration.IgniteConfiguration

case class RadiusSession(
    @ScalarCacheQuerySqlField(index = true) acctSessionId: String, 
    @ScalarCacheQuerySqlField(index = true) ipAddress: String, 
    @ScalarCacheQuerySqlField(index = true) clientId: String,
    @ScalarCacheQuerySqlField(index = true) macAddress: String,
    @ScalarCacheQuerySqlField(index = true) startTimestampUTC: Long,
    @ScalarCacheQuerySqlField avps: List[RadiusAVP[Any]]
)
    
case class DiameterSession(
    @ScalarCacheQuerySqlField(index = true) acctSessionId: String, 
    @ScalarCacheQuerySqlField(index = true) ipAddress: String, 
    @ScalarCacheQuerySqlField(index = true) clientId: String,
    @ScalarCacheQuerySqlField(index = true) macAddress: String,
    @ScalarCacheQuerySqlField(index = true) startTimestampUTC: Long,
    @ScalarCacheQuerySqlField avps: List[DiameterAVP[Any]]
)

object SessionDatabase {
  
  // Configure Ignite client
  val config = ConfigFactory.load().getConfig("aaa.sessionsDatabase")
  
  val role = config.getString("role")
  if(!role.equalsIgnoreCase("client") && !role.equalsIgnoreCase("server") && !role.equalsIgnoreCase("none")) throw new IllegalArgumentException("role is not <client> or <server> or <none>")

  // If configured as "0" or not configured, will not start ignite client
  // 6 characters isorg.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder the minimum for having configured an IP address in the igniteAddresses parameter
  if(!role.equalsIgnoreCase("none")) {
    
    val igniteAddresses = config.getString("igniteAddresses")
    
    val finder = new TcpDiscoveryVmIpFinder
    finder.setAddresses(igniteAddresses.split(",").toList)
    
    val discSpi = new TcpDiscoverySpi
    discSpi.setIpFinder(finder)
    discSpi.setLocalAddress("127.0.0.1")
    discSpi.setLocalPort(47701)
    
    val commSpi = new TcpCommunicationSpi();
    val localIgniteAddress = config.getString("localIgniteAddress").split(":")
    commSpi.setLocalAddress(localIgniteAddress(0))
    commSpi.setLocalPort(localIgniteAddress(1).toInt)
    
    val igniteConfiguration = new IgniteConfiguration
    igniteConfiguration.setCommunicationSpi(commSpi)
    igniteConfiguration.setDiscoverySpi(discSpi)
    igniteConfiguration.setGridLogger(new org.apache.ignite.logger.slf4j.Slf4jLogger)
    
    // Start ignite
    if(role.equalsIgnoreCase("client")) org.apache.ignite.Ignition.setClientMode(true)
    scalar.start(igniteConfiguration)
    
    // Make sure caches exist
    if(cache$("RadiusSessions") == None) createCache$("RadiusSessions", org.apache.ignite.cache.CacheMode.REPLICATED, Seq(classOf[String], classOf[RadiusSession]))
    if(cache$("DiameterSessions") == None) createCache$("DiameterSessions", org.apache.ignite.cache.CacheMode.REPLICATED, Seq(classOf[String], classOf[RadiusSession]))
    
  }
  
  def init() = {
    // Starts ignite if configured so
  }
  
  /**
   * Radius functions
   */
  
  def startRadiusSession(session: RadiusSession) = {
    val radiusSessionsCache = ignite$.getOrCreateCache[String, RadiusSession]("RadiusSessions")
    radiusSessionsCache.put(session.acctSessionId, session)
  }
  
  def stopRadiusSession(session: RadiusSession) = {
    val radiusSessionsCache = ignite$.getOrCreateCache[String, RadiusSession]("RadiusSessions")
    radiusSessionsCache.remove(session.acctSessionId, session)
  }
  
  def findRadiusSessionsByIPAddress(ipAddress: String) = {
    val radiusSessionsCache = ignite$.getOrCreateCache[String, RadiusSession]("RadiusSessions")
    radiusSessionsCache.sql("select * from \"RadiusSessions\".RadiusSession where ipAddress = ?", ipAddress).getAll.map(entry => entry.getValue).toList
  }
  
  def findRadiusSessionsByClientId(clientId: String) = {
    val radiusSessionsCache = ignite$.getOrCreateCache[String, RadiusSession]("RadiusSessions")
    radiusSessionsCache.sql("select * from \"RadiusSessions\".RadiusSession where clientId = ?", clientId).getAll.map(entry => entry.getValue).toList
  }
  
  /**
   * Diameter functions
   */
  
  def startDiameterSession(session: DiameterSession) = {
    val diameterSessionsCache = ignite$.getOrCreateCache[String, DiameterSession]("DiameterSessions")
    diameterSessionsCache.put(session.acctSessionId, session)
  }
  
  def stopDiameterSession(session: DiameterSession) = {
    val diameterSessionsCache = ignite$.getOrCreateCache[String, DiameterSession]("DiameterSessions")
    diameterSessionsCache.remove(session.acctSessionId, session)
  }
  
  def findDiameterSessionsByIPAddress(ipAddress: String) = {
    val diameterSessionsCache = ignite$.getOrCreateCache[String, DiameterSession]("RadiusSessions")
    diameterSessionsCache.sql("select * from \"DiameterSessions\".DiameterSession where ipAddress = ?", ipAddress).getAll.map(entry => entry.getValue).toList
  }
  
  def findDiameterSessionsByClientId(clientId: String) = {
    val diameterSessionsCache = ignite$.getOrCreateCache[String, DiameterSession]("RadiusSessions")
    diameterSessionsCache.sql("select * from \"DiameterSessions\".DiameterSession where clientId = ?", clientId).getAll.map(entry => entry.getValue).toList
  }
  
}