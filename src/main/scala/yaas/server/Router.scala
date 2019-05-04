package yaas.server

/**

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
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import com.typesafe.config.ConfigFactory

import yaas.config.{DiameterConfigManager, DiameterRouteConfig, DiameterPeerConfig}
import yaas.config.{RadiusConfigManager, RadiusThisServerConfig, RadiusPorts, RadiusServerConfig, RadiusServerGroupConfig, RadiusClientConfig}
import yaas.config.{HandlerConfigManager, HandlerConfig}
import yaas.coding.DiameterMessage
import yaas.coding.DiameterConversions._
import yaas.coding.RadiusPacket
import yaas.server.RadiusActorMessages._
import yaas.instrumentation.StatsServer
import yaas.instrumentation.StatOps


/********************************
  Instrumental Diameter classes
 ********************************/

case class DiameterRoute(realm: String, application: String, peers: Option[List[String]], policy: Option[String], handler: Option[String])

// Peer tables are made of these items
object PeerStatus {
  val STATUS_DOWN = 0
  val STATUS_STARTING = 1
  val STATUS_READY = 2
}
case class DiameterPeerPointer(config: DiameterPeerConfig, status: Int, actorRefOption: Option[ActorRef])

/********************************
  Instrumental Radius classes
 ********************************/
case class RadiusEndpointStatus(val endPointType: Int, val port: Int, var quarantineTimestamp: Long, var accErrors: Int) {
  def setQuarantine(quarantineTimeMillis: Long) = { 
    quarantineTimestamp = System.currentTimeMillis + quarantineTimeMillis
  }
  
  def addErrors(errors: Int) = {
    accErrors = accErrors + errors
  }
  
  def reset = {
    accErrors = 0
  }
}

/*
 * Used to store the radius servers configuration and runtime status. The availability is tracked per port
 */
case class RadiusServerPointer(val name: String, val IPAddress: String, val secret: String, val quarantineTimeMillis: Int, val errorLimit: Int,
    val endpointMap: scala.collection.mutable.Map[Int, RadiusEndpointStatus])
    
    
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
  
  case object PeerMapTimer
  
  // Instrumentation messages
  case object IXGetPeerStatus
}

// Manages Routes, Peers and Handlers
class Router() extends Actor with ActorLogging {
  
  import Router._
  
  val config = ConfigFactory.load().getConfig("aaa")
  
  // Create stats server
  val statsServer = context.actorOf(StatsServer.props)
  
  // Create instrumentation server
  val instrumentationActor = context.actorOf(yaas.instrumentation.StatsRESTProvider.props(statsServer))
  
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
  val diameterConfig = DiameterConfigManager.diameterConfig
  diameterServerIPAddress = diameterConfig.bindAddress
  diameterServerPort = diameterConfig.bindPort

  // First update of radius configuration
  val radiusConfig = RadiusConfigManager.radiusConfig
  radiusServerIPAddress = radiusConfig.bindAddress
  radiusServerAuthPort = radiusConfig.authBindPort
  radiusServerAcctPort = radiusConfig.acctBindPort
  radiusServerCoAPort = radiusConfig.coABindPort
  radiusServerGroups = RadiusConfigManager.radiusServerGroups
  radiusServers = getRadiusServers(RadiusConfigManager.radiusServers)
  
  // Diameter Server socket
  implicit val actorSytem = context.system
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = context.dispatcher
    
  if(DiameterConfigManager.isDiameterEnabled){
    startDiameterServerSocket(diameterServerIPAddress, diameterServerPort)
    peerHostMap = updateDiameterPeerMap(DiameterConfigManager.diameterPeerConfig)
    diameterRoutes = updateDiameterRoutes(DiameterConfigManager.diameterRouteConfig)
  }
  
  // Radius server actors
  if(RadiusConfigManager.isRadiusServerEnabled){
    if(radiusServerAuthPort > 0 ) newRadiusAuthServerActor(radiusServerIPAddress, radiusServerAuthPort)
    if(radiusServerAcctPort > 0 ) newRadiusAcctServerActor(radiusServerIPAddress, radiusServerAcctPort)
    if(radiusServerCoAPort > 0 ) newRadiusCoAServerActor(radiusServerIPAddress, radiusServerCoAPort)
  }
  
  // Radius client
  val radiusClientActor = if(RadiusConfigManager.isRadiusClientEnabled) 
    Some(context.actorOf(RadiusClient.props(radiusServerIPAddress, radiusConfig.clientBasePort, radiusConfig.numClientPorts, statsServer), "RadiusClient"))
    else None
  
