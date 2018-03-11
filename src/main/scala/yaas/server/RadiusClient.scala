package yaas.server

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill }
import akka.event.{ Logging, LoggingReceive }
import yaas.config.RadiusServerConfig

// This class handles the communication with an upstream radius server

object RadiusClient {
  def props(bindIPAddress: String, bindPort: Int) = Props(new RadiusClient(bindIPAddress, bindPort))
}

class RadiusClient(bindIPAddress: String, bindPort: Int) extends Actor with ActorLogging {
  
  
  def receive = {
    case _ => Nil
  }
}