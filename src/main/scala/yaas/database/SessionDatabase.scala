package yaas.database

import java.lang

import com.typesafe.config._
import javax.cache.processor.{EntryProcessor, MutableEntry}
import org.apache.ignite.cache.CacheMode._
import org.apache.ignite.cache.query.annotations.QuerySqlField
import org.apache.ignite.cache.query.SqlFieldsQuery
import org.apache.ignite.configuration.{DataRegionConfiguration, DataStorageConfiguration, IgniteConfiguration}
import org.apache.ignite.lang.IgniteFuture
import org.apache.ignite.{Ignite, IgniteCache, Ignition}
import org.apache.ignite.spi.communication.tcp.TcpCommunicationSpi
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi
import org.apache.ignite.spi.discovery.tcp.ipfinder.kubernetes.TcpDiscoveryKubernetesIpFinder
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder
import org.json4s.JsonDSL._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.slf4j.{Logger, LoggerFactory}

import scala.annotation.meta.field
import scala.collection.JavaConverters._
import scala.util.control.Breaks

/**
 * Represents a Radius/Diameter Session as stored in the in-memory sessions database.
 * jData is stringified JSON to store additional abritrary non-searchable parameters
 * 
 * Radius and Diameter Handlers use a JSession object, which is internally converted to Session.
 * 
 * For each session, a list of "groups" may be specified (order is important), for
 * counting purposes (e.g. number of sessions for a given service-name)
 */

// TODO: Should use Options
class Session(
    @(QuerySqlField @field)(index = true) val acctSessionId: String,
    @(QuerySqlField @field)(index = true) val ipAddress: String,
    @(QuerySqlField @field)(index = true) val clientId: String,
    @(QuerySqlField @field)(index = true) val macAddress: String,
    @(QuerySqlField @field)(index = true) val groups: String,
    @(QuerySqlField @field) val startTimestampUTC: Long,
    @(QuerySqlField @field) val lastUpdatedTimestampUTC: Long,
    val jData: String
) {
  /**
   * Creates a new Session instance with the updated jData and a lastUpdatedTimestamp with the current time
   * @param updatedJData the new jData
   * @return new JSession instance
   */
  def copyUpdated(updatedJData: JValue): Session = new Session(acctSessionId, ipAddress, clientId, macAddress, groups, startTimestampUTC, System.currentTimeMillis(), compact(updatedJData))

  /**
   * Creates a new Session instance in which the jData field is merged wit and a lastUpdatedTimestamp with the current time
   * @param toMergeJData the jData to be merged. The old fields remain the same and the new ones are replaced
   * @return new JSession instance
   */
  def copyMerged(toMergeJData: JValue): Session = {
    new Session(acctSessionId, ipAddress, clientId, macAddress, groups, startTimestampUTC, System.currentTimeMillis(), compact(parse(jData).merge(toMergeJData)))
  }

}

/**
 * Builder for JSession instances from Session instances.
 */
object JSession {
  def apply(session: Session) = new JSession(
      session.acctSessionId, 
      session.ipAddress, 
      session.clientId, 
      session.macAddress, 
      session.groups.split(",").toList,
      session.startTimestampUTC, 
      session.lastUpdatedTimestampUTC,
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
    val groups: List[String],
    val startTimestampUTC: Long,
    val lastUpdatedTimestampUTC: Long,
    val jData: JValue = JObject()
) {

  /**
   * Conversion to Session class instance
   * @return
   */
  def toSession: Session = {
    new Session(acctSessionId, ipAddress, clientId, macAddress, groups.mkString(","), startTimestampUTC, lastUpdatedTimestampUTC, compact(render(jData)))
  }

  /**
   * Creates a new JSession instance with the updated jData and a lastUpdatedTimestamp with the current time
   * @param updatedJData the new jData
   * @return new JSession instance
   */
  def copyUpdated(updatedJData: JValue) = new JSession(acctSessionId, ipAddress, clientId, macAddress, groups, startTimestampUTC, System.currentTimeMillis(), compact(updatedJData))

  /**
   * Creates a new JSession instance in which the jData field is merged wit and a lastUpdatedTimestamp with the current time
   * @param toMergeJData the jData to be merged. The old fields remain the same and the new ones are replaced
   * @return new JSession instance
   */
  def copyMerged(toMergeJData: JValue) = new JSession(acctSessionId, ipAddress, clientId, macAddress, groups, startTimestampUTC, System.currentTimeMillis(), compact(jData.merge(toMergeJData)))
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
          (jv \ "groups").extract[List[String]],
          (jv \ "startTimestampUTC").extract[Long],
          (jv \ "lastModifiedTimestampUTC").extract[Long],
          jv \ "data"
      )
  },
  {
    case jSession: JSession =>
      ("acctSessionId" -> jSession.acctSessionId) ~
      ("ipAddress" -> jSession.ipAddress) ~
      ("clientId" -> jSession.clientId) ~
      ("macAddress" -> jSession.macAddress) ~
      ("groups" -> jSession.groups) ~
      ("startTimestampUTC" -> jSession.startTimestampUTC) ~
      ("lastModifiedTimestampUTC" -> jSession.startTimestampUTC) ~
      ("data" -> jSession.jData)
  }
))

/**
 * PoolSelector --> n Pools n <-- Ranges
 */

