package yaas.database

import com.typesafe.config._

import scala.util.control.Breaks
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
import org.apache.ignite.configuration.CacheConfiguration
import org.apache.ignite.cache.CacheMode._

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

/**
 * Represents a Radius/Diameter Session as stored in the in-memory sessions database.
 * jData is stringified JSON
 * 
 * Handlers use a JSession object, which is internally converted to Session
 */

// TODO: Should use Options
case class Session(
    @ScalarCacheQuerySqlField(index = true) acctSessionId: String, 
    @ScalarCacheQuerySqlField(index = true) ipAddress: String, 
    @ScalarCacheQuerySqlField(index = true) clientId: String,
    @ScalarCacheQuerySqlField(index = true) macAddress: String,
    @ScalarCacheQuerySqlField(index = true) groups: String,
    @ScalarCacheQuerySqlField startTimestampUTC: Long,
    @ScalarCacheQuerySqlField lastUpdatedTimestampUTC: Long,
    jData: String
)

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
  
  def toSession = {
    Session(acctSessionId, ipAddress, clientId, macAddress, groups.mkString(","), startTimestampUTC, lastUpdatedTimestampUTC, compact(render(jData)))
  }
  
  def copyUpdated(updatedJData: JValue) = new JSession(acctSessionId, ipAddress, clientId, macAddress, groups, startTimestampUTC, System.currentTimeMillis(), updatedJData)
  
  def copyMerged(toMergeJData: JValue) = new JSession(acctSessionId, ipAddress, clientId, macAddress, groups, startTimestampUTC, System.currentTimeMillis(), jData.merge(toMergeJData))
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
          (jv \ "data")
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
  endLeaseTimestamp TIMESTAMP,
  version INT,
  assignedTo CHAR(128),
  PRIMARY KEY (iPAddress)
) WITH "template=replicated, CACHE_NAME=Leases, KEY_TYPE=es.indra.telco.iam.server.LeaseKey, VALUE_TYPE=es.indra.telco.iam.server.Lease";

*/

case class PoolSelector(
    @ScalarCacheQuerySqlField(index = true) selectorId: String, 
    @ScalarCacheQuerySqlField(index = true) poolId: String, 
    @ScalarCacheQuerySqlField priority: Int  // 0: Do not use. 1 is the first priority.
)

case class Pool(
    @ScalarCacheQuerySqlField(index = true) poolId: String
)

case class Range(
    @ScalarCacheQuerySqlField(index = true) poolId: String, 
    @ScalarCacheQuerySqlField startIPAddress: Long, 
    @ScalarCacheQuerySqlField endIPAddress: Long, 
    @ScalarCacheQuerySqlField status: Int        // 0: Not available, 1: Available
)

case class Lease(
    @ScalarCacheQuerySqlField(index = true) ipAddress: Long, 
    @ScalarCacheQuerySqlField endLeaseTimestamp: java.util.Date, 
    @ScalarCacheQuerySqlField version: Long,
    @ScalarCacheQuerySqlField assignedTo: String
)

case class SimpleRange(startIPAddress: Long, endIPAddress: Long, status: Int) {
  // The Lease algorithm operates on small ranges. This method creates them
  def split(max: Int): List[SimpleRange] = {
    // Cumbersome, but startIPAddress and endIPAddress are inclusive
    if(size > max) SimpleRange(startIPAddress, startIPAddress + max - 1, status) :: 
      SimpleRange(startIPAddress + max, endIPAddress, status).split(max)
      
    else List(this)
  }
  
  def size = {
    if(startIPAddress == endIPAddress) 1 else endIPAddress - startIPAddress + 1
  }
}

case class FullRange(selectorId: String, poolId: String, priority: Int, range: SimpleRange)
    

/**
 * This object starts an ignite instance in client or server mode depending on configuration (aaa.sessionsDatabase), and
 * provides helper functions to interact with the caches
 */
object SessionDatabase {
  
  val log = LoggerFactory.getLogger(SessionDatabase.getClass)
  
  val ti = System.getProperty("instance")
  val instance = if(ti == null) "default" else ti
  
  // Configure Ignite
  val config = ConfigFactory.load().getConfig("aaa.sessionsDatabase")
  
