package yaas.handlers

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._

import scala.util.{Success, Failure}
import yaas.server.MessageHandler

import org.apache.ignite.scalar.scalar
import org.apache.ignite.scalar.scalar._
import org.apache.ignite.cache.query.SqlFieldsQuery

class TestSuperServerInitClientDatabase(statsServer: ActorRef) extends MessageHandler(statsServer) {
  
  log.info("Creating client database")
  
  // Create table
  val emptyCache = ignite$.getOrCreateCache[Nothing, Nothing]("CLIENTS")
  emptyCache.query(new SqlFieldsQuery("CREATE TABLE CLIENTS (CLIENT_ID int primary key, LEGACY_CLIENT_ID varchar, PLANNAME varchar)"))
  
  // Populate
  for(i <- 1 to 1000){
    val q = new SqlFieldsQuery("INSERT INTO CLIENTS (CLIENT_ID, LEGACY_CLIENT_ID, PLANNAME) values (?, ?, ?)")
    q.setArgs(i: java.lang.Integer, "legacy_" + i, "plan_" +  i)
    emptyCache.query(q)
  }
}