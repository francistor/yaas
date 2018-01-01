package diameterServer

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import com.typesafe.config.ConfigFactory


////////////////////////////////////////////////////////////////////////
// Main Diameter Object and application
////////////////////////////////////////////////////////////////////////

object Diameter extends App {
  val config = ConfigFactory.load()
  
	val actorSystem = ActorSystem("AAA")
	val diameterRouterActor = actorSystem.actorOf(DiameterRouter.props())
}