  // Set client mode if so configured
  val role = config.getString("role")
  if(role.equalsIgnoreCase("client")){
    org.apache.ignite.Ignition.setClientMode(true)
    log.info("Node is a sessions database client")
  } else log.info("Node is a sessions database server")
    
  // Try to find the ignite configuration file (ugly due to Scala 2.11)
  val igniteFileWithInstance = getClass.getResource("/" + instance + "/ignite-yaasdb.xml")
  val igniteFile = getClass.getResource("/ignite-yaasdb.xml")
  
  // Tries to find a configuration file for ignite first
  // Otherwise, takes config from global file
  val ignite = {
    if(igniteFileWithInstance != null){
      val msg = s"Ignite configuration from file ${instance}/ignite-yaasdb.xml"
      log.info(msg)
      println(msg)
      // To get rid of percent encoding, use URLDecoder
      scalar.start(java.net.URLDecoder.decode(igniteFileWithInstance.getFile(), "ISO-8859-1"))
      
    } else if(igniteFile != null){
      val msg = "Ignite configuration from file ignite-yaasdb.xml"
      log.info(msg)
      println(msg)
      // To get rid of percent encoding, use URLDecoder
      scalar.start(java.net.URLDecoder.decode(igniteFile.getFile(), "ISO-8859-1"))
      
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
  
      val finder = new TcpDiscoveryVmIpFinder
      finder.setAddresses(igniteAddresses.split(",").toList)
      
      val discSpi = new TcpDiscoverySpi
      discSpi.setIpFinder(finder)
      if(role == "server"){
        discSpi.setLocalAddress(localIgniteAddress(0))
        discSpi.setLocalPort(localIgniteAddress(1).toInt)
      }
      
      val commSpi = new TcpCommunicationSpi();
      
      val igniteConfiguration = new IgniteConfiguration
      igniteConfiguration.setCommunicationSpi(commSpi)
      igniteConfiguration.setDiscoverySpi(discSpi)
      igniteConfiguration.setGridLogger(new org.apache.ignite.logger.slf4j.Slf4jLogger)
      if(role == "server") igniteConfiguration.setDataStorageConfiguration(dsConfiguration)

      scalar.start(igniteConfiguration)
    }
  }

  // TODO: Remove this. Done to force start if persistence enabled
  ignite.cluster.active(true)
  
  val sessionExpirationPolicy = new javax.cache.expiry.ModifiedExpiryPolicy(new javax.cache.expiry.Duration(java.util.concurrent.TimeUnit.HOURS, config.getInt("expiryTimeHours")))

  val sessionsCache = ignite.getOrCreateCache[String, Session](new org.apache.ignite.configuration.CacheConfiguration[String, Session].setName("SESSIONS").setCacheMode(REPLICATED).setIndexedTypes(classOf[String], classOf[Session])).withExpiryPolicy(sessionExpirationPolicy)
  val leasesCache = ignite.getOrCreateCache[Long, Lease](new org.apache.ignite.configuration.CacheConfiguration[Long, Lease].setName("LEASES").setCacheMode(REPLICATED).setIndexedTypes(classOf[Long], classOf[Lease])).withExpiryPolicy(sessionExpirationPolicy)
  val poolSelectorsCache = ignite.getOrCreateCache[(String, String), PoolSelector](new org.apache.ignite.configuration.CacheConfiguration[(String, String), PoolSelector].setName("POOLSELECTORS").setCacheMode(REPLICATED).setIndexedTypes(classOf[(String, String)], classOf[PoolSelector]))
  val poolsCache = ignite.getOrCreateCache[String, Pool](new org.apache.ignite.configuration.CacheConfiguration[String, Pool].setName("POOLS").setCacheMode(REPLICATED).setIndexedTypes(classOf[String], classOf[Pool]))
  val rangesCache = ignite.getOrCreateCache[(String, Long), Range](new org.apache.ignite.configuration.CacheConfiguration[(String, Long), Range].setName("RANGES").setCacheMode(REPLICATED).setIndexedTypes(classOf[(String, Long)], classOf[Range]))
  
  def init() = {
    // Instantiates this object
  }
  
  def close() = {
    ignite.close
  }
  
