package yaas.server

/*

The basic set of Actors are Peer / Router / Handler

Peer actors
-----------

Incoming messages (got from TCPStream handler)
	request -> send to router as diameterMessage
	reply -> look up in cache the h2hId and send as diameterMessage to the actor that sent the request
	
Outgoing messages (got as actor messages)
	request (RoutedDiameterMessage) -> store in cache, along with the actor that sent the request, and send to peer
	reply (DiameterMessage) -> send to peer
	
In addition, Peers may act as "active" or "passive". "Passive" peers are created by the Router after an incoming
connection is received, and register in the Router with a DiameterPeerConfig message

Peers send periodically to themselves Clean() messages to purge old cache entries
	
Router Actor
------------

Receive DiameterMessage(s), either from a Peer Actor (incoming requests) or from a Handler (generated requests) and
send RoutedDiameterMessage(s) either to a Peer Actor or a Handler, depending on the route configuration

Handler Actor
-------------

Incoming messages (got as actor messages)
	request (RoutedDiameterMessage) -> processed and answered directly to the sending actor
	reply (DiameterMessage) -> look up in cache for the callback to invoke
	
Outgoing messages (create actor messages)
	request (RoutedDiameterMessage) -> store in cache and send to Router
	reply (DiameterMessage) -> send to Peer that sent the request

 */

import akka.actor.{ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill}
import akka.event.{Logging, LoggingReceive}
import scala.concurrent.Future
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import com.typesafe.config.ConfigFactory

import yaas.config.{DiameterConfigManager, DiameterRouteConfig, DiameterPeerConfig}
import yaas.config.{RadiusConfigManager, RadiusThisServerConfig, RadiusPorts, RadiusServerConfig, RadiusServerGroupConfig, RadiusClientConfig}
import yaas.config.{HandlerConfigManager, HandlerConfig}
import yaas.coding.diameter.DiameterMessage
import yaas.coding.diameter.DiameterConversions._
import yaas.coding.radius.RadiusPacket
import yaas.server.RadiusActorMessages._

case class DiameterRoute(realm: String, application: String, peers: Option[Array[String]], policy: Option[String], handler: Option[String])
// Peer tables are made of these items
case class DiameterPeerPointer(config: DiameterPeerConfig, actorRefOption: Option[ActorRef])
case class RadiusServerPointer(config: RadiusServerConfig, var isAlive: Boolean, var nextTimestamp: Long, var accErrors: Int){
  def getPort(code: Int) = {
    code match {
      case RadiusPacket.ACCESS_REQUEST => config.ports.auth
      case RadiusPacket.ACCOUNTING_REQUEST => config.ports.acct
      case RadiusPacket.COA_REQUEST => config.ports.coA
      case RadiusPacket.DISCONNECT_REQUEST => config.ports.dm
    }
  }
}

// Best practise
object Router {
  def props() = Props(new Router())
  
  // Actor messages
  case class RoutedDiameterMessage(message: DiameterMessage, owner: ActorRef)
  case class PeerDown()
}

// Manages Routes, Peers and Handlers
class Router() extends Actor with ActorLogging {
  
  import Router._
  
  // Empty initial maps to working objects
  // Diameter
	var diameterServerIPAddress = "0.0.0.0"
	var diameterServerPort = 0
	var peerHostMap : scala.collection.mutable.Map[String, DiameterPeerPointer] = scala.collection.mutable.Map()
	var handlerMap: Map[String, ActorRef] = Map()
	var diameterRoutes : Seq[DiameterRoute] = Seq()
	
	// Radius
	var radiusServerIPAddress = "0.0.0.0"
	var radiusServerAuthPort = 0
	var radiusServerAcctPort = 0
	var radiusServerCoAPort = 0
	var radiusServers = Map[String, RadiusServerPointer]()
  var radiusServerGroups = Map[String, RadiusServerGroupConfig]()
	var radiusClients = Map[String, RadiusClientConfig]()
	
