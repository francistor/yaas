package yaas.server

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, PoisonPill, Props}
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import yaas.coding.DiameterConversions._
import yaas.coding.{DiameterMessage, DiameterMessageKey}
import yaas.config.{DiameterConfigManager, DiameterPeerConfig}
import yaas.dictionary.DiameterDictionary
import yaas.instrumentation.MetricsOps
import yaas.server.Router.RoutedDiameterMessage

import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * Constructor and helpers for DiameterPeer.
 */
object DiameterPeer {
  def props(peerConfig: Option[DiameterPeerConfig], metricsServer: ActorRef): Props = Props(classOf[DiameterPeer], peerConfig, metricsServer)

  /**
   * Delete old entries in the request map. To be sent periodically
   */
  private case object Clean

  /**
   * Scheduled to implement the CER Timeout
   */
  private case object CERTimeout

  /**
   * Scheduled to implement the DWR Timeout
   */
  private case object DWRTimeout

  /**
   * Scheduled to send the DWR
   */
  private case object SendDWR

  /**
   * Send a CER message
   */
  private case object SendCER

  /**
   * Process the base DiameterMessage received
   * @param message the message
   */
  private case class BaseDiameterMessageReceived(message: DiameterMessage)

  /**
   * Send this DiameterMessage
   * @param message the message
   */
  private case class BaseDiameterMessageSend(message: DiameterMessage)

  /**
   * Process the DiameterMessage received
   * @param message the message
   */
  private case class DiameterMessageReceived(message: DiameterMessage)

  // Get configuration parameters. These ones would need a restart in order to be updated
  private val globalConfig = ConfigFactory.load().getConfig("aaa.diameter")
  private val peerMessageQueueSize = globalConfig.getInt("peerMessageQueueSize")
  private val cleanIntervalMillis = globalConfig.getInt("cleanIntervalMillis")
  private val responseTimeoutMillis = globalConfig.getInt("responseTimeoutMillis")
}


/**
 * Actor executing holding the socket to a specific Diameter Peer.
 *
 * Executes the Diameter Base state machine (CER/CEA, DWR/DWA, DPR/DPA) and manages a map of the outstanding
 * replies, to be routed to the sending Actor.
 *
 * Peer created with "active" policy
 * 	- The actor is instantiated by the router, and marked as "Starting". The <code>config</code> has a value
 *  - The actor tries to establish the connection.
 *  	> Sends CER and sets timer
 *  	> When CEA is received, sends message to Router to be marked as "Running" and starts the DWR timer
 *
 * Peer created with "passive" policy
 *  - The actor is instantiated when a connection is received, passing the connection. The Actor is not yet
 *  registered in the list of peers. <code>config</code> is <code>None</code>
 *  - Waits for the reception of CER
 *  - Upon answering (CEA), sends message to Router to register in the list of peers as "Running" and starts the DWR timer
 *
 *  In case of any error, the Actor sends itself a PoisonPill and terminates.
 */
class DiameterPeer(val peerConfig: Option[DiameterPeerConfig], val metricsServer: ActorRef) extends Actor with ActorLogging {
  
  import DiameterPeer._
  import context.dispatcher

  private implicit val actorSystem: ActorSystem = context.system
  private implicit val mat: ActorMaterializer = ActorMaterializer()

  // Will be reset to configured value in passive policy when connected to remote peer
  private var watchdogIntervalMillis = peerConfig.map(_.watchdogIntervalMillis).getOrElse(30000)

  // The peer remote address, to be assigned when available
  private var remoteAddress: Option[String] = None
  // The peer host name as configured (active) or reported (passive)
  private var peerHostName = peerConfig.map(_.diameterHost).getOrElse("<HostName-Not-Established>")
  // To be used to close the connection with the peer in postStop
  private var psKillSwitch: Option[KillSwitch] = None
  // Periodic cleaning of requests
  private var cleanTimer: Option[Cancellable] = None
  // Implementing timeout for answer to CER
  private var cerTimeoutTimer: Option[Cancellable] = None
  // Implementing timeout for answer to DWR
  private var dwrTimeoutTimer: Option[Cancellable] = None
  // Implementing timeout to send new DWR
  private var dwrTimer: Option[Cancellable] = None

  // Shared KillSwitch for both sides of the Source-and-Sink flow. Otherwise the connection is half closed
  private val killSwitch = KillSwitches.shared("closer").flow[ByteString]
  