/**
 
CREATE TABLE PoolsSelectors (
  selectorId CHAR(50),
  poolId CHAR(50),
  priority INT, -- priority 0 means never use for assignments. Lower number means higher priority
  PRIMARY KEY (selectorId, poolId)
) WITH "template=replicated, CACHE_NAME=PoolSelectors, KEY_TYPE=es.indra.telco.iam.server.PoolSelectorKey, VALUE_TYPE=es.indra.telco.iam.server.PoolSelector";

CREATE TABLE Pools (
  poolId CHAR(50),
  PRIMARY KEY (poolId)
) WITH "template=replicated, CACHE_NAME=Pools, KEY_TYPE=es.indra.telco.iam.server.PoolKey, VALUE_TYPE=es.indra.telco.iam.server.Pool";

CREATE TABLE Ranges (
  poolId CHAR(50),
  startIPAddress INT,
  endIPAddress INT,
  status INT, -- 0: disabled, 1: enabled
  PRIMARY KEY (poolId, startIPAddress)
) WITH "template=replicated, CACHE_NAME=Ranges, KEY_TYPE=es.indra.telco.iam.server.RangeKey, VALUE_TYPE=es.indra.telco.iam.server.Range";

CREATE TABLE Leases (
  iPAddress INT,
  endLeaseTime TIMESTAMP,
  version INT,
  assignedTo CHAR(128),
  PRIMARY KEY (iPAddress)
) WITH "template=replicated, CACHE_NAME=Leases, KEY_TYPE=es.indra.telco.iam.server.LeaseKey, VALUE_TYPE=es.indra.telco.iam.server.Lease";

*/

/**
 * A PoolSelector is a String that points to a set of Pools, each one having multiple IP Ranges. This class
 * represents the association between a PoolSelector and a Pool. A Pool may be associated to multiple
 * PoolSelectors
 * @param selectorId name of the PoolSelector
 * @param poolId name of the Pool
 * @param priority if 0, do not use for subsequent assigments
 */
case class PoolSelector(
  @(QuerySqlField @field)(index = true) selectorId: String,
  @(QuerySqlField @field)(index = true) poolId: String,
  @(QuerySqlField @field) priority: Int  // 0: Do not use. 1 is the first priority.
)

/**
 * Represents a Pool of IP Ranges.
 * @param poolId the Pool name
 */
case class Pool(
  @(QuerySqlField @field)(index = true) poolId: String
)

/**
 * A Range is a set of contiguous IP Addresses, assigned to a Pool.
 * IP Addresses are represented as integers.
 * @param poolId the name of the Pool to which this Range is assigned (can only be one)
 * @param startIPAddress the first IP Address of the Range. Inclusive.
 * @param endIPAddress the last IP address of the Range. Inclusive.
 * @param status if 0, the range is not available and must not be used for leases
 */
case class Range(
    @(QuerySqlField @field)(index = true) poolId: String,
    @(QuerySqlField @field) startIPAddress: Long,
    @(QuerySqlField @field) endIPAddress: Long,
    @(QuerySqlField @field) status: Int        // 0: Not available, 1: Available
)

/**
 * A Lease represent the assignment status of an IP Address.
 * @param ipAddress the IP Address to which this Lease refers
 * @param endLeaseTime the end time of the lease. If smaller than now, the IP Address is free
 * @param version opaque number used for optimistic locking
 * @param assignedTo opaque String representing the entity owning this Lease, typically an Accounting-Session-Id
 */
case class Lease(
    @(QuerySqlField @field)(index = true) ipAddress: Long,
    @(QuerySqlField @field) endLeaseTime: java.util.Date,
    @(QuerySqlField @field) version: Long,
    @(QuerySqlField @field) assignedTo: String
)

/**
 * Helper class
 * @param startIPAddress the starting IP Address of the Range
 * @param endIPAddress the ending IP Address of the Range
 * @param status embedded Range status
 */
case class SimpleRange(startIPAddress: Long, endIPAddress: Long, status: Int) {
  // The Lease algorithm operates on small ranges. This method creates them
  def split(max: Int): List[SimpleRange] = {
    // Cumbersome, but startIPAddress and endIPAddress are inclusive
    if(size > max) SimpleRange(startIPAddress, startIPAddress + max - 1, status) :: 
      SimpleRange(startIPAddress + max, endIPAddress, status).split(max)
      
    else List(this)
  }
  
  def size: Long = {
    if(startIPAddress == endIPAddress) 1L else endIPAddress - startIPAddress + 1
  }
}

/**
 * Helper class
 * @param selectorId a Selector pointing to this Range
 * @param poolId the Pool to which this Range belongs
 * @param priority Selector Priority for assignment
 * @param range the embedded IP Address Range
 */
case class FullRange(selectorId: String, poolId: String, priority: Int, range: SimpleRange)

/**
 * To return the pool stats
 * @param poolId the poolId
 * @param totalAddresses number of addresses in all ranges
 * @param leasedAddresses number of non free addresses in all ranges
 */
case class PoolStats(poolId: String, totalAddresses: Long, leasedAddresses: Long)

/**
 * This object starts an ignite instance in client or server mode depending on configuration (aaa.sessionsDatabase), and
 * provides helper functions to interact with the caches.
 *
 * Configuration is retrieves from a <code>[instance]/ignite-yaasdb.xml</code> or <code>ignite-yaasdb.xml<code>
 * as a resource, or from the application configuration file (aaa.sessionsDatabase section)
 */
object SessionDatabase {

  val SUCCESS = 0
  val ASSIGNED_SELECTOR = 1
  val ASSIGNED_RANGE = 2
  val ACTIVE_RANGE = 3
  val ACTIVE_LEASES = 4
  val DOES_NOT_EXIST = 100

  val iamRetCodes = Map(
    SUCCESS -> "Success",
    ASSIGNED_SELECTOR -> "Assigned Selector",
    ASSIGNED_RANGE -> "Assigned Range",
    ACTIVE_RANGE -> "Active Range",
    ACTIVE_LEASES -> "Active Leases",
    100 -> "Does not exist"
  )
  
