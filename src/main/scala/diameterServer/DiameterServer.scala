package diameterServer

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import akka.event.{Logging}
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.json4s._
import org.json4s.jackson.JsonMethods._
import scala.reflect.ManifestFactory.classType
import scala.reflect.runtime.universe

////////////////////////////////////////////////////////////////////////
// Main Diameter Object and application
////////////////////////////////////////////////////////////////////////

object Diameter extends App {
  
  val config = ConfigFactory.load()

	val actorSystem = ActorSystem("AAA")
	val diameterRouterActor = actorSystem.actorOf(DiameterRouter.props())
}

////////////////////////////////////////////////////////////////////////
// DiameterRouter
////////////////////////////////////////////////////////////////////////

case class DiameterConfig(bindAddress: String, bindPort: Int)

// Best practise
object DiameterRouter {
  
  def props() = Props(new DiameterRouter())
}

// Manages Routes, Peers and Handlers
class DiameterRouter() extends Actor {

	// Initialize logger
	val logger = Logging.getLogger(context.system, this)
	
	DiameterDictionary.show()
	
	// For deserialization of Json
  implicit val formats = DefaultFormats
  
  // General config
  val diameterConfig = ConfigManager.getConfigObject("diameterServer.json").extract[DiameterConfig]
  
  // Read peers configuration, build peer tables and create actors
  val peerHostsMap = scala.collection.mutable.Map[String, DiameterPeerPointer]()
  val peerIpAddressMap = scala.collection.mutable.Map[String, DiameterPeerPointer]()
  for(peer <- ConfigManager.getConfigObject("diameterPeers.json").extract[Seq[DiameterPeerConfig]]) {
    // This creates the DiameterPeer actors
    val diameterPeerPointer = DiameterPeerPointer(peer, context.actorOf(DiameterPeer.props(peer), peer.diameterHost))
    // Create the tables for indirection to peers
    peerHostsMap(peer.diameterHost) = diameterPeerPointer
    peerIpAddressMap(peer.IPAddress) = diameterPeerPointer
    
    logger.debug("Created Peer {}", diameterPeerPointer)
  }

  // Read routes and build routing table
  val routes = ConfigManager.getConfigObject("diameterRoutes.json").extract[Seq[DiameterRoute]]
  for(route <- routes){
    logger.debug("Added route: {}", route)
  }
  
  // Utility function to get a route
  def findRoute(realm: String, applicationId: String) : Option[DiameterRoute] = {
    for(route <- routes){
      if(route.realm == "*" || route.realm == realm) 
        if(route.applicationId == "*" || route.applicationId == applicationId) 
          return Some(route) // God forgives me
    }
    
    None
  }
  
  // Build handler table
  val handlersMap = scala.collection.mutable.Map[String, ActorRef]()
  for(handler <- ConfigManager.getConfigObject("diameterHandlers.json").extract[Seq[DiameterHandlerConfig]]){
    handlersMap(handler.applicationName) =  context.actorOf(DiameterMessageHandler.props(handler.handlerObject), handler.applicationName)
    logger.debug("Created handler {}", handler)
  }
  
  // Server socket
  implicit val actorSytem = context.system
  implicit val materializer = ActorMaterializer()
  val connectionsStream = Tcp().bind(diameterConfig.bindAddress, diameterConfig.bindPort)
  connectionsStream.runForeach(connection => {
    val remoteIPAddress = connection.remoteAddress.getAddress().getHostAddress()
    logger.info("Connection from {}", connection.remoteAddress.getAddress().getHostAddress())
    
    val closedFlow = Flow.fromSinkAndSource(Sink.cancelled, Source.empty)
    
    peerIpAddressMap.get(remoteIPAddress) match {
      case Some(peerConfig) => 
        logger.info("Connection for {}", peerConfig)
        peerConfig.actorRef ! connection
      case None => 
        logger.info("No peer found"); connection.handleWith(closedFlow)
    }

  })

	def receive  = {
		case any: Any => Nil
	}
}

////////////////////////////////////////////////////////////////////////
// DiameterPeers
////////////////////////////////////////////////////////////////////////

// This class represents a diameter peer as read from the configuration file
case class DiameterPeerConfig(diameterHost: String, IPAddress: String, port: Int, connectionPolicy: String, watchdogIntervalMillis: Int)

// Peer tables are made of this items
case class DiameterPeerPointer(config: DiameterPeerConfig, actorRef: ActorRef)

// Best practise
object DiameterPeer {
  
  def props(config: DiameterPeerConfig) = Props(new DiameterPeer(config))
}

class DiameterPeer(config: DiameterPeerConfig) extends Actor {
  
  // Initialize logger
  val logger = Logging.getLogger(context.system, this)
  
  implicit val materializer = ActorMaterializer()
  
  val echo = Flow[ByteString].via(Framing.delimiter(ByteString("\n"), 256, allowTruncation = true))
      .viaMat(KillSwitches.single)(Keep.right)
      .map(s =>s.utf8String)
      .map(_+"!")
      .map(line => {println(line); line})
      .map(ByteString(_))
  
  def receive = {
    case DiameterPeerConfig(diameterHost, ipAddr, port, connectionPolicy, watchdogIntervalMillis) => 
      Nil
    case connection: Tcp.IncomingConnection => 
      logger.info("I've been told to handle {}", connection.remoteAddress)
      connection.handleWith(echo)
  }
}

////////////////////////////////////////////////////////////////////////
// Routes
////////////////////////////////////////////////////////////////////////
case class DiameterRoute(realm: String, applicationId: String, peers: Array[String], policy: String)

////////////////////////////////////////////////////////////////////////
// DiameterMessageHandler
////////////////////////////////////////////////////////////////////////
case class DiameterHandlerConfig(applicationName: String, handlerObject: String)

trait DiameterApplicationHandler {
  def handleMessage
}

object DiameterMessageHandler {
  
  def props(handlerObjectName: String) = Props(new DiameterMessageHandler(handlerObjectName))
}

class DiameterMessageHandler(handlerObjectName: String) extends Actor {
  
  import scala.reflect.runtime.universe
  
  def getApplicationHandlerObject(clsName: String) = {
    
    // http://3x14159265.tumblr.com/post/57543163324/playing-with-scala-reflection
    val mirror = universe.runtimeMirror(getClass.getClassLoader)
    val module = mirror.staticModule(clsName)
    mirror.reflectModule(module).instance.asInstanceOf[DiameterApplicationHandler]   
  }
  
  val handlerInstance = getApplicationHandlerObject(handlerObjectName).asInstanceOf[DiameterApplicationHandler]
  
  def receive  = {
		case any: Any => Nil
	}
  
}