	// First update of diameter configuration
  val diameterConfig = DiameterConfigManager.getDiameterConfig
  diameterServerIPAddress = diameterConfig.bindAddress
  diameterServerPort = diameterConfig.bindPort
  updateDiameterPeerMap(DiameterConfigManager.getDiameterPeerConfig)
  updateDiameterRoutes(DiameterConfigManager.getDiameterRouteConfig)
  
  // First update of radius configuration
  val radiusConfig = RadiusConfigManager.getRadiusConfig
  radiusServerIPAddress = radiusConfig.bindAddress
  radiusServerAuthPort = radiusConfig.authBindPort
  radiusServerAcctPort = radiusConfig.acctBindPort
  radiusServerCoAPort = radiusConfig.coABindPort
  radiusServerGroups = RadiusConfigManager.getRadiusServerGroups
  radiusClients = RadiusConfigManager.getRadiusClients
  radiusServers = getRadiusServerPointers(RadiusConfigManager.getRadiusServers)
  
  updateHandlerMap(HandlerConfigManager.getHandlerConfig)
  
  // Diameter Server socket
  implicit val actorSytem = context.system
  implicit val materializer = ActorMaterializer()
  startDiameterServerSocket(diameterServerIPAddress, diameterServerPort)
  
  // Radius server actors
  newRadiusAuthServerActor(radiusServerIPAddress, radiusServerAuthPort)
  newRadiusAcctServerActor(radiusServerIPAddress, radiusServerAcctPort)
  newRadiusCoAServerActor(radiusServerIPAddress, radiusServerCoAPort)
  
  // Radius client
  val radiusClientActor = context.actorOf(RadiusClient.props(radiusServerIPAddress, radiusConfig.clientBasePort, radiusConfig.numClientPorts), "RadiusClient")
  
  ////////////////////////////////////////////////////////////////////////
  // Diameter configuration
  ////////////////////////////////////////////////////////////////////////
	
	/**
	 * Will create the actors for the configured peers and shutdown the ones not configured anymore.
	 */
	def updateDiameterPeerMap(conf: Map[String, DiameterPeerConfig]) = {
	  
	  // Shutdown unconfigured peers and return clean list  
	  val cleanPeersHostMap = peerHostMap.flatMap { case (hostName, peerPointer) => 
	    if(conf.get(hostName) == None){
	      // Stop peer and return empty sequence
	      // TODO: Do same as in getRadiusServerPointers
	      peerPointer.actorRefOption.map(context.stop(_)); Seq()}
	    else
	      // Leave as it is
	      Seq((hostName, peerPointer))
	  }
	  
	  // Create new peers if needed
	  val newPeerHostMap = conf.map { case (hostName, peerConfig) => 
	    // Create if needed
	    if(cleanPeersHostMap.get(hostName) == None){
	      if(peerConfig.connectionPolicy.equalsIgnoreCase("active")){
	        // Create actor, which will initiate the connection.
	       (hostName, DiameterPeerPointer(peerConfig, Some(context.actorOf(DiameterPeer.props(Some(peerConfig)), hostName))))
	      }
	      else {
	        (hostName, DiameterPeerPointer(peerConfig, None))
	      }
	    }
	    // Was already created
	    else (hostName, cleanPeersHostMap(hostName))
	  }
	  
	  // Update maps
	  // TODO: Do same as in getRadiusServerPointers (return instead of modify var)
	  peerHostMap = scala.collection.mutable.Map() ++ newPeerHostMap
	}
  
  def updateHandlerMap(conf: Map[String, String]) = {
    // Shutdown unconfigured handlers
    val cleanHandlerMap = handlerMap.flatMap { case (handlerName, handlerActor) =>
      if(conf.get(handlerName) == None) {context.stop(handlerActor); Seq()} else Seq((handlerName, handlerActor))
    }
    
    // Create new handlers if needed
    val newHandlerMap = conf.map { case (name, clazz) => 
      if(cleanHandlerMap.get(name) == None) (name, context.actorOf(Props(Class.forName(clazz).asInstanceOf[Class[Actor]]), name + "-handler"))
      // Already created
      else (name, cleanHandlerMap(name))
    }
    
    handlerMap = Map() ++ newHandlerMap
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
    diameterRoutes.find{ route => (route.realm == "*" || route.realm == realm) && (route.application == "*" || route.application == application) }
  }
  