  private val log: Logger = LoggerFactory.getLogger(SessionDatabase.getClass)
  
  private val ti = System.getProperty("instance")
  private val instance = if(ti == null) "default" else ti
  
  // Configure Ignite
  private val config = ConfigFactory.load().getConfig("aaa.sessionsDatabase")
  
  // Set client mode if so configured
  private val role = config.getString("role")
  if(role.equalsIgnoreCase("client")){
    org.apache.ignite.Ignition.setClientMode(true)
    log.info("Node is a sessions database client")
  } else
    log.info("Node is a sessions database server")
    
  // Try to find the ignite configuration file (ugly due to Scala 2.11)
  // TODO: Fix this for Scala 2.13
  private val igniteFileWithInstance = getClass.getResource("/" + instance + "/ignite-yaasdb.xml")
  private val igniteFile = getClass.getResource("/ignite-yaasdb.xml")
  
  // Tries to find a configuration file for ignite first
  // Otherwise, takes config from global file
  val ignite: Ignite = {
    if(igniteFileWithInstance != null){
      val msg = s"Ignite configuration from file $instance/ignite-yaasdb.xml"
      log.info(msg)
      println(msg)
      // To get rid of percent encoding, use URLDecoder
      Ignition.start(java.net.URLDecoder.decode(igniteFileWithInstance.getFile, "ISO-8859-1"))
      
    } else if(igniteFile != null){
      val msg = "Ignite configuration from file ignite-yaasdb.xml"
      log.info(msg)
      println(msg)
      // To get rid of percent encoding, use URLDecoder
      Ignition.start(java.net.URLDecoder.decode(igniteFile.getFile, "ISO-8859-1"))
      
    } else {
      val msg = "Ignite configuration from application.conf"
      log.info(msg)
      println(msg)
  
      val igniteAddresses = config.getString("igniteAddresses")
      val localIgniteAddress = config.getString("localIgniteAddress").split(":")
      
      val dsConfiguration = new DataStorageConfiguration
      dsConfiguration.setStoragePath(config.getString("storagePath"))
      dsConfiguration.setWalPath(config.getString("walPath"))
      dsConfiguration.setWalArchivePath(config.getString("walArchivePath"))
      
      val dsRegionConfiguration = new DataRegionConfiguration
      dsRegionConfiguration.setName("custom")
      dsRegionConfiguration.setInitialSize(config.getLong("memoryMegabytes") * 1024L * 1024L)
      dsRegionConfiguration.setMaxSize(config.getLong("memoryMegabytes") * 1024L * 1024L)
      dsRegionConfiguration.setPersistenceEnabled(config.getBoolean("persistenceEnabled"))
      dsConfiguration.setDefaultDataRegionConfiguration(dsRegionConfiguration)
  
      val finder = if(igniteAddresses.startsWith("k8s:")){
        val kFinder = new TcpDiscoveryKubernetesIpFinder
        val tokens = igniteAddresses.split(":")
        kFinder.setNamespace(tokens(1))
        kFinder.setServiceName(tokens(2))
        kFinder
      } else {
        //(new TcpDiscoveryVmIpFinder).setAddresses(igniteAddresses.split(",").toList)
        (new TcpDiscoveryVmIpFinder).setAddresses(igniteAddresses.split(",").toList.asJava)
      }
      
      val discSpi = new TcpDiscoverySpi
      discSpi.setIpFinder(finder)
      if(role == "server"){
        discSpi.setLocalAddress(localIgniteAddress(0))
        discSpi.setLocalPort(localIgniteAddress(1).toInt)
      }
      
      val commSpi = new TcpCommunicationSpi()
      
      val igniteConfiguration = new IgniteConfiguration
      igniteConfiguration.setCommunicationSpi(commSpi)
      igniteConfiguration.setDiscoverySpi(discSpi)
      igniteConfiguration.setGridLogger(new org.apache.ignite.logger.slf4j.Slf4jLogger)
      if(role == "server") igniteConfiguration.setDataStorageConfiguration(dsConfiguration)

      Ignition.start(igniteConfiguration)
    }
  }

  // Done to force start if persistence enabled and do not want to activate the cluster manually
  if(config.getBoolean("forceActivateCluster")) ignite.cluster.active(true)
  
  private val sessionExpirationPolicy = new javax.cache.expiry.ModifiedExpiryPolicy(new javax.cache.expiry.Duration(java.util.concurrent.TimeUnit.HOURS, config.getInt("expiryTimeHours")))

  val sessionsCache: IgniteCache[String, Session] = ignite.getOrCreateCache[String, Session](new org.apache.ignite.configuration.CacheConfiguration[String, Session].setName("SESSIONS").setCacheMode(REPLICATED).setIndexedTypes(classOf[String], classOf[Session])).withExpiryPolicy(sessionExpirationPolicy)
  val leasesCache: IgniteCache[Long, Lease] = ignite.getOrCreateCache[Long, Lease](new org.apache.ignite.configuration.CacheConfiguration[Long, Lease].setName("LEASES").setCacheMode(REPLICATED).setIndexedTypes(classOf[Long], classOf[Lease])).withExpiryPolicy(sessionExpirationPolicy)
  val poolSelectorsCache: IgniteCache[(String, String), PoolSelector] = ignite.getOrCreateCache[(String, String), PoolSelector](new org.apache.ignite.configuration.CacheConfiguration[(String, String), PoolSelector].setName("POOLSELECTORS").setCacheMode(REPLICATED).setIndexedTypes(classOf[(String, String)], classOf[PoolSelector]))
  val poolsCache: IgniteCache[String, Pool] = ignite.getOrCreateCache[String, Pool](new org.apache.ignite.configuration.CacheConfiguration[String, Pool].setName("POOLS").setCacheMode(REPLICATED).setIndexedTypes(classOf[String], classOf[Pool]))
  val rangesCache: IgniteCache[(String, Long), Range] = ignite.getOrCreateCache[(String, Long), Range](new org.apache.ignite.configuration.CacheConfiguration[(String, Long), Range].setName("RANGES").setCacheMode(REPLICATED).setIndexedTypes(classOf[(String, Long)], classOf[Range]))
  