  // The TCP handler is made of a Source and a Sink
  // The Source is a queue of packets to be sent
  private val handlerSource = Source.queue[ByteString](peerMessageQueueSize, akka.stream.OverflowStrategy.dropTail).via(killSwitch)

  // The Sink treats the received packets
  private val handlerSink = Flow[ByteString]
    // Framing.lengthField does not seem to work as expected if the computeFrameSize is not used
    // may be that is because the frame length excludes the offset
    .via(Framing.lengthField(3, 1, 64000, java.nio.ByteOrder.BIG_ENDIAN, (_ /* offsetBytes */, calcFrameSize) => calcFrameSize))
    .viaMat(killSwitch)(Keep.right)
    .map(frame => {
      // Decode message
      try{
        val decodedMessage = yaas.coding.DiameterMessage(frame)
        // If Base, handle in this PeerActor
        // Stats are recorded in the receive function
        if(decodedMessage.applicationId == 0) self ! BaseDiameterMessageReceived(decodedMessage) else self ! DiameterMessageReceived(decodedMessage)
      } catch {
          case e: Throwable =>
            log.error(e, "Frame decoding error")
            self ! PoisonPill
      }
    })
    .to(Sink.onComplete(t => {
      t match {
        case Success(_) =>
          log.info("Closed connection with remote peer")
        case Failure(e) =>
          log.info("Closed connection with remote peer. Error" + e.getMessage)
      }
      // The flow for termination is
      // - The code closes the connection
      // - On connection closed (here), we commit suicide
      self ! PoisonPill
      psKillSwitch = None
    })
    )

  // Handler to be associated to the TCP connection
  private val handler = Flow.fromSinkAndSourceMat(handlerSink, handlerSource)(Keep.both)
  
  /**
   * Initial Receive method
   */
  def receive: Receive = {
    
    // Received server connection
    case connection: Tcp.IncomingConnection =>
        val hostString = connection.remoteAddress.getHostString
        log.info("Incoming connection from {}", hostString)
        val (ks, inputQueue) = connection.handleWith(handler)
        psKillSwitch = Some(ks)
        remoteAddress = Some(hostString)
        context.become(receiveConnected(ks, inputQueue))
  }
  
  /**
   * Receive method for connected Actor
   */
  private def receiveConnected(ks: KillSwitch, q: SourceQueueWithComplete[ByteString]) : Receive = {

    /*
    Received Base Diameter Message
     */
    case BaseDiameterMessageReceived(decodedMessage) =>
      if(decodedMessage.isRequest) {
        // Request
        MetricsOps.pushDiameterRequestReceived(metricsServer, peerHostName, decodedMessage)
        if(log.isDebugEnabled) log.debug(s">> Received diameter request $decodedMessage")
      } else{
        // Response
        requestMapOut(decodedMessage.hopByHopId) match {
          case Some(RequestEntry(_, requestTimestamp, _, _)) =>
            MetricsOps.pushDiameterAnswerReceived(metricsServer, peerHostName, decodedMessage, requestTimestamp)
            if(log.isDebugEnabled) log.debug(s">> Received diameter answer $decodedMessage")
          case None =>
            // Unsolicited or stalled response.
            MetricsOps.pushDiameterDiscardedAnswer(metricsServer, peerHostName, decodedMessage)
        }
      }
      handleDiameterBase(decodedMessage)
      
    /*
    Received Non-base Diameter Message
     */
    case DiameterMessageReceived(decodedMessage) =>
      if(decodedMessage.isRequest){
        // If request, send message to Router
        context.parent ! decodedMessage
        MetricsOps.pushDiameterRequestReceived(metricsServer, peerHostName, decodedMessage)
        if(log.isDebugEnabled) log.debug(s">> Received diameter request $decodedMessage")
      }
      else {
        // If response, check where to send it to, and clean from map
        requestMapOut(decodedMessage.hopByHopId) match {
            // DiameterKey not needed for this type of stats
          case Some(RequestEntry(_, requestTimestamp, destActor, _)) =>
            destActor ! decodedMessage
            MetricsOps.pushDiameterAnswerReceived(metricsServer, peerHostName, decodedMessage, requestTimestamp)
            if(log.isDebugEnabled) log.debug(s">> Received diameter answer $decodedMessage")
          case None =>
            MetricsOps.pushDiameterDiscardedAnswer(metricsServer, peerHostName, decodedMessage)
            if(log.isDebugEnabled) log.warning(s"Unsolicited or staled response $decodedMessage")
        }
      }

    /*
    Send Base Diameter message
     */
    case BaseDiameterMessageSend(message) =>
      q.offer(message.getBytes)
      if(message.isRequest){
        requestMapIn(message, self)
        MetricsOps.pushDiameterRequestSent(metricsServer, peerHostName, message)
        if(log.isDebugEnabled) log.debug(s"<< Sent diameter request $message")
      } else{
        MetricsOps.pushDiameterAnswerSent(metricsServer, peerHostName, message)
        if(log.isDebugEnabled) log.debug(s"<< Sent diameter answer $message")
      }

    /*
    Send Diameter answer
     */
    case message: DiameterMessage => 
      q.offer(message.getBytes)
      MetricsOps.pushDiameterAnswerSent(metricsServer, peerHostName, message)
      if(log.isDebugEnabled) log.debug(s"<< Sent diameter answer $message")
      
    /*
    Send Diameter request
     */
    case RoutedDiameterMessage(message, originActor) =>
      requestMapIn(message, originActor)
      q.offer(message.getBytes)
      MetricsOps.pushDiameterRequestSent(metricsServer, peerHostName, message)
      if(log.isDebugEnabled) log.debug(s"<< Sent diameter request $message")

    case Clean => 
      requestMapClean
      cleanTimer = Some(context.system.scheduler.scheduleOnce(cleanIntervalMillis.milliseconds, self, Clean))
      MetricsOps.updateDiameterPeerQueueGauge(metricsServer, peerHostName, requestMap.size)
      
    case CERTimeout =>
      log.error("No answer to CER received from Peer")
      psKillSwitch.foreach(_.shutdown())
      
    case DWRTimeout =>
      log.error("No answer to DWR received from Peer")
      psKillSwitch.foreach(_.shutdown())
      
    case SendDWR =>
      sendDWR()
      dwrTimer = Some(context.system.scheduler.scheduleOnce(watchdogIntervalMillis.milliseconds, self, SendDWR))
      
    case SendCER =>
      sendCER()
  }
  