  ////////////////////////////////////////////////////////////////////////
  // Radius configuration
  ////////////////////////////////////////////////////////////////////////
  def getRadiusServerPointers(newServers: Map[String, RadiusServerConfig]) = {
    // clean radius server list
    val cleanRadiusServers = radiusServers.flatMap { case(oldServerName, oldServerPointer) => {
        // Keep only entries for servers with matching names and same configuration
        newServers.get(oldServerName) match {
          case Some(newConfig) =>
            if(newConfig == oldServerPointer.config) Seq((oldServerName, oldServerPointer)) 
              else Seq()
           
          case None => Seq()
        }
      }
    }
    
    // create missing servers
    newServers.map { case(newServerName, newServerConfig) => {
        cleanRadiusServers.get(newServerName) match {
          case None =>
            (newServerName, RadiusServerPointer(newServerConfig, true, 0, 0))
          case Some(p) => (newServerName, p)
        }
      }
    }
  }
      
  
  def newRadiusAuthServerActor(ipAddress: String, bindPort: Int) = {
    context.actorOf(RadiusServer.props(ipAddress, bindPort), "RadiusAuthServer")
  }
  
  def newRadiusAcctServerActor(ipAddress: String, bindPort: Int) = {
    context.actorOf(RadiusServer.props(ipAddress, bindPort), "RadiusAcctServer")
  }
    
  def newRadiusCoAServerActor(ipAddress: String, bindPort: Int) = {
    context.actorOf(RadiusServer.props(ipAddress, bindPort), "RadiusCoAServer")
  }
  

  ////////////////////////////////////////////////////////////////////////
  // Diameter socket
  ////////////////////////////////////////////////////////////////////////
  def startDiameterServerSocket(ipAddress: String, port: Int) = {
    
    val futBinding = Tcp().bind(ipAddress, port).toMat(Sink.foreach(connection => {
      val remoteAddress = connection.remoteAddress.getAddress().getHostAddress()
      log.info("Connection from {}", remoteAddress)
      
      // Check that there is at last one peer configured with that IP Address
      if(!peerHostMap.exists{case (hostName, peerPointer) => peerPointer.config.IPAddress == remoteAddress}){
        log.info("No peer found for {}", remoteAddress);
        connection.handleWith(Flow.fromSinkAndSource(Sink.cancelled, Source.empty))
      } else {
        // Create handling actor and send it the connection. The Actor will register itself as peer or die
        val peerActor = context.actorOf(DiameterPeer.props(None))
        peerActor ! connection
      }
    }))(Keep.left).run()
    
    import scala.util.{Try, Success, Failure}
    import context.dispatcher
    
    futBinding.onComplete(t => {
      t match {
        case Success(binding) =>
          log.info("Diameter socket bound to " + binding.localAddress.getHostString + ":" + binding.localAddress.getPort)
        case Failure(exception) =>
          log.error("Could not bind socket {}", exception.getMessage())
          context.system.terminate()
      }
    })
  }
  
  ////////////////////////////////////////////////////////////////////////
  // Actor message handling
  // Peer lifecycle and routing
  ////////////////////////////////////////////////////////////////////////

