package yaas.server

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import com.typesafe.config.ConfigFactory

import yaas.dictionary.{DiameterDictionary, RadiusDictionary}

////////////////////////////////////////////////////////////////////////
// Main Diameter Object and application
////////////////////////////////////////////////////////////////////////

object AAAServer extends App {
  
  val config = ConfigFactory.load()
	
	// Create dictionary. Just to do initialization of the Singleton
	val diameterDictionary = DiameterDictionary
	val radiusDictionary = RadiusDictionary
	
	// Start ignite database if configured
	yaas.database.SessionDatabase.init
	
	// The router will create the peers and handlers
  val actorSystem = ActorSystem("AAA")
	val routerActor = actorSystem.actorOf(Router.props())
	
	// Start database query server
	val databaseRole = config.getString("aaa.sessionsDatabase.role")
	if(databaseRole == "server") actorSystem.actorOf(yaas.database.SessionRESTProvider.props())
}