  def init(): Unit = {
    // Instantiates this object
  }
  
  def close(): Unit = {
    ignite.close()
  }
  
  /******************************************************************
   * Session methods
   *****************************************************************/
  
  /**
   * Inserts a new session or overwrites based on the AcctSessionId
   */
  def putSession(jSession: JSession): Unit = {
    sessionsCache.put(jSession.acctSessionId, jSession.toSession)
  }
  
  /**
   * Inserts a new session or overwrites based on the AcctSessionId
   */
  def putSessionAsync(jSession: JSession): IgniteFuture[Void] = {
    sessionsCache.putAsync(jSession.acctSessionId, jSession.toSession)
  }
  
  /**
   * Removes the session with the specified acctSessionId
   */
  def removeSession(acctSessionId: String): Boolean = {
    sessionsCache.remove(acctSessionId)
  }
  
  /**
   * Removes the session with the specified acctSessionId
   */
  def removeSessionAsync(acctSessionId: String): IgniteFuture[lang.Boolean] = {
    sessionsCache.removeAsync(acctSessionId)
  }

  /**
   * Updates the lastUpdatedTimestamp field and merges or updates the jData-
   *
   * To to the job, reads the cache entry and updates it from the client.
   */
  def updateSession(acctSessionId: String, jDataOption: Option[JValue], merge: Boolean): Boolean = {
    jDataOption match {
      case None => 
        val updateStmt = new SqlFieldsQuery("update \"SESSIONS\".Session set lastUpdatedTimestampUTC = ? where acctSessionId = ?")
          .setArgs(
              System.currentTimeMillis: java.lang.Long,
              acctSessionId: java.lang.String
           )
			  sessionsCache.query(updateStmt).getAll.asScala.head.get(0).asInstanceOf[Long] == 1
        
      case Some(jData) =>
        findSessionsByAcctSessionId(acctSessionId) match {
          case List(currentSession) =>
            if(merge) putSession(currentSession.copyMerged(jData)) else putSession(currentSession.copyUpdated(jData))
            true
        
          case _ =>
            false
        }
    }
  }

  /**
   * Updates the Session, setting the current timestamp as lastModified and merging if so specified the jData.
   *
   * The modification is done in the server using an EntryProcessor
   * @param acctSessionId the acctSessionId to be updated
   * @param jDataOption JSON data to update
   * @param merge true if the JSON data is to be merged or false if it should be updated
   * @return IgniteFuture
   */
  def updateSessionAsync(acctSessionId: String, jDataOption: Option[JValue], merge: Boolean): IgniteFuture[Object] = {
    sessionsCache.invokeAsync(acctSessionId, new EntryProcessor[String, Session, Object]() {
      // https://github.com/apache/ignite/blob/master/examples/src/main/scala/org/apache/ignite/scalar/examples/ScalarCacheEntryProcessorExample.scala
      override def process(e: MutableEntry[String, Session], args: AnyRef*): Object = {
        jDataOption match {
          case Some(jData) =>
            if(merge) e.setValue(e.getValue.copyMerged(jData)) else e.setValue(e.getValue.copyUpdated(jData))
          case None =>
            e.setValue(e.getValue.copyMerged(JObject()))
        }
        // Must return something
        null
      }
    })
  }

  /**
   * Retrieves all the sessions with the specified IP Address
   * @param ipAddress the IP Address
   * @return List of Sessions. Empty if no session found
   */
  def findSessionsByIPAddress(ipAddress: String): List[JSession] = {
    sessionsCache.query(new SqlFieldsQuery("Select _val from \"SESSIONS\".Session where ipAddress = ?").setArgs(ipAddress: java.lang.String)).getAll.asScala.map(item => JSession(item.get(0).asInstanceOf[Session])).toList
  }

  /**
   * Retrieves all the sessions with the specified clientId
   * @param clientId the clientId to look for
   * @return List of Sessions. Empty if no session found
   */
  def findSessionsByClientId(clientId: String): List[JSession] = {
    sessionsCache.query(new SqlFieldsQuery("Select _val from \"SESSIONS\".Session where clientId = ?").setArgs(clientId: java.lang.String)).getAll.asScala.map(item => JSession(item.get(0).asInstanceOf[Session])).toList
  }

  def findSessionsByMACAddress(macAddress: String): List[JSession] = {
    sessionsCache.query(new SqlFieldsQuery("Select _val from \"SESSIONS\".Session where MACAddress = ?").setArgs(macAddress: java.lang.String)).getAll.asScala.map(item => JSession(item.get(0).asInstanceOf[Session])).toList
  }

  /**
   * Retrieves all the sessions with the specified acctSessionId
   * @param acctSessionId the acctSessionId to look for
   * @return List of Sessions. Empty if no session found
   */
  def findSessionsByAcctSessionId(acctSessionId: String): List[JSession] = {
    sessionsCache.query(new SqlFieldsQuery("Select _val from \"SESSIONS\".Session where acctSessionId = ?").setArgs(acctSessionId: java.lang.String)).getAll.asScala.map(item => JSession(item.get(0).asInstanceOf[Session])).toList
  }

