package yaas.handlers.test.superserver

import akka.actor.ActorRef
import org.apache.ignite.Ignition
import org.apache.ignite.cache.query.SqlFieldsQuery
import yaas.server.MessageHandler

/**
 * Write 1000 entries in the CLIENT database
 * username: user_<i>@clientdb.accept
 * password: password!_<i>
 * nasipaddress: 1.1.1.1
 * nasport: <i>
 *
 * @param statsServer not used here
 * @param configObject not used here
 */
class InitClientDatabase(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {

  /*
  import org.mongodb.scala._
  import scala.concurrent.Await
  import scala.concurrent.duration._
  import scala.util.{Failure, Success}
  import org.json4s._

  implicit val formats: DefaultFormats.type = DefaultFormats

  private val dbConf = yaas.config.ConfigManager.getConfigObjectAsJson("handlerConf/clientsDatabaseMongo.json")
  private val mongoClient = MongoClient((dbConf \ "url").extract[String])
  private val clientsDatabase = mongoClient.getDatabase("CLIENTS")

  Await.result(clientsDatabase.getCollection("Clients").drop().toFuture, 1.second)

  private val documents = for(i <- 0 to 1000) yield
    Document(
      "NASIPAddress" -> "1.1.1.1",
      "UserName" -> s"user_$i@clientdb.accept",
      "NASPort" -> i,
      "Password" -> s"password!_$i",
      "LegacyClientId"-> s"legacy_$i",
      "Status" -> (if((i % 11) == 0) 2 else 0),
      "ServiceName" -> s"service_${i % 4}",
      "AddonServiceName" -> (if(i % 4 == 0) Some("addon_1") else None)
    )

  clientsDatabase.getCollection("Clients").insertMany(documents).toFuture.onComplete{
    case Success(res) => println(res.toString)
    case Failure(e) => println(e.getMessage)
  }
  */

  /*
  if(!Ignition.ignite.cacheNames().contains("CLIENTS")){
    
    log.info("Creating Clients database")
    
    import org.apache.ignite.cache.CacheMode._
    val clientCache = Ignition.ignite.getOrCreateCache[Nothing, Nothing](new org.apache.ignite.configuration.CacheConfiguration[Nothing, Nothing].setName("CLIENTS").setCacheMode(REPLICATED).setSqlSchema("CLIENTS"))

    clientCache.query(new SqlFieldsQuery("""
      CREATE TABLE "CLIENTS".Client (
        NASIPAddress varchar,
        NASPort bigint,
        UserName varchar,
        Password varchar,
        LegacyClientId varchar not null, 
        Status int,
        ServiceName varchar,
        AddonServiceName varchar,
        primary key (NASIPAddress, NASPort)
      )
      """))
    
    // Populate
    for(i <- 0 to 1000){
      val q = new SqlFieldsQuery("""
        INSERT INTO "CLIENTS".Client
        (NASIPAddress, NASPort, UserName, Password, LegacyClientId, Status, ServiceName, AddonServiceName) values 
        (?, ?, ?, ?, ?, ?, ?, ?)
        """)
  
      q.setArgs(
          "1.1.1.1": java.lang.String, 
          i: java.lang.Integer, 
          s"user_$i@clientdb.accept": java.lang.String, 
          s"password!_$i": java.lang.String,
          "legacy_" + i: java.lang.String, 
          (if((i % 11) == 0) 2 else 0): java.lang.Integer, 
          "service_" + (i % 4): java.lang.String,
          if(i % 4 == 0) "addon_1" else null
          )
      clientCache.query(q)
    }
    
    log.info("Clients database populated")

  } else log.info("Clients database already exists")
  */

  if(!Ignition.ignite.cacheNames().contains("CLIENTS")){

    log.info("Creating Clients database")

    import org.apache.ignite.cache.CacheMode._
    val clientCache = Ignition.ignite.getOrCreateCache[Nothing, Nothing](new org.apache.ignite.configuration.CacheConfiguration[Nothing, Nothing].setName("CLIENTS").setCacheMode(REPLICATED).setSqlSchema("CLIENTS"))

    clientCache.query(new SqlFieldsQuery("""
      CREATE TABLE Client (
        CLIENT_ID bigInt not null,
        LEGACY_CLIENT_ID varchar not null,
        PHONE varchar not null,
        COMMERCIAL_SEGMENT varchar,
        ISP_NAME varchar,
        FULLNAME varchar,
        BLOCKING_STATE bigInt,
        PLAN_NAME varchar,
        TIMEZONE varchar,
        BILLING_CYCLE_ID bigInt,
        CREATED_DATE_UTC Timestamp not null,
        DELETED_DATE_UTC Timestamp,
        LEGACY_MAIN_SUBSCRIPTION_ID  varchar,
        LEGACY_CLIENT_ID_SEC  varchar,
        CREATED_DATE_LOCAL Timestamp,
        DELETED_DATE_LOCAL Timestamp,
        OPC_CL_INFO_00 varchar,
        OPC_CL_INFO_01 varchar,
        OPC_CL_INFO_02 varchar,
        OPC_CL_INFO_03 varchar,
        OPC_CL_INFO_04 varchar,
        OPC_CL_INFO_05 varchar,
        OPC_CL_INFO_06 varchar,
        OPC_CL_INFO_07 varchar,
        OPC_CL_INFO_08 varchar,
        OPC_CL_INFO_09 varchar,
        PRIMARY KEY (CLIENT_ID)
      ) WITH "TEMPLATE=REPLICATED"
      """))

    clientCache.query(new SqlFieldsQuery("""
      CREATE TABLE Userline(
        USER_LINE_ID bigInt not null,
        CLIENT_ID bigInt not null,
        NASPORT bigInt,
        NASIP_ADDRESS varchar,
        USERNAME varchar,
        PASSWORD varchar,
        IP_ADDRESS varchar,
        GUIDING_TYPE bigInt not null,
        CPE_IP_ADDRESS varchar,
        REALM varchar,
        USABILITY bigInt,
        IPV6_DELEGATED_PREFIX varchar,
        IPV6_WAN_PREFIX varchar,
        IPV6_WAN_INTERFACE_ID varchar,
        OPC_UL_INFO_00 varchar,
        OPC_UL_INFO_01 varchar,
        OPC_UL_INFO_02 varchar,
        OPC_UL_INFO_03 varchar,
        OPC_UL_INFO_04 varchar,
        OPC_UL_INFO_05 varchar,
        OPC_UL_INFO_06 varchar,
        OPC_UL_INFO_07 varchar,
        OPC_UL_INFO_08 varchar,
        OPC_UL_INFO_09 varchar,
        PRIMARY KEY (USER_LINE_ID)
      ) WITH "TEMPLATE=REPLICATED"
      """))

    clientCache.query(new SqlFieldsQuery("""
      CREATE TABLE ServicePlan (
        PLAN_NAME varchar not null,
        SERVICE_NAME varchar not null,
        SERVICE_TYPE varchar not null,
        PRIORITY bigInt,
        FILTER varchar,
        PRIMARY KEY (PLAN_NAME, SERVICE_NAME)
      ) WITH "TEMPLATE=REPLICATED"
      """))

    // Populate
    // Every 4 clients
    //  0 is normal
    //  1 is blocked
    //  2 is fixed IP and vala
    //  3 has standard addon
    for(i <- 0 to 1000){
      val clientQuery = new SqlFieldsQuery("""
        INSERT INTO Client
        (CLIENT_ID, LEGACY_CLIENT_ID, PHONE, BLOCKING_STATE, PLAN_NAME, CREATED_DATE_UTC, OPC_CL_INFO_09, OPC_CL_INFO_03, OPC_CL_INFO_04) values
        (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).setArgs(
        i: java.lang.Integer,
        s"legacy_$i": java.lang.String,
        s"phone_$i": java.lang.String,
        (if((i % 4) == 1) 2 else 0): java.lang.Integer,
        "plan_" + (i % 4): java.lang.String,
        new java.util.Date(): java.util.Date,
        if(i % 4 == 3) "addon_1" else null,
        if(i % 4 == 2) "vala" else null,
        if(i % 4 == 2) "addon_vala" else null

      )
      clientCache.query(clientQuery)

      val userLineQuery = new SqlFieldsQuery("""
        INSERT INTO UserLine
        (USER_LINE_ID, CLIENT_ID, NASIP_ADDRESS, NASPORT, USERNAME, PASSWORD, GUIDING_TYPE, IP_ADDRESS, IPV6_DELEGATED_PREFIX) values
        (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).setArgs(
        i: java.lang.Integer,
        i: java.lang.Integer,
        "1.1.1.1": java.lang.String,
        i: java.lang.Integer,
        s"user_$i@clientdb.accept": java.lang.String,
        s"password!_$i": java.lang.String,
        1: java.lang.Integer,
        if(i == 2) "100.100.100.100" else null: java.lang.String,
        if(i == 2) "bebe:cafe::0/64" else null: java.lang.String
      )
      clientCache.query(userLineQuery)
    }

    for(i <- 0 to 3) {
      val servicesQuery = new SqlFieldsQuery(
        """
           INSERT INTO ServicePlan (PLAN_NAME, SERVICE_NAME, SERVICE_TYPE) values (?, ?, 'autoactivatedservice')
       """).setArgs(
        s"plan_$i": java.lang.String,
        s"service_$i": java.lang.String
      )

      clientCache.query(servicesQuery)
    }
    log.info("Clients database populated")

  } else log.info("Clients database already exists")



}