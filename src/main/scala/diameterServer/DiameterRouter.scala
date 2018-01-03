package diameterServer

import akka.actor.{ActorSystem, Actor, ActorLogging, ActorRef, Props}
import akka.event.{Logging, LoggingReceive}
import scala.concurrent.Future
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import com.typesafe.config.ConfigFactory

import diameterServer.config.{DiameterConfigManager, DiameterRouteConfig, DiameterPeerConfig, DiameterHandlerConfig}
import diameterServer.coding.DiameterMessage
import diameterServer.coding.DiameterConversions._

case class DiameterRoute(realm: String, application: String, peers: Option[Array[String]], policy: Option[String], handler: Option[String])

// Best practise
object DiameterRouter {
  def props() = Props(new DiameterRouter())
  
  // Diameter messages
  case class RoutedDiameterMessage(message: DiameterMessage, owner: ActorRef)
}

// Manages Routes, Peers and Handlers
class DiameterRouter() extends Actor with ActorLogging {
  
  import DiameterRouter._
  
  // Empty initial maps to working objects
	var serverIPAddress = "0.0.0.0"
	var serverPort = 0
	var peerHostMap : Map[String, DiameterPeerPointer] = Map()
	var peerIPAddressMap : Map[String, DiameterPeerPointer] = Map()
	var handlerMap: Map[String, ActorRef] = Map()
	var diameterRoutes : Seq[DiameterRoute] = Seq()
	
	// First update of configuration
  val diameterConfig = DiameterConfigManager.getDiameterConfig
  serverIPAddress = diameterConfig.bindAddress
  serverPort = diameterConfig.bindPort
  updatePeerMap(DiameterConfigManager.getDiameterPeerConfig)
  updateDiameterRoutes(DiameterConfigManager.getDiameterRouteConfig)
  
  // Server socket
  implicit val actorSytem = context.system
  implicit val materializer = ActorMaterializer()
  startServerSocket(serverIPAddress, serverPort)
	
	/**
	 * Will create the actors for the configured peers and shutdown the ones not configured anymore.
	 */
	def updatePeerMap(conf: Map[String, DiameterPeerConfig]) = {
	  
	  // Shutdown unconfigured peers and return clean list  
	  val cleanPeersHostMap = peerHostMap.flatMap { case (hostName, peerPointer) => 
	    if(conf.get(hostName) == None){context.stop(peerPointer.actorRef); Seq()} else Seq((hostName, peerPointer))
	    }
	  
	  // Create new peers if needed
	  val newPeerHostMap = conf.map { case (hostName, peerConfig) => 
	    // Create if needed
	    if(cleanPeersHostMap.get(hostName) == None) (hostName, DiameterPeerPointer(peerConfig, context.actorOf(DiameterPeer.props(peerConfig), hostName)))
	    // Was already created
	    else (hostName, cleanPeersHostMap(hostName))
	  }
	  
	  // Update maps
	  peerHostMap = newPeerHostMap
	  peerIPAddressMap = newPeerHostMap.map {case (peerHost, peerPointer) => (peerPointer.config.IPAddress, peerPointer)}
	}
  
  def updateHandlerMap(conf: Map[String, String]) = {
    // Shutdown unconfigured handlers
    val cleanHandlerMap = handlerMap.flatMap { case (handlerName, handlerActor) =>
      if(conf.get(handlerName) == None) {context.stop(handlerActor); Seq()} else Seq((handlerName, handlerActor))
    }
    
    // Create new handlers if needed
    val newHandlerMap = conf.map { case (name, clazz) => 
      if(cleanHandlerMap.get(name) == None) (name, context.actorOf(Props(Class.forName(clazz).asInstanceOf[Class[Actor]], name+"-handler")))
      // Already created
      else (name, cleanHandlerMap(name))
    }
    
    handlerMap = newHandlerMap
  }
	
	def updateDiameterRoutes(conf: Seq[DiameterRouteConfig]){
	  // Just copy
	  val newDiameterRoutes = for {
	    route <- conf
	  } yield DiameterRoute(route.realm, route.applicationId, route.peers, route.policy, route.handler)
	      
	  diameterRoutes = newDiameterRoutes
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
	   * Diameter messages
	   */
	  case message : DiameterMessage =>
	    // Diameter message received without actorRef => requires routing
	    findRoute(message >> "Destination-Realm", message.application) match {
	      // Local handler
	      case Some(DiameterRoute(realm, application, peers, policy, Some(handler))) =>
	        // Handle locally
	        handlerMap.get(handler) match {
	          case Some(handlerActor) =>  handlerActor ! RoutedDiameterMessage(message, context.sender)
	          case None => log.warning("Attempt to rotue message to a non exising handler {}", handler)
	        }
	      
	      // Send to Peer
	      case Some(DiameterRoute(realm, application, Some(peers), policy, _)) => 
	        peerHostMap.get(peers(0)) match { // Only one peer supported
	          case Some(peerPointer) => peerPointer.actorRef ! RoutedDiameterMessage(message, context.sender)
	          case None => log.warning("Attempt to rotue message to a non exising peer {}", peers(0))
	        }
	      // No route
	      case _ =>
	        log.warning("No route found for {} and {}", message >> "Destination-Realm", message.application)
	    }

		case any: Any => Nil
	}
  
}