  /******************************************************************
   * Session methods
   *****************************************************************/
  
  /**
   * Inserts a new session or overwrites based on the AcctSessionId
   */
  def putSession(jSession: JSession) = {
    sessionsCache.put(jSession.acctSessionId, jSession.toSession)
  }
  
  /**
   * Removes the session with the specified acctSessionId
   */
  def removeSession(acctSessionId: String) = {
    sessionsCache.remove(acctSessionId)
  }
  
  /**
   * Updates the lastUpdatedTimestamp field and merges or updates the jData
   */
  def updateSession(acctSessionId: String, jDataOption: Option[JValue], merge: Boolean) = {
    jDataOption match {
      case None => 
        val updateStmt = new SqlFieldsQuery("update \"SESSIONS\".Session set lastUpdatedTimestampUTC = ? where acctSessionId = ?")
          .setArgs(
              System.currentTimeMillis: java.lang.Long,
              acctSessionId: java.lang.String
           )
			  sessionsCache.query(updateStmt).getAll.head.get(0).asInstanceOf[Long] == 1
        
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
  
  def findSessionsByIPAddress(ipAddress: String) = {
    sessionsCache.sql("select * from \"SESSIONS\".Session where ipAddress = ?", ipAddress).getAll.map(entry => JSession(entry.getValue)).toList
  }
  
  def findSessionsByClientId(clientId: String) = {
    sessionsCache.sql("select * from \"SESSIONS\".Session where clientId = ?", clientId).getAll.map(entry => JSession(entry.getValue)).toList
  }
  
  def findSessionsByMACAddress(macAddress: String) = {
    sessionsCache.sql("select * from \"SESSIONS\".Session where MACAddress = ?", macAddress).getAll.map(entry => JSession(entry.getValue)).toList
  }
  
  def findSessionsByAcctSessionId(acctSessionId: String) = {
    sessionsCache.sql("select * from \"SESSIONS\".Session where acctSessionId = ?", acctSessionId).getAll.map(entry => JSession(entry.getValue)).toList
  }
  
  def getSessionGroups = {
    sessionsCache.query(new SqlFieldsQuery("select groups, count(*) as count from \"SESSIONS\".Session group by groups")).getAll.map(item => (item.get(0).asInstanceOf[String], item.get(1).asInstanceOf[Long])).toList
  }
  
 /******************************************************************
 * IAM methods
 *****************************************************************/
    
  val addressBucketSize = config.getInt("iam.addressBucketSize")
    
  // Map[Selector -> Map[Priority -> Iterable[startIPAddr, endIPAddr, status]]
  // For each selector, map of priorities to list of Ranges
  // Refreshed periodically
  var lookupTable: Map[String, Map[Int, Iterable[SimpleRange]]] = buildLookupTable
  
  /**
   * Builds the base info for looking up addresses. Refresed at fixed time intervals
   * Map[Selector -> Map[Priority -> Iterable[startIPAddr, endIPAddr, status]]
   */
  def buildLookupTable = {
    val t = (
      for {
        item <- poolsCache.query(new SqlFieldsQuery("select selectorId, ps.poolId, priority, startIPAddress, endIPAddress, status from \"RANGES\".Range r join \"POOLSELECTORS\".PoolSelector ps where r.poolId = ps.poolId"))
      } yield 
        FullRange(
            item.get(0).asInstanceOf[String], // selectorId
            item.get(1).asInstanceOf[String], // poolId
            item.get(2).asInstanceOf[Int],    // priority
            SimpleRange(item.get(3).asInstanceOf[Long], item.get(4).asInstanceOf[Long], item.get(5).asInstanceOf[Int])
        )
    ) 
    log.info("Lookup table reloaded")
    
    t.groupBy (_.selectorId).mapValues(rangeList => rangeList.groupBy(_.priority).mapValues(rl => rl.map(r => r.range)))
  }
  
  /**
   * Management
   */
  
  // Refreshes the lookup table
  def rebuildLookup = {
    lookupTable = buildLookupTable
  }
  
  // Clears all tables. Used for testing
  def resetToFactorySettings = {
    poolSelectorsCache.clear
    poolsCache.clear
    rangesCache.clear
    leasesCache.clear
  }
  
  /**
   * Get methods
   */
  
  /**
   * Returns the list of PoolSelectors for the specified selectorId
   */
  def getPoolSelectors(selectorIdOption: Option[String]) = {
    selectorIdOption match {
      case Some(selectorId) =>
        poolSelectorsCache.sql("select * from \"POOLSELECTORS\".Poolselector where selectorId = ?", selectorId).getAll.map(c => c.getValue).toList

      case None =>
        poolSelectorsCache.sql("select * from \"POOLSELECTORS\".Poolselector").getAll.map(c => c.getValue).toList
    }
  }
  
  /**
   * Returns the list of pools with the specified name
   */
  def getPools(poolIdOption: Option[String]) = {
    poolIdOption match {
      case Some(poolId) =>
        poolsCache.sql("select * from \"POOLS\".Pool where poolId = ?", poolId).getAll.map(c => c.getValue).toList
        
      case None =>
        poolsCache.sql("select * from \"POOLS\".Pool").getAll.map(c => c.getValue).toList
    }
  }
  
  /**
   * Returns the List of ranges that correspond to the specified poolId
   */
  def getRanges(poolIdOption: Option[String]) = {
    poolIdOption match {
      case Some(poolId) =>
        rangesCache.sql("select * from \"RANGES\".Range where poolId = ?", poolId).getAll.map(c => c.getValue).toList
      case None =>
        rangesCache.sql("select * from \"RANGES\".Range").getAll.map(c => c.getValue).toList
    }
  }
  
  
  def getLease(ipAddress: String) = {
    leasesCache.sql("select * from \"LEASES\".Lease where ipAddress = ?", ipAddress).getAll.map(c => c.getValue).toList
  }
  
  /**
   * Create methods
   */
  
  /**
   * Pushes a new Pool.
   * Returns true if done. False if already existed.
   */
  def putPool(pool: Pool) = {
    poolsCache.putIfAbsent(pool.poolId, pool)
  }
  
  /**
   * Pushes a new poolSelector object
   * Returns true if done
   */
  def putPoolSelector(poolSelector: PoolSelector) = {
    poolSelectorsCache.putIfAbsent((poolSelector.selectorId, poolSelector.poolId), poolSelector)
  }
  
  /**
   * Returns true if there is a Range which overlaps with the existing one
   */
  def checkRangeOverlap(range: Range) = {
    rangesCache.sql("select * from \"RANGES\".range where startIPAddress <= ? and endIPAddress >= ?", 
    				        range.endIPAddress, range.startIPAddress).getAll.size > 0
  }
  
  /**
   * Pushes the new Range
   */
  def putRange(range: Range) = {
    rangesCache.putIfAbsent((range.poolId, range.startIPAddress), range)
  }
  
  /**
   * Checks that there is one SelectorId with the specified name
   */
  def checkSelectorId(selectorId: String) = {
    lookupTable.get(selectorId) != None
  }
  
  /**
   * Checks that the specified poolId 
   */
  def checkPoolId(poolId: String) = {
    Option(poolsCache.get(poolId)) != None
  }
  
  /**
   * Gets an IP address
   */
  def lease(selectorId: String, req: Option[String], leaseTimeMillis: Long, graceTimeMillis: Long) = {
    val now = (new java.util.Date).getTime
    
    lookupTable.get(selectorId) match {
      case Some(selectorRanges) =>
        var myLease: Option[Lease] = None
        val requester = req.getOrElse(java.util.UUID.randomUUID().toString)
        
        // Sort by priority
        val pSelectorRanges = selectorRanges.toList.sortBy{case (k, v) => k}
        
        val mybreaks = new Breaks
        import mybreaks.{break, breakable}
        
        breakable {
          
	        // For each priority
	        for((priority, rangeList) <- pSelectorRanges if priority > 0){
	          
	          // Break into bucket addresses and filter ranges not available, then randomize order
	          val chunkedRangeList = scala.util.Random.shuffle(
	              rangeList.flatMap{sr => 
		              if(sr.status <= 0) List()
		              else sr.split(addressBucketSize)
	            })
	          
	          // For each bucket address chunk, try to reserve one address  
	          for(range <- chunkedRangeList){
	            if(log.isDebugEnabled()) log.debug(s"Getting leases from ${range.startIPAddress} to ${range.endIPAddress}")
	            val rangeLeases = leasesCache.sql("Select * from \"LEASES\".lease where ipAddress >= ? and ipAddress <= ? order by ipAddress",
	               range.startIPAddress, range.endIPAddress).getAll().map(entry => entry.getValue)
	            val rangeSize = range.size.toInt
	            
	            // Build a list of leases in which all the items are filled (inserting "None" where the Lease does not yet exist)
	            var expectedIPAddr = range.startIPAddress
	            // If rangeLeases.size is rangeSize, no need to fill, just change format
	            val filledLeases = 
	              if(rangeLeases.size == rangeSize) rangeLeases.map(lease => Some(lease)).toList
	              else rangeLeases.flatMap(lease => {
	              // Returns the current item prepended by a number of empty elements equal to the offset between the element ip address and the var expectedIPAddr
	              val rv = List.fill[Option[Lease]]((lease.ipAddress - expectedIPAddr).toInt)(None) :+ Some(lease)
	              expectedIPAddr = lease.ipAddress + 1
	              rv
	            }) ++ List.fill[Option[Lease]]((range.endIPAddress - expectedIPAddr + 1).toInt)(None)

	            // Find available lease, starting at a random position
	            val baseOffset = scala.util.Random.nextInt(rangeSize)
	            if(log.isDebugEnabled) log.debug(s"Looking up free IP address starting at $baseOffset out of $rangeSize)")
	            for(i <- 0 to rangeSize - 1){
	              val idx = (i + baseOffset) % rangeSize
	              filledLeases.get(idx) match {
	             
	                case None =>
	                  // Address not yet in database. Push new one
	                  val proposedLease = Lease(range.startIPAddress + idx, new java.util.Date(now + leaseTimeMillis), 0, requester)
	                  if(log.isDebugEnabled) log.debug(s"Try to put a new Lease for ${proposedLease.ipAddress}")
	                  if(leasesCache.putIfAbsent(range.startIPAddress + idx, proposedLease)){
	                    myLease = Some(proposedLease)
	                    break
	                  } else log.info(s"Lease creation not performed. Another client may have taken the candidate IP address ${proposedLease.ipAddress}")
	                  
	                case Some(lease) =>
	                    // Consider for leasing only addresses expired more than grace time ago
		                  if(lease.endLeaseTimestamp.getTime + graceTimeMillis < now){
			                  // Try to update lease
			                  val proposedLease = Lease(range.startIPAddress + idx, new java.util.Date(now + leaseTimeMillis), lease.version + 1, requester)
			                  if(log.isDebugEnabled) log.debug(s"Try to put update lease for for ${proposedLease.ipAddress}")
			                  val updateStmt = new SqlFieldsQuery("update \"LEASES\".lease set endLeaseTimestamp = ?, version = ?, assignedTo = ? where ipAddress = ? and version = ?")
			                    .setArgs(
			                        proposedLease.endLeaseTimestamp: java.util.Date, 
			                        proposedLease.version: java.lang.Long, 
			                        requester: java.lang.String, 
			                        proposedLease.ipAddress : java.lang.Long, 
			                        lease.version : java.lang.Long
			                     )
			                  val updates = leasesCache.query(updateStmt).getAll.head.get(0).asInstanceOf[Long]
			                  if(updates == 1){
			                    myLease = Some(proposedLease)
			                    break
			                  } else log.info(s"Lease update not performed. Another client may have taken the candidate IP address ${proposedLease.ipAddress}")
	                    }
	              }
	            }
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
   */
  def release(ipAddress: Long) = {
    val nowDate = new java.util.Date
    val updateStmt = new SqlFieldsQuery("update \"LEASES\".lease set endLeaseTimestamp = ? where ipAddress = ?")
        .setArgs(
            nowDate: java.util.Date, 
            ipAddress: java.lang.Long
         )
      val updated = leasesCache.query(updateStmt).getAll.head.get(0).asInstanceOf[Long]
      if(updated != 1) log.warn(s"Could not release $ipAddress")
      updated == 1
  }
  
  def renew(ipAddress: Long, requester: String, leaseTimeMillis: Long) = {
    val now = (new java.util.Date).getTime
    val updateStmt = new SqlFieldsQuery("update \"LEASES\".lease set endLeaseTimestamp = ? where ipAddress = ? and endLeaseTimestamp > ? and assignedTo = ?")
      .setArgs(
          new java.util.Date(now + leaseTimeMillis): java.util.Date, 
          ipAddress: java.lang.Long, 
          new java.util.Date(now): java.util.Date, 
          requester: java.lang.String
       )
       
      val updated = leasesCache.query(updateStmt).getAll.head.get(0).asInstanceOf[Long]
      if(updated != 1) log.warn(s"Could not renew $ipAddress")
      updated == 1
  }
  
  // For testing only. Creates a set of old leases filling all the ranges in the specified pool
  def fillPoolLeases(poolId: String) = {
    val now = (new java.util.Date).getTime
    val poolRanges = rangesCache.sql("Select * from \"RANGES\".range where poolId = ?", poolId).getAll
    for(currentRange <- poolRanges){
      val range = currentRange.getValue
      if(range.status > 0) {
        for(ipAddr <- range.startIPAddress to range.endIPAddress)
          leasesCache.put(ipAddr, Lease(ipAddr, new java.util.Date(now - 86400000), 99, "fake-to-test"))
      }
    }
  }
  
  /**
   * Delete objects
   */
  
  def deletePoolSelector(selectorId: String, poolId: String) = {
    poolSelectorsCache.remove((selectorId, poolId))
  }
  

  /** 
   *  Verifies that the pool is not associated to a Selector or a Range
   */
  def deletePool(poolId: String) = {
    if(rangesCache.sql("select * from \"RANGES\".range where poolId = ?", poolId).getAll.size > 0 ||
			 poolSelectorsCache.sql("select * from \"POOLSELECTORS\".poolSelector where poolId = ?", poolId).getAll.size > 0){
				  false
		} else {
			  // Delete pool
			  poolsCache.remove((poolId))
	  }
  }
  
  /**
   * Deletes the Range.
   * 
   * Does not check whether there is any Address in use 
   */
  def deleteRange(poolId: String, startIpAddress: Long, force: Boolean) = {
    
    val inUse = if(!force){
      Option(rangesCache.get(poolId, startIpAddress)) match {
        case Some(range) =>
          
        val query = new SqlFieldsQuery("select count(*) from \"LEASES\".lease where ipAddress >= ? and ipAddress <= ? and endLeaseTimestamp > ?")
        .setArgs(
            range.startIPAddress: java.lang.Long,
            range.endIPAddress: java.lang.Long,
            System.currentTimeMillis(): java.lang.Long
         )
         
      leasesCache.query(query).getAll.head.get(0).asInstanceOf[Long] > 1
          
        case None =>
          false
      }
    } else false
    
    if(inUse) false else rangesCache.remove((poolId, startIpAddress))
    
  }
     
  /**
   * Modifications
   */
  
  /**
   * Status is 0 if the range must not be used.
   * 
   * Returns true if the modification took place
   */
  def modifyRangeStatus(poolId: String, startIpAddress: Long, status: Int) = {
    val updateStmt = new SqlFieldsQuery("update \"RANGES\".range set stauts = ? where poolId = ? and startIpAddress = ?")
      .setArgs(
          status: java.lang.Integer,
          poolId: java.lang.String,
          startIpAddress: java.lang.Long
      )
        
    rangesCache.query(updateStmt).getAll.head.get(0).asInstanceOf[Long] == 1
  }
  
  /**
   * Priority is 0 if the PoolSelector is not used.
   * 
   * Returns true if the modification took place.
   */
  def modifyPoolSelectorPriority(selectorId: String, poolId: String, priority: Int) = {
    val updateStmt = new SqlFieldsQuery("update \"POOLSELECTORS\".PoolSelector set priority = ? where selectorId = ? and poolId = ?")
      .setArgs(
          priority: java.lang.Integer,
          selectorId: java.lang.String,
          poolId: java.lang.String
      )
        
    poolSelectorsCache.query(updateStmt).getAll.head.get(0).asInstanceOf[Long] == 1
  }
}