  // Initialize handlers
  handlerMap = updateHandlerMap(HandlerConfigManager.handlerConfig)
  
  // Start timer for re-evalutation of peer status
  context.system.scheduler.scheduleOnce(diameterConfig.peerCheckTimeSeconds seconds, self, PeerMapTimer)
  
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
	  
	  val newPeerHostMap = conf.map { case (hostName, peerConfig) =>
	    cleanPeersHostMap.get(hostName) match {
	      case None | Some(DiameterPeerPointer(_, 0, _)) =>
	        // Not found or disconnected
	        if(peerConfig.connectionPolicy.equalsIgnoreCase("active")) (hostName, DiameterPeerPointer(peerConfig, PeerStatus.STATUS_STARTING, Some(context.actorOf(DiameterPeer.props(Some(peerConfig), statsServer), hostName + "-peer"))))
	        else (hostName, DiameterPeerPointer(peerConfig, PeerStatus.STATUS_DOWN, None))
	      case Some(dpp) =>
	        (hostName, dpp)
	    }
	  }
	  
	  // Update maps
	  scala.collection.mutable.Map() ++ newPeerHostMap
	}
  
  def updateHandlerMap(conf: Map[String, String]) = {
    // Shutdown unconfigured handlers
    val cleanHandlerMap = handlerMap.flatMap { case (handlerName, handlerActor) =>
      if(conf.get(handlerName) == None) {context.stop(handlerActor); Seq()} else Seq((handlerName, handlerActor))
    }
    
    // Create new handlers if needed
    val newHandlerMap = conf.map { case (name, clazz) => 
      if(cleanHandlerMap.get(name) == None) (name, context.actorOf(Props(Class.forName(clazz).asInstanceOf[Class[Actor]], statsServer), name + "-handler"))
      // Already created
      else (name, cleanHandlerMap(name))
    }
    
    Map() ++ newHandlerMap
  }
	
	def updateDiameterRoutes(conf: Seq[DiameterRouteConfig]) = {
	  // Just copy
	  for {
	    route <- conf
	  } yield DiameterRoute(route.realm, route.applicationId, route.peers, route.policy, route.handler)
	}
	
	// Utility function to get a route
  def findRoute(realm: String, application: String, nonLocal: Boolean /* If true, force that the route is not local (i.e. no handler) */) : Option[DiameterRoute] = {
    diameterRoutes.find{ route => 
      (route.realm == "*" || route.realm == realm) && 
      (route.application == "*" || route.application == application) &&
      (if(nonLocal) route.handler.isEmpty else true)
      }
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
            if(config.ports.auth != 0) endPoints(RadiusPacket.ACCESS_REQUEST) = new RadiusEndpointStatus(RadiusPacket.ACCESS_REQUEST, config.ports.auth, 0, 0)
            if(config.ports.acct != 0) endPoints(RadiusPacket.ACCOUNTING_REQUEST) = new RadiusEndpointStatus(RadiusPacket.ACCOUNTING_REQUEST, config.ports.acct, 0, 0)
            if(config.ports.coA != 0) endPoints(RadiusPacket.COA_REQUEST) = new RadiusEndpointStatus(RadiusPacket.COA_REQUEST, config.ports.coA, 0, 0)
            
            (newServerName, new RadiusServerPointer(config.name, config.IPAddress, config.secret, config.quarantineTimeMillis, config.errorLimit, endPoints))
          case Some(rs) => (newServerName, rs)
        }
      }
    }
  }   
  
  def newRadiusAuthServerActor(ipAddress: String, bindPort: Int) = {
    context.actorOf(RadiusServer.props(ipAddress, bindPort, statsServer), "RadiusAuthServer")
  }
  
  def newRadiusAcctServerActor(ipAddress: String, bindPort: Int) = {
    context.actorOf(RadiusServer.props(ipAddress, bindPort, statsServer), "RadiusAcctServer")
  }
    
  def newRadiusCoAServerActor(ipAddress: String, bindPort: Int) = {
    context.actorOf(RadiusServer.props(ipAddress, bindPort, statsServer), "RadiusCoAServer")
  }
  

  ////////////////////////////////////////////////////////////////////////
  // Diameter socket
  ////////////////////////////////////////////////////////////////////////
  def startDiameterServerSocket(ipAddress: String, port: Int) = {
    
    val futBinding = Tcp().bind(ipAddress, port).toMat(Sink.foreach(connection => {
      val remoteAddress = connection.remoteAddress.getAddress().getHostAddress()
      log.info("Connection from {}", remoteAddress)
      
      // Check that there is at last one peer configured with that IP Address
      if(!DiameterConfigManager.validatePeerIPAddress(remoteAddress)){
        log.warning("No valid peer found for {}", remoteAddress);
        connection.handleWith(Flow.fromSinkAndSource(Sink.cancelled, Source.empty))
      } else {
        // Create handling actor and send it the connection. The Actor will register itself as peer or die
        val peerActor = context.actorOf(DiameterPeer.props(None, statsServer))
        peerActor ! connection
      }
    }))(Keep.left).run()
    
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
	  
	  // Request received
	  case message : DiameterMessage =>
	    // If origin is local, force that the route must not be also local, in order to avoid loops
	    val forceNotLocal = sender.path.name.endsWith("-handler")
	    
	    findRoute(message >> "Destination-Realm", message.application, forceNotLocal) match {
	      case Some(DiameterRoute(_, _, _, _, Some(handler))) =>
	        // Handle locally
	        handlerMap.get(handler) match {
	          case Some(handlerActor) =>  
	            handlerActor ! RoutedDiameterMessage(message, context.sender)
	          case None => 
	            log.warning("Attempt to route message to a non exising handler {}", handler)
	            StatOps.pushDiameterReceivedDropped(statsServer, message) 
	        }
	      
	      // Send to Peer
	      case Some(DiameterRoute(_, _, Some(peers), policy, _)) =>
	        // Get the diameterPointers whose name is in the peers list for this message and are active
	        val candidatePeers = peerHostMap.filter{case (host, peerPtr) => peers.contains(host) && peerPtr.status == PeerStatus.STATUS_READY}
	        if(candidatePeers.size == 0){
	          	log.warning("Peer not available among {}", peers)
	            StatOps.pushDiameterReceivedDropped(statsServer, message) 
	        } else {
	          (if(policy == Some("fixed")) candidatePeers.values.head else scala.util.Random.shuffle(candidatePeers.values).head) match {
	            case DiameterPeerPointer(_, _, Some(actorRef)) => 
	              actorRef ! RoutedDiameterMessage(message, context.sender)
	            case _ => 
	              log.warning("This should never happen {}", peers(0))
	              StatOps.pushDiameterReceivedDropped(statsServer, message) 
	          }
	        }
	        
	      // No route
	      case _ =>
	        log.warning("No route found for {} and {}", message >> "Destination-Realm", message.application)
	        StatOps.pushDiameterReceivedDropped(statsServer, message) 
	    }
	    
    /*
     * Radius messages
     */
	  case RadiusClientStats(stats) =>
	    val currentTimestamp = System.currentTimeMillis
	    
	    // Iterate through all the configured endpoints
	    // If there are stats reported, update the endpoint status
	    for {
	      (serverName, radiusServerPointer) <- radiusServers
	      (_, epStatus) <- radiusServerPointer.endpointMap
	    } {
	      stats.get(RadiusEndpoint(radiusServerPointer.IPAddress, epStatus.port)) match {
	        case Some((successes, errors)) => 
	          // Only if not in quarantine
	          if(radiusServerPointer.quarantineTimeMillis < currentTimestamp) {
  	          // If there are successes, reset the old errors if any
  	          if(successes > 0) epStatus.reset
  	          // Add the errors if there are only errors
  	          else epStatus.addErrors(errors)
  	          
  	          // Put in quarantine if necessary
  	          if(epStatus.accErrors > radiusServerPointer.errorLimit){
  	            log.info("Radius Server Endpoint {}:{} now in quarantine for {} milliseconds", radiusServerPointer.name, epStatus.port, radiusServerPointer.quarantineTimeMillis)
  	            epStatus.quarantineTimestamp = System.currentTimeMillis + radiusServerPointer.quarantineTimeMillis
  	            epStatus.reset
  	          }
	          } 
	          
	        case _ =>
	          // No stats reported in the interval for this endpoint
	      }
	    }
	    
	  case RadiusServerRequest(packet, actorRef, origin, secret) =>
	    packet.code match {
	    case RadiusPacket.ACCESS_REQUEST =>
  	    handlerMap.get("AccessRequestHandler") match {
    	    case Some(handlerActor) => handlerActor ! RadiusServerRequest(packet, actorRef, origin, secret)
    	    case None => log.warning("No handler defined for Access-Request")
    	  }

	    case RadiusPacket.ACCOUNTING_REQUEST =>
  	    handlerMap.get("AccountingRequestHandler") match {
    	    case Some(handlerActor) => handlerActor ! RadiusServerRequest(packet, actorRef, origin, secret)
    	    case None => log.warning("No handler defined for Accounting-Request")
    	  }

	    case RadiusPacket.COA_REQUEST =>
  	    handlerMap.get("CoARequestHandler") match {
    	    case Some(handlerActor) => handlerActor ! RadiusServerRequest(packet, actorRef, origin, secret)
    	    case None => log.warning("No handler defined for CoA-Request")
    	  }
	    }
	    
	  case RadiusGroupClientRequest(radiusPacket, serverGroupName, radiusId, retryNum) =>
	    // Find the radius destination
	    radiusServerGroups.get(serverGroupName) match {
	      
	      case Some(serverGroup) =>
	        // Filter available servers
	        val now = System.currentTimeMillis
	        val availableServers = serverGroup.servers.filter(
	          radiusServers(_).endpointMap.get(radiusPacket.code) match {
	            case Some(ep) if(ep.quarantineTimestamp < now) => true
	            case _ => false
	          }
	        )
	        val allServers = serverGroup.servers
	        
	        // Get candidate servers. If no available servers, ignore status if policy contains "clear"
	        val servers = 
	          if(availableServers.length > 0) {
	            availableServers 
	          }
	          else if (serverGroup.policy.contains("clear")) {
	            log.warning("No available server found for group {}. Quaratine status will be ignored", serverGroupName)
	            allServers
	          }
	          else IndexedSeq()

	        val nServers = servers.length
	        if(nServers > 0) {
            val serverIndex = (retryNum + (if(serverGroup.policy.contains("random")) (radiusPacket.authenticator(0).toInt % nServers) else 0)) % nServers
            val radiusServer = radiusServers(servers(serverIndex))
            // Name is converted to address here
            radiusClientActor.get ! RadiusClientRequest(radiusPacket, 
                    RadiusEndpoint(java.net.InetAddress.getByName(radiusServer.IPAddress).getHostAddress(), radiusServer.endpointMap(radiusPacket.code).port), radiusServer.secret,
                    sender, radiusId)
	        }
          else log.warning("No available server found for group {}. Discarding packet", serverGroupName)

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
	          peerHostMap(diameterPeerConfig.diameterHost) = DiameterPeerPointer(config, PeerStatus.STATUS_READY, Some(sender))
	        } else {
	          // There is some other guy. One of them must die
	          if(status == PeerStatus.STATUS_READY){
	            // The other guy wins
	            log.info("Second connection to already connected peer {} will be torn down", config)
              // Kill this new redundant connection
              context.stop(sender)
	          } else {
	            // Kill the other Actor
	            log.info("Won the race. Established a peer relationship with {}", config)
	            peerHostMap(diameterPeerConfig.diameterHost) = DiameterPeerPointer(config, PeerStatus.STATUS_READY, Some(sender))
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
	    peerHostMap.find{case (hostName, peerPointer) => peerPointer.actorRefOption.map(r => r.compareTo(sender) == 0).getOrElse(false)} match {
	      case Some((hostName, peerPointer)) => 
	        log.info("Unregistering peer actor for {}", hostName)
	        peerHostMap(hostName) = DiameterPeerPointer(peerPointer.config, PeerStatus.STATUS_DOWN, None)
	        
	      case _ => 
	        log.debug("Peer down for Actor {} not found in peer map", sender.path)
	    }
	    
	  case PeerMapTimer if(DiameterConfigManager.isDiameterEnabled) =>
      peerHostMap = updateDiameterPeerMap(DiameterConfigManager.diameterPeerConfig)
      diameterRoutes = updateDiameterRoutes(DiameterConfigManager.diameterRouteConfig)
      handlerMap = updateHandlerMap(HandlerConfigManager.handlerConfig)
      
	    // Start timer for re-evalutation of peer status
      context.system.scheduler.scheduleOnce(diameterConfig.peerCheckTimeSeconds seconds, self, PeerMapTimer)
	    
	  /* 
	   * Instrumentation messages
	   */
    case IXGetPeerStatus =>
      val peerStatus: scala.collection.immutable.Map[String, StatOps.DiameterPeerStat] = for {
        (hostName, dpp) <- peerHostMap.toMap
      } yield (hostName, StatOps.DiameterPeerStat(dpp.config.copy(), dpp.status))
      
      sender ! peerStatus

	}
}