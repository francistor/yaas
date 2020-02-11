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

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill, Props}
import akka.event.LoggingReceive
import akka.stream._
import akka.stream.scaladsl._
import com.typesafe.config.ConfigFactory
import yaas.coding.DiameterConversions._
import yaas.coding.{DiameterMessage, RadiusPacket}
import yaas.config._
import yaas.instrumentation.{MetricsOps, MetricsServer}
import yaas.server.RadiusActorMessages._

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}


object Router {
  def props(): Props = Props(new Router())

	// Actor Messages

	/**
	 * Sent by Router to a handler actor. The owner will be the Originating peer
	 * @param message the Diameter Message sent
	 * @param owner origin Peer
	 */
  case class RoutedDiameterMessage(message: DiameterMessage, owner: ActorRef)

	/**
	 * Notification from Peer that is not available anymore
	 */
  case class PeerDown()

	/**
	 * Sent by the RadiusClient to notify statistics
	 * @param stats statistics object
	 */
  case class RadiusClientStats(stats: Map[RadiusEndpoint, (Int, Int)])

	/**
	 * To answer with Peer Stats
	 */
  case object IXGetPeerStatus

	/**
	 * Notification that the configuration has changed
	 * @param fileName don't know
	 */
  case class IXReloadConfig(fileName: Option[String])

	/**
	 * Auto-sent periodically to check if the Peer table has changed
	 */
  case object PeerCheck
}

// Manages Routes, Peers and Handlers
class Router() extends Actor with ActorLogging {

	import Router._

	case class DiameterRoute(realm: String, application: String, peers: Option[List[String]], policy: Option[String], handler: Option[String])

	// Peer tables are made of these items
	case class DiameterPeerPointer(config: DiameterPeerConfig, status: Int, actorRefOption: Option[ActorRef])

	case class RadiusEndpointStatus(endPointType: Int, port: Int, var quarantineTimestamp: Long, var accErrors: Int) {

		def addErrors(errors: Int): Unit = {
			accErrors = accErrors + errors
		}

		def reset(): Unit = {
			accErrors = 0
		}
	}

	/*
   * Used to store the radius servers configuration and runtime status. The availability is tracked per port
   */
	case class RadiusServerPointer(name: String, IPAddress: String, secret: String, quarantineTimeMillis: Int, errorLimit: Int, endpointMap: scala.collection.mutable.Map[Int, RadiusEndpointStatus])

  private val config = ConfigFactory.load().getConfig("aaa")
  
  private val peerCheckTimeSeconds = config.getInt("diameter.peerCheckTimeSeconds")
  
	// Create stats server
  private val metricsServer = context.actorOf(MetricsServer.props())
  
  // Start sessions database and IPAM REST server
	private val databaseRole = config.getString("sessionsDatabase.role")
	if(databaseRole != "none") yaas.database.SessionDatabase.init()
	if(databaseRole == "server") context.actorOf(yaas.database.SessionRESTProvider.props(metricsServer))

	private var handlerMap: Map[String, ActorRef] = Map()
  
  // Initial Diameter Configuration
	private val diameterConfig = DiameterConfigManager.diameterConfig
	private val diameterServerIPAddress = diameterConfig.bindAddress
	private val diameterServerPort = diameterConfig.bindPort
	private var peerHostMap : scala.collection.mutable.Map[String, DiameterPeerPointer] = scala.collection.mutable.Map()
	private var diameterRoutes : Seq[DiameterRoute] = Seq()
	
	// Initial Radius Configuration
	private val radiusConfig = RadiusConfigManager.radiusConfig
	private val radiusServerIPAddress = radiusConfig.bindAddress
	private val radiusServerAuthPort = radiusConfig.authBindPort
	private val radiusServerAcctPort = radiusConfig.acctBindPort
	private val radiusServerCoAPort = radiusConfig.coABindPort
	private var radiusServers: Map[String, RadiusServerPointer] = Map()
	private var radiusServerGroups: Map[String, RadiusServerGroupConfig] = RadiusConfigManager.radiusServerGroups
	
  // Diameter Server socket
  private implicit val actorSytem: ActorSystem = context.system
  private implicit val materializer: ActorMaterializer = ActorMaterializer()
  private implicit val executionContext: ExecutionContextExecutor = context.dispatcher
    
