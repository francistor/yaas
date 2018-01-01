package diameterServer

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import akka.actor.ActorLogging
import akka.event.{Logging, LoggingReceive}
import scala.concurrent.Future
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import com.typesafe.config.ConfigFactory

import diameterServer.config.{DiameterConfigManager, DiameterRouteConfig, DiameterPeerConfig}
import diameterServer.coding.DiameterMessage
import diameterServer.coding.DiameterConversions._

case class DiameterRoute(realm: String, application: String, peers: Option[Array[String]], policy: Option[String], handlerActor: Option[ActorRef])

// Best practise
object DiameterRouter {
  def props() = Props(new DiameterRouter())
  
  // Configuration messages
  case class DiameterServerConfigMsg(ipAddress: String, port: Int)
  case class PeerConfigMsg(peerHostMapConfig: Map[String, DiameterPeerConfig])
  case class RouteConfigMsg(routeConfig: Seq[DiameterRouteConfig])
  case class HandlerConfigMsg(handlerConfig: Map[String, String])
  
  // Diameter messages
  case class RoutedDiameterMessage(message: DiameterMessage, owner: ActorRef)
}

// Manages Routes, Peers and Handlers
class DiameterRouter() extends Actor with ActorLogging {
  
  import DiameterRouter._
	
	// Start Configuration Actor
	val diameterConfigurationActor = context.actorOf(DiameterConfigManager.props(), "DiameterConfigManagerActor")
		
	// Empty initial maps to working objects
	var serverIPAddress = "0.0.0.0"
	var serverPort = 0
	var peerHostMap : Map[String, DiameterPeerPointer] = Map()
	var peerIPAddressMap : Map[String, DiameterPeerPointer] = Map()
	var diameterRoutes : Seq[DiameterRoute] = Seq()
	
	/**
	 * Will create the actors for the configured peers and shutdown the ones not configured anymore.
	 * Returns the new peerMap
	 */
	def updatePeerMap(conf: Map[String, DiameterPeerConfig]) = {
	  
	  // Shutdown unconfigured peers and return clean list  
	  val cleanPeersHostMap = peerHostMap.flatMap { case (hostName, peerPointer) => 
	    if(!conf.values.toList.contains(peerPointer)){context.stop(peerPointer.actorRef); Seq()} else Seq((hostName, peerPointer))
	    }
	  
	  // Create new peers if needed
	  val cleanHostList = cleanPeersHostMap.keys.toList
	  val newPeerHostMap = conf.map { case (hostName, peerConfig) => 
	    // Create if needed
	    if(!cleanHostList.contains(hostName)) (hostName, DiameterPeerPointer(peerConfig, context.actorOf(DiameterPeer.props(peerConfig), hostName)))
	    // Was already created
	    else (hostName, cleanPeersHostMap(hostName))
	  }
	  
	  // Update maps
	  peerHostMap = newPeerHostMap
	  peerIPAddressMap = newPeerHostMap.map {case (peerHost, peerPointer) => (peerPointer.config.IPAddress, peerPointer)}
	}
	
	def updateDiameterRoutes(conf: Seq[DiameterRouteConfig]){
	  // Recreate all the handlers
	  val newDiameterRoutes = for {
	    route <- conf
	    actorRef = route.handlerObject match {
	      case Some(hObj) => 
	        log.info("Instantating Actor for {}", hObj)
	        Some(context.actorOf(DiameterMessageHandler.props(hObj)))
	      case None => None
	    }
	  } yield DiameterRoute(route.realm, route.applicationId, route.peers, route.policy, actorRef)
	  
	  // Swap
	  val oldDiameterRoutes = diameterRoutes
	  diameterRoutes = newDiameterRoutes
	  
	  // Shutdown old handlers
	  log.info("Shutting down handlers")
	  oldDiameterRoutes.map(route => {
	    route.handlerActor match {
	      case Some(actorRef) => 
	        context.stop(actorRef)
	      case None => Unit
	    }
	  })
	}
	
	// Utility function to get a route
  def findRoute(realm: String, application: String) : Option[DiameterRoute] = {
    for(route <- diameterRoutes){
      if(route.realm == "*" || route.realm == realm) 
        if(route.application == "*" || route.application == application) 
          return Some(route) // God forgives me
    }
    
    None
  }
	
  // Server socket
  implicit val actorSytem = context.system
  implicit val materializer = ActorMaterializer()

  def startServerSocket(ipAddress: String, port: Int) = {
    
    val futBinding = Tcp().bind(ipAddress, port).toMat(Sink.foreach(connection => {
      val remoteAddress = connection.remoteAddress.getAddress().getHostAddress()
      log.info("Connection from {}", remoteAddress)
      
      // Send connection to peer
      peerIPAddressMap.get(remoteAddress) match {
        case Some(peerPointer) =>
          log.info("Connection for {}", peerPointer)
          peerPointer.actorRef ! connection
          
        case None =>
          log.info("No peer found for {}", remoteAddress);
          connection.handleWith(Flow.fromSinkAndSource(Sink.cancelled, Source.empty))
      }
    }))(Keep.left).run()
    
    import scala.util.{Try, Success, Failure}
    import context.dispatcher
    
    futBinding.onComplete(t => {
      t match {
        case Success(binding) =>
          log.info("Bound")
        case Failure(exception) =>
          log.error("Could not bind socket {}", exception.getMessage())
          context.system.terminate()
      }
    })
  }
  

	def receive  = LoggingReceive {
	  
	  /*
	   * Configuration messages
	   */
	  case DiameterServerConfigMsg(ipAddress, port) =>
	    // This one cannot change without restart
	    startServerSocket(ipAddress, port)
	    
	  case PeerConfigMsg(peerConfig) =>
	    updatePeerMap(peerConfig)
	    
	  case RouteConfigMsg(routeConfig) =>
	    updateDiameterRoutes(routeConfig)
	    
	  /*
	   * Diameter messages
	   */
	  case message : DiameterMessage =>
	    // Diameter message received without actorRef => requires routing
	    
	    findRoute(message >> "Destination-Realm", message.application) match {
	      case Some(DiameterRoute(realm, application, peers, policy, handler)) =>
	        // Route found
	        handler match {
	          case Some(actorRef) => actorRef ! RoutedDiameterMessage(message, context.sender)
	          case None => log.error("To be implemented later") // Route to another server
	        }
	      case None =>
	        log.warning("No route found for {} and {}", message >> "Destination-Realm", message.application)
	    }

	    
		case any: Any => Nil
	}
  
}