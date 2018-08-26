package yaas.server

/*

The basic set of Actors are Peer / Router / Handler

Peer actors
-----------

Incoming messages (got from TCPStream handler)
	request -> send to router as diameterMessage
	anwer -> look up in cache the h2hId and send as diameterMessage to the actor that sent the request
	
Outgoing messages (got as actor messages)
	request (RoutedDiameterMessage) -> store in cache, along with the actor that sent the request, and send to peer
	answer (DiameterMessage) -> send to peer
	
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
	answer (DiameterMessage) -> look up in cache for the callback to invoke
	
Outgoing messages (create actor messages)
	request (RoutedDiameterMessage) -> store in cache and send to Router
	answer (DiameterMessage) -> send to Peer that sent the request

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

case class DiameterRoute(realm: String, application: String, peers: Option[List[String]], policy: Option[String], handler: Option[String])

/*
 * Instrumental Diameter classes
 */

// Peer tables are made of these items
object PeerStatus {
  sealed trait Status
  case object Down extends Status
  case object Starting extends Status
  case object Ready extends Status
}
case class DiameterPeerPointer(config: DiameterPeerConfig, status: PeerStatus.Status, actorRefOption: Option[ActorRef])

/*
 * Instrumental Radius classes
 */
case class RadiusEndpointStatus(val endPointType: Int, val port: Int, var quarantineTimestamp: Long, var accErrors: Int) {
  def setQuarantine(quarantineTimeMillis: Long) = { 
    quarantineTimestamp = System.currentTimeMillis + quarantineTimeMillis
  }
  
  def addErrors(errors: Int) = {
    accErrors = accErrors + errors
  }
  
  def reset = {
    accErrors = 0
    quarantineTimestamp = 0
  }
}

/*
 * Used to store the radius servers configuration and runtime status. The availability is tracked per port
 */
case class RadiusServerPointer(val name: String, val IPAddress: String, val secret: String, val quarantineTimeMillis: Int, val errorLimit: Int,
    val endPoints: scala.collection.mutable.Map[Int, RadiusEndpointStatus])
    
    
///////////////////////////////////////////////////////////////////////////////
// Router
///////////////////////////////////////////////////////////////////////////////

// Best practise
object Router {
  def props() = Props(new Router())
  
  // Actor messages
  case class RoutedDiameterMessage(message: DiameterMessage, owner: ActorRef)
  case class PeerDown()
  case class RadiusClientStats(stats: Map[RadiusEndpoint, (Int, Int)])
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
  
  // TODO: Start periodic re-connection of peers
  
  // First update of radius configuration
  val radiusConfig = RadiusConfigManager.getRadiusConfig
  radiusServerIPAddress = radiusConfig.bindAddress
  radiusServerAuthPort = radiusConfig.authBindPort
  radiusServerAcctPort = radiusConfig.acctBindPort
  radiusServerCoAPort = radiusConfig.coABindPort
  radiusServerGroups = RadiusConfigManager.getRadiusServerGroups
  radiusClients = RadiusConfigManager.getRadiusClients
  radiusServers = getRadiusServers(RadiusConfigManager.getRadiusServers)
  
  updateHandlerMap(HandlerConfigManager.getHandlerConfig)
  
  // Diameter Server socket
  implicit val actorSytem = context.system
  implicit val materializer = ActorMaterializer()
  if(diameterServerIPAddress != "0") startDiameterServerSocket(diameterServerIPAddress, diameterServerPort)
  