  if(DiameterConfigManager.isDiameterEnabled){
    startDiameterServerSocket(diameterServerIPAddress, diameterServerPort)
    peerHostMap = updatePeerHostMap(DiameterConfigManager.diameterPeerConfig, peerHostMap.toMap)
    diameterRoutes = updateDiameterRoutes(DiameterConfigManager.diameterRouteConfig)
  }
  
  // Radius server actors
  if(RadiusConfigManager.isRadiusServerEnabled){
    if(radiusServerAuthPort > 0 ) context.actorOf(RadiusServer.props(radiusServerIPAddress, radiusServerAuthPort, metricsServer), "RadiusAuthServer")
    if(radiusServerAcctPort > 0 ) context.actorOf(RadiusServer.props(radiusServerIPAddress, radiusServerAcctPort, metricsServer), "RadiusAcctServer")
    if(radiusServerCoAPort > 0 ) context.actorOf(RadiusServer.props(radiusServerIPAddress, radiusServerCoAPort, metricsServer), "RadiusCoAServer")
  }
  
  // Radius client
  private val radiusClientActor = if(RadiusConfigManager.isRadiusClientEnabled) {
		radiusServers = getRadiusServers(RadiusConfigManager.radiusServers, radiusServers)
    Some(context.actorOf(RadiusClient.props(radiusServerIPAddress, radiusConfig.clientBasePort, radiusConfig.numClientPorts, metricsServer), "RadiusClient"))
	} else None
  
  // Initialize handlers
  handlerMap = updateHandlerMap(HandlerConfigManager.handlerConfig, handlerMap)
  
  // Create instrumentation server
  context.actorOf(yaas.instrumentation.InstrumentationRESTProvider.props(metricsServer))
  
  ////////////////////////////////////////////////////////////////////////
  // Diameter configuration
  ////////////////////////////////////////////////////////////////////////
	
	/**
	 * Will create the actors for the configured peers and shutdown the ones not configured anymore.
	 */
	private def updatePeerHostMap(confPeerHostMap: Map[String, DiameterPeerConfig], currPeerHostMap: Map[String, DiameterPeerPointer]) = {
	  
    log.info("Updating Diameter Peers")
    
	  // Shutdown un-configured peers and return clean list
		val cleanPeersHostMap = currPeerHostMap.filter{
			case (hostName, peerPointer) =>
				if(confPeerHostMap.get(hostName).isEmpty) {
					// Stop peer and return empty sequence
					log.info(s"Removing peer $hostName")
					peerPointer.actorRefOption.foreach(context.stop)
					false
				}
				else true
		}

    // For each one of the peers in the new configuration map
		scala.collection.mutable.Map() ++ confPeerHostMap.map { case (hostName, peerConfig) =>
	    cleanPeersHostMap.get(hostName) match {
				// Not in old map or disconnected --> No Actor associated
	      case None | Some(DiameterPeerPointer(_, PeerStatus.STATUS_DOWN, _)) =>
	        if(peerConfig.connectionPolicy.equalsIgnoreCase("active")){
	          // Create peer actor that will try to connect
	          log.info(s"Retrying connection to $hostName")
	          (hostName, DiameterPeerPointer(peerConfig, PeerStatus.STATUS_STARTING, Some(context.actorOf(DiameterPeer.props(Some(peerConfig), metricsServer), hostName + "-peer"))))
	        }
	        else {
	          // Passive Policy. Just create pointer without Actor. Will be created when a connection arrives
	          log.debug(s"Waiting for connection from $hostName")
	          (hostName, DiameterPeerPointer(peerConfig, PeerStatus.STATUS_DOWN, None))
	        }
				// Was in the previous map and was not down. Leave as it is
	      case Some(dpp) =>
	        (hostName, dpp)
	    }
	  }
	}