  /**
   * Gets info about the number of session for each combination of group labels
   * @return A List of groups (comma separated) and the session count for each one of them
   */
  def getSessionGroups: List[(String, Long)] = {
    sessionsCache.query(new SqlFieldsQuery("Select groups, count(*) as count from \"SESSIONS\".Session group by groups")).getAll.asScala.map(item => (item.get(0).asInstanceOf[String], item.get(1).asInstanceOf[Long])).toList
  }
  
 /******************************************************************
 * IAM methods
 *****************************************************************/
  /**
   * Buckets of this size addressed are retrieved from server to client during the process of finding a free
   * IP Address
   */
  private val addressBucketSize = config.getInt("iam.addressBucketSize")
    
  // Map[Selector -> Map[Priority -> Iterable[startIPAddr, endIPAddr, status]]]
  // For each selector, map of priorities to list of Ranges
  // Refreshed periodically
  var lookupTable: Map[String, Map[Int, Iterable[SimpleRange]]] = buildLookupTable
  
  /**
   * Builds the base info for looking up addresses. Refreshed at fixed time intervals.
   *
   * <code>Map[Selector -> Map[Priority -> Iterable[startIPAddr, endIPAddr, status]]]</code>
   */
  def buildLookupTable: Map[String, Map[Int, Iterable[SimpleRange]]] = {
    val t = for {
      item <- poolsCache.query(new SqlFieldsQuery(
        "select selectorId, ps.poolId, priority, startIPAddress, endIPAddress, status from \"RANGES\".Range r join \"POOLSELECTORS\".PoolSelector ps where r.poolId = ps.poolId")).asScala
    } yield
      FullRange(
          item.get(0).asInstanceOf[String], // selectorId
          item.get(1).asInstanceOf[String], // poolId
          item.get(2).asInstanceOf[Int],    // priority
          SimpleRange(item.get(3).asInstanceOf[Long], item.get(4).asInstanceOf[Long], item.get(5).asInstanceOf[Int])
      )
    log.info("Lookup table reloaded")

    // Group by selectorId and then by priority
    t.groupBy (_.selectorId).mapValues(rangeList => rangeList.groupBy(_.priority).mapValues(rl => rl.map(r => r.range)))
  }
  
  /**
   * Management
   */

  /**
   * Refreshes the lookup table.
   */
  def rebuildLookup(): Unit = {
    lookupTable = buildLookupTable
  }

  /**
   * Clears all tables.
   *
   * Used only for testing.
   */
  def resetToFactorySettings(): Unit = {
    poolSelectorsCache.clear()
    poolsCache.clear()
    rangesCache.clear()
    leasesCache.clear()
  }
  
  /*********************************************************************************************************************
   * Get methods
   ********************************************************************************************************************/

  def getPoolStats(poolId: String, enabledOnly: Boolean): PoolStats = {
    val basicQueryStr = "Select _val From \"RANGES\".Range where poolId = ?"
    val enabledQueryStr = basicQueryStr + " and status > 0"

    val ranges = rangesCache.query(new SqlFieldsQuery(if(enabledOnly) enabledQueryStr else basicQueryStr).setArgs(poolId: java.lang.String)).getAll.asScala.map(c => c.get(0).asInstanceOf[Range]).toList
    val totalAddresses = ranges.map(item => item.endIPAddress - item.startIPAddress + 1).sum

    val addressesPerRange = for {
      range <- ranges
    } yield leasesCache.query(new SqlFieldsQuery("Select count(*) from \"LEASES\".Lease where ipAddress >= ? and ipAddress <= ?")
      .setArgs(range.startIPAddress: java.lang.Long, range.endIPAddress: java.lang.Long)).asScala.head.get(0).asInstanceOf[Long]

    PoolStats(poolId, totalAddresses, addressesPerRange.sum)
  }

  /**
   * Returns the list of PoolSelectors (including the PoolId) for the specified selectorId
   * @param selectorIdOption the selectorId
   * @return
   */
  def getPoolSelectors(selectorIdOption: Option[String]): List[PoolSelector] = {
    selectorIdOption match {
      case Some(selectorId) =>
        poolSelectorsCache.query(new SqlFieldsQuery("Select _val from \"POOLSELECTORS\".Poolselector where selectorId = ?").setArgs(selectorId: java.lang.String)).getAll.asScala.map(c => c.get(0).asInstanceOf[PoolSelector]).toList

      case None =>
        poolSelectorsCache.query(new SqlFieldsQuery("Select _val from \"POOLSELECTORS\".Poolselector")).getAll.asScala.map(c => c.get(0).asInstanceOf[PoolSelector]).toList
    }
  }

  /**
   * Returns the list of Pools with the specified name (which will be only one) or all the Pools
   * @param poolIdOption the poolId
   * @return
   */
  def getPools(poolIdOption: Option[String]): List[Pool] = {
    poolIdOption match {
      case Some(poolId) =>
        poolsCache.query(new SqlFieldsQuery("Select _val from \"POOLS\".Pool where poolId = ?").setArgs(poolId: java.lang.String)).getAll.asScala.map(c => c.get(0).asInstanceOf[Pool]).toList
        
      case None =>
        poolsCache.query(new SqlFieldsQuery("Select _val from \"POOLS\".Pool")).getAll.asScala.map(c => c.get(0).asInstanceOf[Pool]).toList
    }
  }

  /**
   * Returns the List of ranges that correspond to the specified poolId.
   * @param poolIdOption the poolId
   * @return
   */
  def getRanges(poolIdOption: Option[String]): List[Range] = {
    poolIdOption match {
      case Some(poolId) =>
        rangesCache.query(new SqlFieldsQuery("Select _val from \"RANGES\".Range where poolId = ?").setArgs(poolId: java.lang.String)).getAll.asScala.map(c => c.get(0).asInstanceOf[Range]).toList
      case None =>
        rangesCache.query(new SqlFieldsQuery("Select _val from \"RANGES\".Range")).getAll.asScala.map(c => c.get(0).asInstanceOf[Range]).toList
    }
  }

