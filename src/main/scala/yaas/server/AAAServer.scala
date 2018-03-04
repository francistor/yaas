package yaas.server

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import com.typesafe.config.ConfigFactory

import yaas.dictionary.{DiameterDictionary, RadiusDictionary}

////////////////////////////////////////////////////////////////////////
// Main Diameter Object and application
////////////////////////////////////////////////////////////////////////

object AAAServer extends App {
  
  val config = ConfigFactory.load()
  
	val actorSystem = ActorSystem("AAA")
	
	// Create dictionary. Just to do initialization of the Singleton
	val diameterDictionary = DiameterDictionary
	val radiusDictionary = RadiusDictionary
	
	// The router will create the peers and handlers
	val routerActor = actorSystem.actorOf(Router.props())
}
