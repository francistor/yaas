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
	
  val actorSystem = ActorSystem("AAA")
	
	// The router will create the peers and handlers
	val routerActor = actorSystem.actorOf(Router.props())
}