	/**
	 * Create/Destroy handlers to adjust to current configuration
	 */
  private def updateHandlerMap(conf: Map[String, HandlerConfigEntry], currHandlerMap: Map[String, ActorRef]) = {

    log.info("Updating Handlers")
		val cleanHandlerMap = currHandlerMap.filter{ case (handlerName, handlerActor) =>
				if(conf.get(handlerName).isEmpty) {
					log.info(s"Stopping handler $handlerName")
					context.stop(handlerActor)
					false
				} else true
		}
    
    // Create new handlers if needed
		Map() ++ conf.map { case (name, hce) =>
      if(cleanHandlerMap.get(name).isEmpty){
        log.info(s"Creating handler $name")
        (name, context.actorOf(Props(Class.forName(hce.clazz).asInstanceOf[Class[Actor]], metricsServer, hce.config), name + "-handler"))
      }
      // Already created
      else (name, cleanHandlerMap(name))
    }
  }
	
	private def updateDiameterRoutes(conf: Seq[DiameterRouteConfig]) = {
	  log.info("Updating Diameter Routes")
	  
	  // Just copy
	  for {
	    route <- conf
	  } yield DiameterRoute(route.realm, route.applicationId, route.peers, route.policy, route.handler)
	}

	/**
	 * 	Utility function to get a route
	 */
  private def findRoute(realm: String, application: String, nonLocal: Boolean /* If true, force that the route is not local (i.e. no handler) */) : Option[DiameterRoute] = {
    diameterRoutes.find{ route => 
      (route.realm == "*" || route.realm == realm) && 
      (route.application == "*" || route.application == application) &&
      (if(nonLocal) route.handler.isEmpty else true)
      }
  }
  
  ////////////////////////////////////////////////////////////////////////
  // Radius configuration
  ////////////////////////////////////////////////////////////////////////
  // TODO: RadiusEndpointStatus should be dynamically created and deleted
	/**
	 * Updates the Radius Servers List
	 */
  private def getRadiusServers(newServersConfig: Map[String, RadiusServerConfig], currServersConfig: Map[String, RadiusServerPointer]) = {

    log.info("Updating Radius Servers")

		val cleanRadiusServers = currServersConfig.filter{ case(oldServerName, oldServer) => {
				// Keep only entries for servers with matching names and same configuration
				newServersConfig.get(oldServerName) match {
					case Some(newServer) =>
						if(newServer.IPAddress == oldServer.IPAddress &&
							newServer.errorLimit == oldServer.errorLimit &&
							newServer.quarantineTimeMillis == oldServer.quarantineTimeMillis &&
							newServer.secret == oldServer.secret) true
						else false

					case None => false
				}
			}
		}

    // create missing servers
    newServersConfig.map { case(newServerName, config) => {
        cleanRadiusServers.get(newServerName) match {
					// Create new
          case None =>
            val endPoints = scala.collection.mutable.Map[Int, RadiusEndpointStatus]()
            if(config.ports.auth != 0) endPoints(RadiusPacket.ACCESS_REQUEST) = RadiusEndpointStatus(RadiusPacket.ACCESS_REQUEST, config.ports.auth, 0, 0)
            if(config.ports.acct != 0) endPoints(RadiusPacket.ACCOUNTING_REQUEST) = RadiusEndpointStatus(RadiusPacket.ACCOUNTING_REQUEST, config.ports.acct, 0, 0)
            if(config.ports.coA != 0) endPoints(RadiusPacket.COA_REQUEST) = RadiusEndpointStatus(RadiusPacket.COA_REQUEST, config.ports.coA, 0, 0)
            
            (newServerName, RadiusServerPointer(config.name, config.IPAddress, config.secret, config.quarantineTimeMillis, config.errorLimit, endPoints))

					// Keep existing
          case Some(rs) =>
						(newServerName, rs)
        }
      }
    }
  }

  ////////////////////////////////////////////////////////////////////////
  // Diameter socket
  ////////////////////////////////////////////////////////////////////////
  private def startDiameterServerSocket(ipAddress: String, port: Int): Unit = {
    
    val futBinding = Tcp().bind(ipAddress, port).toMat(Sink.foreach(connection => {

      val remoteAddress = connection.remoteAddress.getAddress.getHostAddress
      log.info("Connection from {}", remoteAddress)
      
      // Check that there is at last one peer configured with that IP Address
      if(!DiameterConfigManager.validatePeerIPAddress(remoteAddress)){
        log.warning("No valid peer found for {}", remoteAddress);
        connection.handleWith(Flow.fromSinkAndSource(Sink.cancelled, Source.empty))
      } else {
        // Create handling actor and send it the connection. The Actor will register itself as peer or die
        context.actorOf(DiameterPeer.props(None, metricsServer)) ! connection
      }
    }))(Keep.left).run()
    
    futBinding.onComplete {
			case Success(binding) =>
				log.info("Diameter socket bound to " + binding.localAddress.getHostString + ":" + binding.localAddress.getPort)
			case Failure(exception) =>
				log.error("Could not bind diameter socket {}", exception.getMessage)
				context.system.terminate()
		}
  }
  