  override def preStart: Unit = {
    log.info("Started Peer Actor")
    // If active peer, try to establish connection here
    peerConfig match {
      case Some(pc) if pc.connectionPolicy == "active" =>
        log.info(s"Peer has active policy. Trying to connect to $pc")
        Tcp().outgoingConnection(
            new InetSocketAddress(pc.IPAddress, pc.port),
            Some(new InetSocketAddress(DiameterConfigManager.diameterConfig.bindAddress, 0)),
            connectTimeout = 5.seconds
            )
            .joinMat(handler)(Keep.both).run match {
          case (cf, (ks, q)) => 
            // The materialized result from the connection is a Future[Connection]
            psKillSwitch = Some(ks)
            cf.onComplete {
              // Warning, this is executed out of the actor thread
              case Success(connection) => 
                log.info(s"Connected to Peer $pc")
                remoteAddress = Some(connection.remoteAddress.getHostString)
                context.become(receiveConnected(ks, q))
                self ! SendCER
                // peerHostMap update will take place when Capabilities-Exchange process is finished
                
              case Failure(e) =>
                log.warning(s"Unable to connect to Peer $pc due to $e")
                // Failure will show also in the handler flow and un-registration takes place there, just in case
            }
        }
      case _ => 
        // Peer not with "active" policy. Do not try to establish connection
    }
  
    // Send first cleaning
    cleanTimer = Some(context.system.scheduler.scheduleOnce(cleanIntervalMillis.milliseconds, self, Clean))
  }
  
  override def postStop: Unit= {
    // TODO: Check if this is called on restart
    log.info("Peer postStop. Closing resources")

    // If not already disconnected, do it now
    psKillSwitch.foreach(_.shutdown())

    // Signal we are not active anymore to Router
    context.parent ! Router.PeerDown
    
    // Clean all timers
    cleanTimer.foreach(_.cancel)
    cerTimeoutTimer.foreach(_.cancel)
    dwrTimeoutTimer.foreach(_.cancel)
    dwrTimer.foreach(_.cancel)
    
    // Signal that this peer now has no queue size to report
    MetricsOps.updateDiameterPeerQueueGauge(metricsServer, peerHostName, -1)
  }
  
  ////////////////////////////////////////////////////////////////////////////
  // Request Map. Stores entries for each one of the Diameter requests
  // waiting for an answer
  ////////////////////////////////////////////////////////////////////////////

  // Class for each one of the entries
  private case class RequestEntry(hopByHopId: Long, requestTimestamp: Long, sendingActor: ActorRef, key: DiameterMessageKey)