  /**
   * Gets the Lease for a specified IP Address, encapsulated in a List, which will be empty if the Lease for that IP
   * Address is not found.
   *
   * @param ipAddress the IP Address
   * @return
   */
  def getLease(ipAddress: String): List[Lease] = {
    leasesCache.query(new SqlFieldsQuery("Select _val from \"LEASES\".Lease where ipAddress = ?").setArgs(ipAddress: java.lang.String)).getAll.asScala.map(c => c.get(0).asInstanceOf[Lease]).toList
  }

  /**
   * Creates a new Pool, without assigned Ranges.
   * Returns true if done. False if already exists.
   *
   * @param pool the Pool Object
   * @return
   */
  def putPool(pool: Pool): Boolean = {
    poolsCache.putIfAbsent(pool.poolId, pool)
  }

  /**
   * Pushes a new poolSelector object.
   * @param poolSelector the PoolSelector Object
   * @return
   */
  def putPoolSelector(poolSelector: PoolSelector): Boolean = {
    poolSelectorsCache.putIfAbsent((poolSelector.selectorId, poolSelector.poolId), poolSelector)
  }

  /**
   * Returns true if there is an existing Range which overlaps with the one passed as parameter.
   * @param range the Range
   * @return
   */
  def checkRangeOverlap(range: Range): Boolean = {
    rangesCache.query(new SqlFieldsQuery("Select _val from \"RANGES\".range where startIPAddress <= ? and endIPAddress >= ?")
        .setArgs(range.endIPAddress: java.lang.Long, range.startIPAddress: java.lang.Long)).getAll.size > 0
  }

  /**
   * Creates the Range
   * @param range the Range
   * @return true if inserted
   */
  def putRange(range: Range): Boolean = {
    rangesCache.putIfAbsent((range.poolId, range.startIPAddress), range)
  }

  /**
   * Checks that there is one SelectorId with the specified name
   * @param selectorId the selectorId
   * @return true if the selector exists
   */
  def checkSelectorId(selectorId: String): Boolean = {
    lookupTable.contains(selectorId)
  }
  
  /**
   * Checks that the specified poolId exists
   *
   * @return true if the poolId exists
   */
  def checkPoolId(poolId: String): Boolean = {
    poolsCache.containsKey(poolId)
  }

  /**
   * Gets an IP address
   * @param selectorId the selector
   * @param req the requester. Opaque parameter. Typically the acctSessionId. If not provided, a UUID is auto-generated
   * @param leaseTimeMillis how long to keep the lease. After that time plus graceTimeMillis the IP address may be assigned again
   * @param graceTimeMillis graceTime
   * @return a Lease object or None if could not get an IP Address, either because they are all assigned, or because the Selector does not exist.
   */
  def lease(selectorId: String, req: Option[String], leaseTimeMillis: Long, graceTimeMillis: Long): Option[Lease] = {
    val now = System.currentTimeMillis()

    // TODO: Execute in the server
    
    lookupTable.get(selectorId) match {
      // A selectorRange is a map from priorities to list of SimpleRanges
      case Some(selectorRanges) =>
        var myLease: Option[Lease] = None
        val requester = req.getOrElse(java.util.UUID.randomUUID().toString)
        
        // Sort by priority
        val pSelectorRanges = selectorRanges.toList.sortBy{case (k, _) => k}
        
        val myBreaks = new Breaks
        import myBreaks.{break, breakable}
        
        breakable {
          
	        // For each priority, excluding no assignable Pools (priority == 0)
	        for((priority, rangeList) <- pSelectorRanges if priority > 0){
            if(log.isDebugEnabled()) log.debug(s"Trying in Ranges $rangeList")

	          // Break into bucket addresses and filter ranges not available, then randomize order
	          val chunkedRangeList = scala.util.Random.shuffle(
	              rangeList.flatMap{sr => 
		              if(sr.status <= 0) List()
		              else sr.split(addressBucketSize)
	            })
	          
	          // For each bucket address chunk, try to reserve one address and break if success
	          for(range <- chunkedRangeList){
	            if(log.isDebugEnabled()) log.debug(s"Trying in chunk ${range.startIPAddress} to ${range.endIPAddress}")
	            val rangeLeases = leasesCache.query(new SqlFieldsQuery("Select _val from \"LEASES\".lease where ipAddress >= ? and ipAddress <= ? order by ipAddress")
                setArgs(range.startIPAddress: java.lang.Long, range.endIPAddress: java.lang.Long)).getAll.asScala.map(entry => entry.get(0).asInstanceOf[Lease])
	            val rangeSize = range.size.toInt
	            // Build a list of Leases in which all the items are filled (inserting "None" where the Lease does not yet exist)
	            var expectedIPAddr = range.startIPAddress
	            val filledLeases =
                (
                // If rangeLeases.size is rangeSize, no need to fill, just change format
	              if(rangeLeases.size == rangeSize) rangeLeases.map(lease => Some(lease)).toList
	              else {
                  if(log.isDebugEnabled()) log.debug("Filling Range with first time leases")
                  rangeLeases.flatMap(lease => {
                    // Returns the current item prepended by a number of empty elements equal to the offset between the element ip address and the var expectedIPAddr
                    val rv = List.fill[Option[Lease]]((lease.ipAddress - expectedIPAddr).toInt)(None) :+ Some(lease)
                      expectedIPAddr = lease.ipAddress + 1
                      rv
                      }) ++ List.fill[Option[Lease]]((range.endIPAddress - expectedIPAddr + 1).toInt)(None)
                }
                ).toIndexedSeq
	            // Find available lease, starting at a random position
	            val baseOffset = scala.util.Random.nextInt(rangeSize)
	            if(log.isDebugEnabled) log.debug(s"Looking up free IP address starting at $baseOffset out of $rangeSize")
	            for(i <- 0 until rangeSize){
	              val idx = (i + baseOffset) % rangeSize
	              filledLeases(idx) match {
	             
	                case None =>
	                  // Address not yet in database. Push new one
	                  val proposedLease = Lease(range.startIPAddress + idx, new java.util.Date(now + leaseTimeMillis), /* version */ 0, requester)
	                  if(log.isDebugEnabled) log.debug(s"Try to put a new Lease for ${proposedLease.ipAddress}")
	                  if(leasesCache.putIfAbsent(range.startIPAddress + idx, proposedLease)){
                      if(log.isDebugEnabled) log.debug(s"Assigned $proposedLease - First time")
	                    myLease = Some(proposedLease)
	                    break
	                  } else log.info(s"Lease creation not performed. Another client may have taken the candidate IP address ${proposedLease.ipAddress}")

	                case Some(lease) =>
	                    // Consider for leasing only addresses expired more than grace time ago
		                  if(lease.endLeaseTime.getTime + graceTimeMillis < now){
			                  // Try to update lease
			                  val proposedLease = Lease(range.startIPAddress + idx, new java.util.Date(now + leaseTimeMillis), lease.version + 1, requester)
			                  if(log.isDebugEnabled) log.debug(s"Try to get Lease for ${proposedLease.ipAddress}")
			                  val updateStmt = new SqlFieldsQuery("update \"LEASES\".lease set endLeaseTime = ?, version = ?, assignedTo = ? where ipAddress = ? and version = ?")
			                    .setArgs(
			                        proposedLease.endLeaseTime: java.util.Date,
			                        proposedLease.version: java.lang.Long, 
			                        requester: java.lang.String, 
			                        proposedLease.ipAddress : java.lang.Long, 
			                        lease.version : java.lang.Long
			                     )
			                  val updates = leasesCache.query(updateStmt).getAll.asScala.head.get(0).asInstanceOf[Long]
			                  if(updates == 1){
                          if(log.isDebugEnabled()) log.debug(s"Assigned $proposedLease - Reused")
			                    myLease = Some(proposedLease)
			                    break
			                  } else log.info(s"Lease update not performed. Another client may have taken the candidate IP address ${proposedLease.ipAddress}")
	                    } // else if(log.isDebugEnabled()) log.debug(s"Not available $lease")
	              }
	            }
              if(log.isDebugEnabled()) log.debug(s"Could not find free address in range $range")
	          }
	        }
        } // breakable
        
        myLease match {
          case Some(lease) =>
            if(log.isDebugEnabled) log.debug(s"Assigned ${lease.ipAddress} to ${lease.assignedTo}")
          case None =>
            log.warn(s"Could not assign an IP Address to $requester")
        }
        
        myLease 
        
      case None =>
        log.error(s"$selectorId not found")
        None
    }
  }