  ////////////////////////////////////////////////////////////////////////
  // Actor message handling
  // Peer lifecycle and routing
  ////////////////////////////////////////////////////////////////////////

	def receive: Receive = LoggingReceive {

	  // Request received
	  case message : DiameterMessage =>
	    // If origin is local, force that the route must not be also local, in order to avoid loops
	    val forceNotLocal = sender.path.name.endsWith("-handler")
	    
	    findRoute(message.destinationRealm, message.application, forceNotLocal) match {
	      case Some(DiameterRoute(_, _, _, _, Some(handler))) =>
	        // Handle locally
	        handlerMap.get(handler) match {
	          case Some(handlerActor) =>  
	            handlerActor ! RoutedDiameterMessage(message, context.sender)
	          case None => 
	            log.warning("Attempt to route message to a non exising handler {}", handler)
	            MetricsOps.pushDiameterReceivedDropped(metricsServer, message) 
	        }
	      
	      // Send to Peer
	      case Some(DiameterRoute(_, _, Some(peers), policy, _)) =>
	        // Get the diameterPointers whose name is in the peers list for this message and are active
	        val candidatePeers = peerHostMap.filter{case (host, peerPtr) => peers.contains(host) && peerPtr.status == PeerStatus.STATUS_READY}
	        if(candidatePeers.isEmpty){
	          	log.warning("Peer not available among {}", peers)
	            MetricsOps.pushDiameterReceivedDropped(metricsServer, message) 
	        } else {
	          (if(policy.contains("fixed")) candidatePeers.values.head else scala.util.Random.shuffle(candidatePeers.values).head) match {
	            case DiameterPeerPointer(_, _, Some(actorRef)) => 
	              actorRef ! RoutedDiameterMessage(message, context.sender)
	            case _ => 
	              log.warning("This should never happen {}", peers(0))
	              MetricsOps.pushDiameterReceivedDropped(metricsServer, message) 
	          }
	        }
	        
	      // No route
	      case _ =>
	        log.warning("No route found for {} and {}", message >> "Destination-Realm", message.application)
	        MetricsOps.pushDiameterReceivedDropped(metricsServer, message) 
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
	      Try(java.net.InetAddress.getByName(radiusServerPointer.IPAddress).getHostAddress()) match {
	        case Success(ipAddr) =>
	          stats.get(RadiusEndpoint(ipAddr, epStatus.port)) match {
    	        case Some((successes, errors)) => 
    	          // Only if not in quarantine
    	          if(epStatus.quarantineTimestamp < currentTimestamp) {
      	          // If there are successes, reset the old errors if any
      	          if(successes > 0) epStatus.reset
      	          // Add the errors if there are only errors
      	          else epStatus.addErrors(errors)
      	          
      	          // Put in quarantine if necessary
      	          if(epStatus.accErrors > radiusServerPointer.errorLimit){
      	            log.info("Too many timeouts. Radius Server Endpoint {}/{}:{} now in quarantine for {} milliseconds", radiusServerPointer.name, ipAddr, epStatus.port, radiusServerPointer.quarantineTimeMillis)
      	            epStatus.quarantineTimestamp = currentTimestamp + radiusServerPointer.quarantineTimeMillis
      	            epStatus.reset
      	          }
    	          } 
    	          
    	        case _ =>
    	          // No stats reported in the interval for this endpoint
	        }
	        case Failure(e) =>
	          // Unresolvable IP address
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
	          // (radiusId - retryNum) >> 3 is a stable number across retransmissions (up to 8). See yaas.util.IDGenerator
	          val requestId = ((radiusId - retryNum) >> 3)
	          
            val serverIndex = (retryNum + (if(serverGroup.policy.contains("random")) (requestId % nServers).toInt else 0)) % nServers
            val radiusServerPointer = radiusServers(servers(serverIndex))
            
            // Name is converted to address here. If cannot be done, put immediately in quarantine
            val endPoint = radiusServerPointer.endpointMap(radiusPacket.code)
            Try(java.net.InetAddress.getByName(radiusServerPointer.IPAddress).getHostAddress()) match {
	            case Success(endpointIPAddress) =>
	              radiusClientActor.get ! RadiusClientRequest(radiusPacket, RadiusEndpoint(endpointIPAddress, endPoint.port), radiusServerPointer.secret, sender, radiusId)
                    
	            case Failure(_) =>
	              log.info("Bad resolution. Radius Server Endpoint {}:{} now in quarantine for {} milliseconds", radiusServerPointer.name, endPoint.port, radiusServerPointer.quarantineTimeMillis)
	              endPoint.quarantineTimestamp = System.currentTimeMillis() + radiusServerPointer.quarantineTimeMillis
	              endPoint.reset
	          }
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
	    
	  /* 
	   * Instrumentation messages
	   */
    case IXGetPeerStatus =>
      val peerStatus: scala.collection.immutable.Map[String, MetricsOps.DiameterPeerStatus] = for {
        (hostName, dpp) <- peerHostMap.toMap
      } yield (hostName, MetricsOps.DiameterPeerStatus(dpp.config.copy(), dpp.status))
      
      sender ! peerStatus

    case IXReloadConfig(fileName) =>
      import yaas.config.ConfigManager
      
      try {
        fileName match {
          case Some(f) => 
            ConfigManager.reloadConfigObjectAsJson(f)
            
          case None =>
            ConfigManager.reloadAllConfigObjects
        }
        
        // Reconfigure Diameter
        if(DiameterConfigManager.isDiameterEnabled){
          if(fileName.contains("diameterPeers.json") || fileName.isEmpty){
            // Update the underlying config object and then the router map
            DiameterConfigManager.updateDiameterPeerConfig(false)
            peerHostMap = updatePeerHostMap(DiameterConfigManager.diameterPeerConfig, peerHostMap.toMap)
          }
          if(fileName.contains("diameterRoutes.json") || fileName.isEmpty){
            DiameterConfigManager.updateDiameterRouteConfig(false)
            diameterRoutes = updateDiameterRoutes(DiameterConfigManager.diameterRouteConfig)
          }
        }
        
        // Reconfigure Radius
        if(fileName.contains("radiusServers.json") || fileName.isEmpty){
          RadiusConfigManager.updateRadiusServers(false)
          radiusServerGroups = RadiusConfigManager.radiusServerGroups
          radiusServers = getRadiusServers(RadiusConfigManager.radiusServers, radiusServers)
        }
        
        if(fileName.contains("radiusClients.json") || fileName.isEmpty){
          RadiusConfigManager.updateRadiusClients(false)
        }
        
        // Reconfigure Handlers
        if(fileName.contains("handlers.json")){
          HandlerConfigManager.updateHandlerConfig(false)
          handlerMap = updateHandlerMap(HandlerConfigManager.handlerConfig, handlerMap)
        }
        
        sender ! "OK"
      } 
      catch {
        case e: NoSuchElementException => sender ! s"${fileName.getOrElse("all")} not found"
        case e: java.io.IOException => sender ! akka.actor.Status.Failure(e)
      }
      
    case PeerCheck =>
      // Check peer status. Will use last peer table if not updated by a reload
      if(DiameterConfigManager.isDiameterEnabled){
        log.debug("Peer status checking")
        peerHostMap = updatePeerHostMap(DiameterConfigManager.diameterPeerConfig, peerHostMap.toMap)
        context.system.scheduler.scheduleOnce(peerCheckTimeSeconds seconds, self, PeerCheck)
      }
	}
	
	override def preStart = {
	  // Start peer check timer
    context.system.scheduler.scheduleOnce(peerCheckTimeSeconds seconds, self, PeerCheck)
  }
  
  // Cleanup
  override def postStop = {
    val databaseRole = config.getString("sessionsDatabase.role")
    if(databaseRole != "none") yaas.database.SessionDatabase.close
  }
}