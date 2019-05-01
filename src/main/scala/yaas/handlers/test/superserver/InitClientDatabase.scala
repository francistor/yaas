package yaas.handlers.test.superserver

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import yaas.server._
import yaas.server.MessageHandler
import org.apache.ignite.cache.query.SqlFieldsQuery
import org.apache.ignite.scalar._
import scalar._

class InitClientDatabase(statsServer: ActorRef) extends MessageHandler(statsServer) {
  
  
  if(cache$("CLIENTS").isEmpty){
    
    log.info("Creating client database")
  
    // Create table
    val clientCache = ignite$.getOrCreateCache[Nothing, Nothing]("CLIENTS")
    clientCache.query(new SqlFieldsQuery("CREATE TABLE CLIENTS (USERNAME varchar primary key, LEGACY_CLIENT_ID varchar, PLANNAME varchar)"))
    
    // Populate
    for(i <- 0 to 1000){
      val q = new SqlFieldsQuery("INSERT INTO CLIENTS (USERNAME, LEGACY_CLIENT_ID, PLANNAME) values (?, ?, ?)")
      q.setArgs("user_" + i, "legacy_" + i, "plan_" +  i)
      clientCache.query(q)
    }
    
    log.info("Client database populated")
  } else log.info("Client database already exists")
}