  // Radius server actors
  if(radiusServerIPAddress != "0"){
    newRadiusAuthServerActor(radiusServerIPAddress, radiusServerAuthPort)
    newRadiusAcctServerActor(radiusServerIPAddress, radiusServerAcctPort)
    newRadiusCoAServerActor(radiusServerIPAddress, radiusServerCoAPort)
  }
  
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
	       (hostName, DiameterPeerPointer(peerConfig, PeerStatus.Starting, Some(context.actorOf(DiameterPeer.props(Some(peerConfig)), hostName))))
	      }
	      else {
	        (hostName, DiameterPeerPointer(peerConfig, PeerStatus.Down, None))
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
  
  def getRadiusServers(newServersConfig: Map[String, RadiusServerConfig]) = {
    // clean radius server list
    val cleanRadiusServers = radiusServers.flatMap { case(oldServerName, oldServer) => {
        // Keep only entries for servers with matching names and same configuration
        newServersConfig.get(oldServerName) match {
          case Some(newServer) =>
            if(newServer.IPAddress == oldServer.IPAddress &&
               newServer.errorLimit == oldServer.errorLimit &&
               newServer.quarantineTimeMillis == oldServer.quarantineTimeMillis &&
               newServer.secret == oldServer.secret) Seq((oldServerName, oldServer)) 
              else Seq()
           
          case None => Seq()
        }
      }
    }
    
    // create missing servers
    newServersConfig.map { case(newServerName, config) => {
        cleanRadiusServers.get(newServerName) match {
          case None =>
            val endPoints = scala.collection.mutable.Map[Int, RadiusEndpointStatus]()
            if(config.ports.auth != 0) endPoints(RadiusPacket.ACCESS_REQUEST) = new RadiusEndpointStatus(RadiusPacket.ACCESS_REQUEST, config.ports.auth, config.quarantineTimeMillis, config.errorLimit)
            if(config.ports.acct != 0) endPoints(RadiusPacket.ACCOUNTING_REQUEST) = new RadiusEndpointStatus(RadiusPacket.ACCOUNTING_REQUEST, config.ports.acct, config.quarantineTimeMillis, config.errorLimit)
            if(config.ports.coA != 0) endPoints(RadiusPacket.COA_REQUEST) = new RadiusEndpointStatus(RadiusPacket.COA_REQUEST, config.ports.coA, config.quarantineTimeMillis, config.errorLimit)
            if(config.ports.dm != 0) endPoints(RadiusPacket.DISCONNECT_REQUEST) = new RadiusEndpointStatus(RadiusPacket.DISCONNECT_REQUEST, config.ports.dm, config.quarantineTimeMillis, config.errorLimit)

            (newServerName, new RadiusServerPointer(config.name, config.IPAddress, config.secret, config.quarantineTimeMillis, config.errorLimit, endPoints))
          case Some(rs) => (newServerName, rs)
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
	        peerHostMap.get(peers.filter(peer => peerHostMap(peer).status == PeerStatus.Ready)(0)) match { 
	          // TODO: Implement load balancing
	          case Some(DiameterPeerPointer(_, _, Some(actorRef))) => actorRef ! RoutedDiameterMessage(message, context.sender)
	          case _ => log.warning("Attempt to route message to a non exising peer {}", peers(0))
	        }
	      // No route
	      case _ =>
	        log.warning("No route found for {} and {}", message >> "Destination-Realm", message.application)
	        // TODO: Answer with error message if peer not Ready
	    }
	    
    /*
     * Radius messages
     */
	    
	  case RadiusClientStats(stats) =>
	    
	    // Iterate through all the configured endpoints
	    // If there are stats reported, update the endpoint status
	    for {
	      (serverName, radiusServerPointer) <- radiusServers
	      (ept, endpoint) <- radiusServerPointer.endPoints 
	    } {
	      stats.get(RadiusEndpoint(radiusServerPointer.IPAddress, endpoint.port, radiusServerPointer.secret)) match {
	        case Some((successes, errors)) => 
	          // If there are successes, reset the old errors if any
	          if(successes > 0) endpoint.reset
	          // Add the errors if there are only errors
	          else endpoint.addErrors(errors)
	          
	          // Put in quarantine if necessary
	          if(endpoint.accErrors > radiusServerPointer.errorLimit){
	            log.info("Radius Server Endpoint {}:{} now in quarantine for {} milliseconds", radiusServerPointer.name, endpoint.port, radiusServerPointer.quarantineTimeMillis)
	            endpoint.quarantineTimestamp = System.currentTimeMillis
	          }
	          
	        case _ =>
	      }
	    }
	    
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
	    
	  case RadiusGroupClientRequest(radiusPacket, serverGroupName, radiusId) =>
	    // Find the radius destination
	    radiusServerGroups.get(serverGroupName) match {
	      
	      case Some(serverGroup) =>
	        // Filter available servers
	        val now = System.currentTimeMillis
	        val availableServers = serverGroup.servers.filter(
	          radiusServers(_).endPoints.get(radiusPacket.code) match {
	            case Some(ep) if(ep.quarantineTimestamp < now) => true
	            case _ => false
	          }
	        )
	        val nServers = availableServers.length
	        if(nServers > 0){
	          val serverIndex = if(serverGroup.policy == "random") scala.util.Random.nextInt(nServers) else 0
	          val radiusServer = radiusServers(availableServers(serverIndex))
	          radiusClientActor ! RadiusClientRequest(radiusPacket, 
	                  RadiusEndpoint(radiusServer.IPAddress, radiusServer.endPoints(radiusPacket.code).port, radiusServer.secret),
	                  sender, radiusId)
	        }
	        else log.warning("No available server found for group {}", serverGroupName)
	        
	      case None =>
	        log.warning("Radius server group {} not found", serverGroupName)
	    }
	    
	  /*
	   * Peer lifecycle
	   */
	  case diameterPeerConfig : DiameterPeerConfig =>
	    // Actor has successfully processed a CER/CEA exchange. Register in map, but
	    // check there is not already an actor for the same peer.
	    // If another actor exists and is "Ready", leave as it is and kill this new Actor. Otherwise, destroy the other actor.
	    peerHostMap.get(diameterPeerConfig.diameterHost) match {
	      case Some(DiameterPeerPointer(config, status, actorRefOption)) =>
	        
	        if(actorRefOption == None || actorRefOption == Some(sender)){
	          // If no actor or same Actor, this is us
	          log.info("Established a peer relationship with {}", config)
	          peerHostMap(diameterPeerConfig.diameterHost) = DiameterPeerPointer(config, PeerStatus.Ready, Some(sender))
	        } else {
	          // There is some other guy. One of them must die
	          if(status == PeerStatus.Ready){
	            // The other guy wins
	            log.info("Second connection to already connected peer {} will be torn down", config)
              // Kill this new redundant connection
              context.stop(sender)
	          } else {
	            // Kill the other Actor
	            log.info("Won the race. Established a peer relationship with {}", config)
	            peerHostMap(diameterPeerConfig.diameterHost) = DiameterPeerPointer(config, PeerStatus.Ready, Some(sender))
	            log.info("Stopping redundant Actor")
	            context.stop(actorRefOption.get)
	          }
	        }
	        
	      case None =>
	        log.error("Established connection to non configured Peer. This should never happen")
	        sender ! PoisonPill
	    }
	    
	  case PeerDown =>
	    // Find the peer whose Actor is the one sending the down message
	    peerHostMap.find{case (hostName, peerPointer) => Some(sender).equals(peerPointer.actorRefOption)} match {
	      case Some((hostName, peerPointer)) => 
	        log.info("Unregistering peer actor for {}", hostName)
	        peerHostMap(hostName) = DiameterPeerPointer(peerPointer.config, PeerStatus.Down, None)
	        
	      case _ => log.debug("Peer down for unavailable Peer Actor")
	    }
	}
  
}