	def receive  = LoggingReceive {
	  /*
	   * Diameter messages
	   */
	  
	  case message : DiameterMessage =>
	    findRoute(message >> "Destination-Realm", message.application) match {
	      // Local handler
	      case Some(DiameterRoute(_, _, _, _, Some(handler))) =>
	        // Handle locally
	        handlerMap.get(handler) match {
	          case Some(handlerActor) =>  handlerActor ! RoutedDiameterMessage(message, context.sender)
	          case None => log.warning("Attempt to route message to a non exising handler {}", handler)
	        }
	      
	      // Send to Peer
	      case Some(DiameterRoute(_, _, Some(peers), policy, _)) => 
	        peerHostMap.get(peers(0)) match { // TODO: Only one peer supported
	          case Some(DiameterPeerPointer(_, Some(actorRef))) => actorRef ! RoutedDiameterMessage(message, context.sender)
	          case _ => log.warning("Attempt to route message to a non exising peer {}", peers(0))
	        }
	      // No route
	      case _ =>
	        log.warning("No route found for {} and {}", message >> "Destination-Realm", message.application)
	    }
	    
    /*
     * Radius messages
     */
	  case RadiusServerRequest(packet, actorRef, origin) =>
	    packet.code match {
	    case RadiusPacket.ACCESS_REQUEST =>
  	    handlerMap.get("AccessRequestHandler") match {
    	    case Some(handlerActor) => handlerActor ! RadiusServerRequest(packet, actorRef, origin)
    	    case None => log.warning("No handler defined for Access-Request")
    	  }

	    case RadiusPacket.ACCOUNTING_REQUEST =>
  	    handlerMap.get("AccountingRequestHandler") match {
    	    case Some(handlerActor) => handlerActor ! RadiusServerRequest(packet, actorRef, origin)
    	    case None => log.warning("No handler defined for Accounting-Request")
    	  }

	    case RadiusPacket.COA_REQUEST =>
  	    handlerMap.get("CoARequestHandler") match {
    	    case Some(handlerActor) => handlerActor ! RadiusServerRequest(packet, actorRef, origin)
    	    case None => log.warning("No handler defined for CoA-Request")
    	  }
	    }
	    
	  case RadiusGroupClientRequest(radiusPacket, serverGroupName, authenticator) =>
	    // Find the radius destination
	    radiusServerGroups.get(serverGroupName) match {
	      case Some(serverGroup) =>
	        // Get the target server
	        val availableServers = serverGroup.servers.filter(radiusServers(_).isAlive)
	        val nServers = availableServers.length
	        if(nServers > 0){
	          val serverIndex = if(serverGroup.policy == "random") scala.util.Random.nextInt(nServers) else 0
	          val radiusServer = radiusServers(availableServers(serverIndex))
	          radiusClientActor ! RadiusClientRequest(radiusPacket, 
	                  RadiusEndpoint(radiusServer.config.IPAddress, radiusServer.getPort(radiusPacket.code), radiusServer.config.secret),
	                  sender, authenticator)
	        }
	        else log.warning("No available server found for group {}", serverGroupName)
	        
	      case None =>
	        log.warning("Radius server group {} not found", serverGroupName)
	    }
	    
	  /*
	   * Peer lifecycle
	   */
	  case diameterPeerConfig : DiameterPeerConfig =>
	    // Actor spawn on received connection has successfully processed a CER/CEA exchange
	    // Check there is not already an actor for the same peer
	    peerHostMap.get(diameterPeerConfig.diameterHost) match {
	      case Some(DiameterPeerPointer(config, actorRefOption)) => 
	        if(actorRefOption.isDefined){
	          log.info("Second connection to {} will be torn down", config)
	          sender ! DiameterPeer.Disconnect
	          sender ! PoisonPill
	        } else {
	          log.info("Established a peer relationship with {}", config)
	          peerHostMap(diameterPeerConfig.diameterHost) = DiameterPeerPointer(config, Some(sender))
	        }
	        
	      case None =>
	        log.error("Established connection to non configured peer. This should never happen")
	        sender ! DiameterPeer.Disconnect
	        sender ! PoisonPill
	    }
	    
	  case PeerDown =>
	    // Find the peer whose Actor is the one sending the down message
	    peerHostMap.find{case (hostName, peerPointer) => Some(sender).equals(peerPointer.actorRefOption)} match {
	      case Some((hostName, peerPointer)) => 
	        log.info("Unregistering peer actor for {}", hostName)
	        peerHostMap(hostName) = DiameterPeerPointer(peerPointer.config, None)
	        
	      case _ => log.warning("Peer down for unavailable Peer Actor")
	    }
	}
  
}