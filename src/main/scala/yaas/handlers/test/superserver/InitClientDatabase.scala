package yaas.handlers.test.superserver

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import yaas.server._
import yaas.server.MessageHandler
import org.apache.ignite.cache.query.SqlFieldsQuery
import org.apache.ignite.scalar._
import scalar._

class InitClientDatabase(statsServer: ActorRef) extends MessageHandler(statsServer) {
  
  if(cache$("CLIENTS").isEmpty){
    
    log.info("Creating Clients database")
  
    val clientCache = ignite$.getOrCreateCache[Nothing, Nothing]("CLIENTS")
    clientCache.query(new SqlFieldsQuery("CREATE TABLE \"CLIENTS\".Client (UserName varchar primary key, legacy_client_id varchar, planName varchar)"))
    
    // Populate
    for(i <- 0 to 1000){
      val q = new SqlFieldsQuery("INSERT INTO \"CLIENTS\".Client (UserName, legacy_client_id, planName) values (?, ?, ?)")
      q.setArgs("user_" + i, "legacy_" + i, "plan_" +  i)
      clientCache.query(q)
    }
    
    log.info("Clients database populated")
  } else log.info("Clients database already exists")
}