  // Map of requests, keyed by HopByHopId
  private val requestMap = scala.collection.mutable.Map[Int, RequestEntry]()
  
  private def requestMapIn(diameterMessage: DiameterMessage, sendingActor: ActorRef): Unit = {
    if(log.isDebugEnabled) log.debug("Request Map -> in {}", diameterMessage.hopByHopId)
    requestMap(diameterMessage.hopByHopId) = RequestEntry(diameterMessage.hopByHopId, System.currentTimeMillis(), sendingActor, diameterMessage.key)
  }
  
  private def requestMapOut(hopByHopId : Int) : Option[RequestEntry] = {
    if(log.isDebugEnabled) log.debug("Request Map <- out {}", hopByHopId)
    requestMap.remove(hopByHopId)
  }
  
  private def requestMapClean = {
    val targetTimestamp = System.currentTimeMillis() - responseTimeoutMillis - 300 // Grace time
    requestMap.retain((_, v) => v.requestTimestamp > targetTimestamp)
  }
  
  ////////////////////////////////////////////////////////////////////////////
  // Handlers
  ////////////////////////////////////////////////////////////////////////////
  
  private def handleDiameterBase(diameterMessage: DiameterMessage) = {
    
    diameterMessage.command match {
      case "Capabilities-Exchange" if diameterMessage.isRequest => handleCER(diameterMessage)
      case "Capabilities-Exchange" if !diameterMessage.isRequest => handleCEA(diameterMessage)
      case "Device-Watchdog" if diameterMessage.isRequest => handleDWR(diameterMessage)
      case "Device-Watchdog" if !diameterMessage.isRequest => handleDWA(diameterMessage)
      case "Disconnect-Peer" if diameterMessage.isRequest => handleDPR(diameterMessage)
      case "Disconnect-Peer" if !diameterMessage.isRequest => handleDPA(diameterMessage)
      case _ =>
        log.error(s"Unknown message ${diameterMessage.command}")
    }
  }
  
  /////////////////////////////////////////////////////////////////////////////
  // Capabilities-Exchange
  /////////////////////////////////////////////////////////////////////////////
  
  // Used when active policy
  private def sendCER(): Unit = {
    val message = DiameterMessage.request("Base", "Capabilities-Exchange")

    val diameterConfig = DiameterConfigManager.diameterConfig

    if(diameterConfig.bindAddress != "0.0.0.0") message << ("Host-IP-Address" -> diameterConfig.bindAddress)
    message << ("Vendor-Id" -> diameterConfig.vendorId)
    message << ("Product-Name" -> "Yaas")
    message << ("Firmware-Revision" -> diameterConfig.firmwareRevision)
    // TODO: Check what to do with this. Should increase on restart
    message << ("Origin-State-Id" -> 1)

    // Add supported applications. Used Set to remove duplicates
    for (applicationId <- DiameterConfigManager.diameterRouteConfig.map(_.applicationId).toSet[String]){
      if(applicationId != "*"){
        DiameterDictionary.appMapByName.get(applicationId).map(dictItem => {
          if(dictItem.appType.contains("auth")) message << ("Auth-Application-Id", dictItem.code)
          if(dictItem.appType.contains("acct")) message << ("Acct-Application-Id", dictItem.code)
        })
      }
    }
    
    // Add supported vendors
    DiameterDictionary.vendorNames.foreach{ case (code, _) => message << ("Supported-Vendor-Id" -> code)}
    
    // Send the message
    self ! BaseDiameterMessageSend(message)
    
    // Setup timer
    cerTimeoutTimer = Some(context.system.scheduler.scheduleOnce(responseTimeoutMillis.milliseconds, self, CERTimeout))
    
    log.info(s"CER --> $peerHostName")
  }
  
  private def handleCEA(message: DiameterMessage): Unit = {
    
    log.info(s"CEA <-- $peerHostName")
    
    // Clear Capabilities-Exchange timer
    cerTimeoutTimer.foreach(_.cancel)

    // Check received host name
    val receivedOriginHost = message.getAsString("Origin-Host")
    if(receivedOriginHost == peerHostName){
      // Ignore the rest of the message and set Peer relationship as established
      context.parent ! peerConfig.get

      // Start DWR process
      self ! SendDWR

    } else {
      log.error(s"Received Origin-Host $receivedOriginHost is not as expected $peerHostName")
      psKillSwitch.foreach(_.shutdown)
    }
  }

