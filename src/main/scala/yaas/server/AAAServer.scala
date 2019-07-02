package yaas.server

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import scala.util.{Try, Success, Failure}
import com.typesafe.config.ConfigFactory

import yaas.dictionary.{DiameterDictionary, RadiusDictionary}

////////////////////////////////////////////////////////////////////////
// Main Diameter Object and application
////////////////////////////////////////////////////////////////////////

object AAAServer extends App {
  
  java.security.Security.setProperty("networkaddress.cache.ttl" , "60");
  java.security.Security.setProperty("networkaddress.cache.negative.ttl " , "30");
  
  // Set config.file property as aaa-<instance>.conf, where <instance> is -Dinstance value or "default"
  val ti = System.getProperty("instance")
  val instance = if(ti == null) "default" else ti
  if(System.getProperty("config.file") == null && 
      System.getProperty("config.url") == null &&
      System.getProperty("config.resource") == null) 
    System.setProperty("config.resource", s"aaa-${instance}.conf")
  
  // Logging configuration file is logback-<instance>.xml if found, otherwise logback.xml in the classpath
  if(System.getProperty("logback.configurationFile") == null){
    Try(getClass.getResource(s"/logback-${instance}.xml")) match {
      case Success(r) => 
        System.setProperty("logback.configurationFile", s"logback-${instance}.xml")
      case Failure(e) =>
    }
  }
  
  val config = ConfigFactory.load()
	
	// Create dictionary. Just to do initialization of the Singleton
	val diameterDictionary = DiameterDictionary
	val radiusDictionary = RadiusDictionary
	
	// Start ignite database if configured
	if(config.getString("aaa.sessionsDatabase.role") != "none") yaas.database.SessionDatabase.init
	
	// The router will create the peers and handlers
  val actorSystem = ActorSystem("AAA")
	val routerActor = actorSystem.actorOf(Router.props())
	
	// Start sessions database and IPAM REST server
	val databaseRole = config.getString("aaa.sessionsDatabase.role")
	if(databaseRole == "server") actorSystem.actorOf(yaas.database.SessionRESTProvider.props())
}
