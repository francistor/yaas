package yaas.handlers.test.superserver

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import yaas.server._
import yaas.server.MessageHandler
import org.apache.ignite.cache.query.SqlFieldsQuery
import org.apache.ignite.scalar._
import scalar._

class InitClientDatabase(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {
  
  if(cache$("CLIENTS").isEmpty){
    
    log.info("Creating Clients database")
  
    val clientCache = ignite$.getOrCreateCache[Nothing, Nothing]("CLIENTS")
    clientCache.query(new SqlFieldsQuery("""
      CREATE TABLE "CLIENTS".Client (
        NASIPAddress varchar,
        NASPort bigint,
        UserName varchar,
        Password varchar,
        LegacyClientId varchar not null, 
        serviceName varchar,
        addonServiceName varchar,
        primary key (NASIPAddress, NASPort)
      )
      """))
    
    // Populate
    for(i <- 0 to 1000){
      val q = new SqlFieldsQuery("""
        INSERT INTO "CLIENTS".Client 
        (NASIPAddress, NASPort, UserName, Password, LegacyClientId, serviceName, addonServiceName) values 
        (?, ?, ?, ?, ?, ?, ?)
        """)
  
      q.setArgs(
          "1.1.1.1": java.lang.String, 
          i: java.lang.Integer, 
          s"user_$i@clientdb.accept": java.lang.String, 
          s"password!_$i": java.lang.String,
          "legacy_" + i: java.lang.String, 
          "service_" + (i % 4): java.lang.String,
          if(i % 4 == 0) "addon_1" else null
          )
      clientCache.query(q)
    }
    
    log.info("Clients database populated")
  } else log.info("Clients database already exists")
}