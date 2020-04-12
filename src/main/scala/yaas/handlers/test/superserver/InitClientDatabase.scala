package yaas.handlers.test.superserver

import akka.actor.ActorRef
import org.apache.ignite.cache.query.SqlFieldsQuery
import org.apache.ignite.scalar.scalar._
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
  
  if(cache$("CLIENTS").isEmpty){
    
    log.info("Creating Clients database")
    
    import org.apache.ignite.cache.CacheMode._
    val clientCache = ignite$.getOrCreateCache[Nothing, Nothing](new org.apache.ignite.configuration.CacheConfiguration[Nothing, Nothing].setName("CLIENTS").setCacheMode(REPLICATED))

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
}