  /**
   * Releases the specified IP Address
   * Returns true if the address was released.
   *
   * @param ipAddress the IP Address to free
   * @return true if the IP Address was released, false otherwise
   */
  def release(ipAddress: Long): Boolean = {
    val nowDate = new java.util.Date
    val updateStmt = new SqlFieldsQuery("update \"LEASES\".lease set endLeaseTime = ? where ipAddress = ?")
        .setArgs(
            nowDate: java.util.Date, 
            ipAddress: java.lang.Long
         )
      val updated = leasesCache.query(updateStmt).getAll.asScala.head.get(0).asInstanceOf[Long]
      if(updated != 1){
        log.warn(s"Could not release $ipAddress")
        false
      } else true
  }

  /**
   * Extend the Lease period.
   *
   * @param ipAddress the IP Address
   * @param requester the requester. It must match.
   * @param leaseTimeMillis the extension period
   * @return true if the IP Address was renewed, false otherwise
   */
  def renew(ipAddress: Long, requester: String, leaseTimeMillis: Long): Boolean = {
    val now = System.currentTimeMillis()
    val updateStmt = new SqlFieldsQuery("update \"LEASES\".lease set endLeaseTime = ? where ipAddress = ? and endLeaseTime > ? and assignedTo = ?")
      .setArgs(
          new java.util.Date(now + leaseTimeMillis): java.util.Date, 
          ipAddress: java.lang.Long, 
          new java.util.Date(now): java.util.Date, 
          requester: java.lang.String
       )
       
      val updated = leasesCache.query(updateStmt).getAll.asScala.head.get(0).asInstanceOf[Long]
      if(updated != 1){
        log.warn(s"Could not renew $ipAddress")
        false
      } else true
  }

  /**
   * For testing only. Creates a set of old leases filling all the ranges in the specified pool.
   *
   * @param poolId the PoolId to fill
   */
  def fillPoolLeases(poolId: String): Unit = {
    val now = System.currentTimeMillis()
    for {
      currentRange <- rangesCache.query(new SqlFieldsQuery("Select _val from \"RANGES\".range where poolId = ?").setArgs(poolId: java.lang.String)).getAll.asScala
      range = currentRange.get(0).asInstanceOf[Range]
      ipAddress <- range.startIPAddress to range.endIPAddress if range.status > 0
    } leasesCache.put(ipAddress, Lease(ipAddress, new java.util.Date(now - 86400000), /* version */ 99, "fake-to-test"))
  }

