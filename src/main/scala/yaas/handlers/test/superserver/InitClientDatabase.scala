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
    //  2 is fixed IP and vala. Usability 4
    //  3 has standard addon, and null password
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
        (USER_LINE_ID, CLIENT_ID, NASIP_ADDRESS, NASPORT, USERNAME, PASSWORD, GUIDING_TYPE, IP_ADDRESS, IPV6_DELEGATED_PREFIX, USABILITY) values
        (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).setArgs(
        i: java.lang.Integer,
        i: java.lang.Integer,
        "1.1.1.1": java.lang.String,
        i: java.lang.Integer,
        s"user_$i@clientdb.accept": java.lang.String,
        if(i % 4 != 3) s"password!_$i" else null : java.lang.String,
        1: java.lang.Integer,
        if(i == 2) "100.100.100.100" else null : java.lang.String,
        if(i == 2) "bebe:cafe::0/64" else null : java.lang.String,
        (if(i == 2) 4 else 0): java.lang.Integer
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