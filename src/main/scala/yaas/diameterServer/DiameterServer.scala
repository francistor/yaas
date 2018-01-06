package yaas.diameterServer

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import com.typesafe.config.ConfigFactory

import yaas.diameterServer.dictionary.DiameterDictionary

////////////////////////////////////////////////////////////////////////
// Main Diameter Object and application
////////////////////////////////////////////////////////////////////////

object Diameter extends App {
  val config = ConfigFactory.load()
  
	val actorSystem = ActorSystem("AAA")
	
	// Create dictionary
	val dictionary = DiameterDictionary
	
	val diameterRouterActor = actorSystem.actorOf(DiameterRouter.props())
}