  // Used when passive policy
  private def handleCER(message: DiameterMessage): Unit = {
    
    log.info(s"CER <-- $peerHostName")
    
    val answer = DiameterMessage.answer(message)
    
    // Check peer parameters
    DiameterConfigManager.findDiameterPeer(remoteAddress.getOrElse("<none>"), message >> "Origin-Host") match {
      case Some(diameterConfig) => 
        sendCEASuccess(diameterConfig)
      
      case _ =>
        log.warning("Origin-Host {} does not match the remoteAddress {}", message >> "Origin-Host", remoteAddress.getOrElse("<none>"))
        sendCEAFailure()
    }
    
    def sendCEASuccess(diameterPeerConfig: DiameterPeerConfig): Unit = {

      // Set configuration of peer (was not received in Actor creation)
      peerHostName = diameterPeerConfig.diameterHost
      watchdogIntervalMillis = diameterPeerConfig.watchdogIntervalMillis
      
      // Add basic parameters in response
      val diameterConfig = DiameterConfigManager.diameterConfig
          
      if(diameterConfig.bindAddress != "0.0.0.0") answer << ("Host-IP-Address" -> diameterConfig.bindAddress)
      answer << ("Vendor-Id" -> diameterConfig.vendorId)
      answer << ("Firmware-Revision" -> diameterConfig.firmwareRevision)

      // Add supported applications. Used Set to remove duplicates
      for (applicationId <- DiameterConfigManager.diameterRouteConfig.map(_.applicationId).toSet[String]){
        if(applicationId != "*"){
          DiameterDictionary.appMapByName.get(applicationId).map(dictItem => {
            if(dictItem.appType.contains("auth")) answer << ("Auth-Application-Id", dictItem.code)
            if(dictItem.appType.contains("acct")) answer << ("Acct-Application-Id", dictItem.code)
          })
        }
      }
      
      answer << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
      
      // Answer with CEA
      self ! BaseDiameterMessageSend(answer)
      
      // Register actor in Router
      context.parent ! diameterPeerConfig
      
      // Start DWR process
      self ! SendDWR
      
      log.info(s"CEA --> $peerHostName")
      log.info("Sent CEA. New Peer Actor is up for {}", peerHostName)
    }

    def sendCEAFailure(): Unit = {
      answer << ("Result-Code" -> DiameterMessage.DIAMETER_UNKNOWN_PEER)

      self ! BaseDiameterMessageSend(answer)
      log.warning(s"Sending CEA with failure because Peer is unknonwn. Received message $message")

      // Terminate
      psKillSwitch.foreach(_.shutdown())

      log.info(s"CEA (Failure) --> $peerHostName")
    }
  }
  
  /////////////////////////////////////////////////////////////////////////////
  // Disconnect-Peer
  /////////////////////////////////////////////////////////////////////////////
    
  private def handleDPR(message: DiameterMessage): Unit = {
    
    log.info(s"DPR <-- $peerHostName")
    
    val answer = DiameterMessage.answer(message)
    
    answer << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
    
    self ! BaseDiameterMessageSend(answer)

    // According to the specification, we are supposed to take note of this request and do not
    // try to reconnect. We don't do that
    
    log.info(s"DPA --> $peerHostName")
  }
  
  private def handleDPA(message: DiameterMessage): Unit = {
    psKillSwitch.foreach(_.shutdown())

    log.info(s"DPA <-- $peerHostName")
  }  
  
  /////////////////////////////////////////////////////////////////////////////
  // Device-Watchdog
  /////////////////////////////////////////////////////////////////////////////
  
  private def sendDWR(): Unit = {
    val request = DiameterMessage.request("Base", "Device-Watchdog")
    
    self ! BaseDiameterMessageSend(request)
    
    // Setup timer
    dwrTimeoutTimer = Some(context.system.scheduler.scheduleOnce(responseTimeoutMillis.milliseconds, self, DWRTimeout))
   
    log.debug(s"DWR -->  $peerHostName")
  }
  
  private def handleDWA(message: DiameterMessage) = {
    log.debug(s"DWA <-- $peerHostName")
    
    dwrTimeoutTimer.map(_.cancel)
  }
  
  private def handleDWR(message: DiameterMessage): Unit = {
    log.debug(s"DWR <-- $peerHostName")
    
    val answer = DiameterMessage.answer(message)
    
    answer << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
    
    self ! BaseDiameterMessageSend(answer)
    
    log.debug(s"DWA --> $peerHostName")
  }
}