  /**
   * Delete the PoolSelector
   * @param selectorId the Selector to delete
   * @param poolId the Pool to which it points to
   * @return 0 if success
   */
  def deletePoolSelector(selectorId: String, poolId: String): Int = {
    if(poolSelectorsCache.remove((selectorId, poolId))) 0 else DOES_NOT_EXIST
  }
  

  /**
   * Deletes a pool, and all the enclosed Ranges if so specified.
   *
   * It may partially do the job, deleting some Ranges but not others which are in use.
   *
   * @param poolId the poolId
   * @param deleteRanges if false, only will delete the Pool if there are no enclosed Ranges. Otherwise it will try
   *                     to delete them.
   * @param withActiveLeases this parameter is passed to deleteRange
   * @return 0 if success
   */
  def deletePool(poolId: String, deleteRanges: Boolean, withActiveLeases: Boolean): Int = {

    if(poolSelectorsCache.query(new SqlFieldsQuery("Select _val from \"POOLSELECTORS\".poolSelector where poolId = ?").setArgs(poolId: java.lang.String)).getAll.size > 0){
      log.warn(s"Tried to delete Pool $poolId, which has associated Selectors")
      // Did not delete the pool because there is a Selector pointing to it
      ASSIGNED_SELECTOR
    } else {
      if(!deleteRanges && rangesCache.query(new SqlFieldsQuery("Select _val from \"RANGES\".range where poolId = ?").setArgs(poolId: java.lang.String)).getAll.size > 0){
          log.warn(s"Tried to delete Pool $poolId, which has associated Ranges")
          // Did not delete the pool because there are enclosed Ranges
          ASSIGNED_RANGE
      } else {
        // Delete Ranges
        val deleteResult = (for {
          rangeEntry <- rangesCache.query(new SqlFieldsQuery("Select _val from \"RANGES\".range where poolId = ?").setArgs(poolId: java.lang.String)).getAll.asScala
          range = rangeEntry.get(0).asInstanceOf[Range]
        } yield deleteRange(range.poolId, range.startIPAddress, withActiveLeases)).find(_ != 0).getOrElse(SUCCESS)
        if(deleteResult != SUCCESS){
          log.warn(s"At least one Range in $poolId could not be deleted")
          deleteResult
        }
        else {
          if(poolsCache.remove(poolId)) SUCCESS else DOES_NOT_EXIST
        }
      }
    }
  }

  /**
   * Deletes the range and the corresponding leases. The status must be "disabled" (0) and there must not be
   * active Leases, unless ("force") is true.
   *
   * @param poolId the poolId
   * @param startIpAddress the start IP Address
   * @param withActiveLeases whether to do the deletion even if there are active Leases
   * @return code
   */
  def deleteRange(poolId: String, startIpAddress: Long, withActiveLeases: Boolean = false): Int = {

    Option(rangesCache.get((poolId, startIpAddress))) match {
      case None =>
        log.warn(s"Tried to delete non existing Range: $poolId, $startIpAddress")
        DOES_NOT_EXIST

      case Some(range) if range.status != 0 =>
        log.warn(s"Tried to delete Range with non 0 status: $poolId, $startIpAddress")
        ACTIVE_RANGE

      case Some(range) =>
        if(!withActiveLeases) {
          // Check that there are not active Leases
          val query = new SqlFieldsQuery("select count(*) from \"LEASES\".lease where ipAddress >= ? and ipAddress <= ? and endLeaseTime > ?")
            .setArgs(
              range.startIPAddress: java.lang.Long,
              range.endIPAddress: java.lang.Long,
              new java.util.Date(System.currentTimeMillis()): java.util.Date
            )

          if(leasesCache.query(query).getAll.asScala.head.get(0).asInstanceOf[Long] > 1){
            log.warn("Trying to delete Range with active leases: $poolId, $startIPAddress")
            return ACTIVE_LEASES
          }
        }

        // Delete the Leases
        val deleteStmt = new SqlFieldsQuery("delete from \"LEASES\".lease where ipAddress >= ? and ipAddress <= ?")
          .setArgs(
            range.startIPAddress: java.lang.Long,
            range.endIPAddress: java.lang.Long
          )
        leasesCache.query(deleteStmt)

        // Delete the Range
        if(rangesCache.remove((poolId, startIpAddress))) SUCCESS else DOES_NOT_EXIST
    }
    
  }

  /**
   * Changes the status of a Range
   * @param poolId the poolId
   * @param startIpAddress the start IP Address
   * @param status new status
   * @return true if the modification took place, and false if nothing changed
   */
  def modifyRangeStatus(poolId: String, startIpAddress: Long, status: Int): Boolean = {
    val updateStmt = new SqlFieldsQuery("update \"RANGES\".range set status = ? where poolId = ? and startIpAddress = ?")
      .setArgs(
          status: java.lang.Integer,
          poolId: java.lang.String,
          startIpAddress: java.lang.Long
      )
        
    rangesCache.query(updateStmt).getAll.asScala.head.get(0).asInstanceOf[Long] == 1
  }

  /**
   * Changes the priority of a Selector
   * @param selectorId the selectorId
   * @param poolId the PoolId
   * @param priority the new priority
   * @return true if the modification was performed, and false if nothing was changed
   */
  def modifyPoolSelectorPriority(selectorId: String, poolId: String, priority: Int): Boolean = {
    val updateStmt = new SqlFieldsQuery("update \"POOLSELECTORS\".PoolSelector set priority = ? where selectorId = ? and poolId = ?")
      .setArgs(
          priority: java.lang.Integer,
          selectorId: java.lang.String,
          poolId: java.lang.String
      )
        
    poolSelectorsCache.query(updateStmt).getAll.asScala.head.get(0).asInstanceOf[Long] == 1
  }
}