package yaas.server

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill, Cancellable }
import akka.event.{ Logging, LoggingReceive }

import yaas.config.DiameterConfigManager
import yaas.dictionary.DiameterDictionary
import yaas.server.Router.RoutedDiameterMessage
import yaas.coding.{DiameterMessage, DiameterMessageKey}
import yaas.coding.DiameterConversions._
import yaas.config.{DiameterPeerConfig}
import yaas.instrumentation.MetricsOps

import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import scala.concurrent.duration._
import scala.util.{Success, Failure}

import com.typesafe.config.ConfigFactory

import java.net.InetSocketAddress

// Best practise
/**
 * Constructor and helpers for DiameterPeer.
 */
object DiameterPeer {
  def props(config: Option[DiameterPeerConfig], metricsServer: ActorRef) = Props(new DiameterPeer(config, metricsServer))
  
  case object Clean
  case object CERTimeout
  case object DWRTimeout
  case object SendDWR
  case object SendCER
  
  case class BaseDiameterMessageReceive(message: DiameterMessage)
  case class BaseDiameterMessageSend(message: DiameterMessage)
  case class DiameterMessageReceive(message: DiameterMessage)
  
  val config = ConfigFactory.load().getConfig("aaa.diameter")
  val messageQueueSize = config.getInt("peerMessageQueueSize")
  val cleanIntervalMillis = config.getInt("cleanIntervalMillis")
  val cerTimeoutMillis = config.getInt("cerTimeoutMillis")
  val responseTimeoutMillis = config.getInt("responseTimeoutMillis")
}

/* 
 * Peer created with "active" policy
 * 	- The actor is instantiated by the router, and marked as "Starting"
 *  - The actor tries to establish the connection.
 *  	> Sends CER and sets timer
 *  	> When CEA is received, sends message to Router to be marked as "Running" and starts the DWR timer
 *  
 * Peer created with "passive" policy
 *  - The actor is instantiated when a connection is received, passing the connection
 *  - Waits for the reception of CER
 *  - Upon answering (CEA), sends message to Router to be marked as "Running" and starts the DWR timer
 *  
 *  In case of any error, the Actor sends itself a PoisonPill and terminates.
 */

/**
 * Actor executing a DiameterPeer.
 * 
 * If created by the server ("active" policy), <code>config</config> has a value. Otherwise is set to None.
 */
class DiameterPeer(val config: Option[DiameterPeerConfig], val metricsServer: ActorRef) extends Actor with ActorLogging {
  
  import DiameterPeer._
  import context.dispatcher

  implicit val materializer = ActorMaterializer()
  implicit val actorSytem = context.system
  
  var remoteAddr: Option[String] = None
  var peerHostName = config.map(_.diameterHost).getOrElse("<HostName-Not-Estabished>")
  var killSwitch: Option[KillSwitch] = None
  var cleanTimer: Option[Cancellable] = None
  var cerTimeoutTimer: Option[Cancellable] = None
  var dwrTimeoutTimer: Option[Cancellable] = None
  var dwrTimer: Option[Cancellable] = None
  var watchdogIntervalMillis = config.map(_.watchdogIntervalMillis).getOrElse(30000)
  
  // The TCP handler is made of a Source and a Sink
  // The Source is a queue of packets to be sent
  val handlerSource = Source.queue[ByteString](messageQueueSize, akka.stream.OverflowStrategy.dropTail)
  // The Sink treats the received packets
  val handlerSink = Flow[ByteString]
    // Framing.lengthField does not seem to work as expected if the computeFrameSize is not used
    // may be that is because the frame length excludes the offset
    .via(Framing.lengthField(3, 1, 10000, java.nio.ByteOrder.BIG_ENDIAN, (offsetBytes, calcFrameSize) => {calcFrameSize}))
    .viaMat(KillSwitches.single)(Keep.right)
    .map(frame => {
      // Decode message
      try{
        val decodedMessage = yaas.coding.DiameterMessage(frame)
        // If Base, handle in this PeerActor
        if(decodedMessage.applicationId == 0){
          // Stats are recorded in the receive function
          self ! BaseDiameterMessageReceive(decodedMessage)
        }
        else {
          self ! DiameterMessageReceive(decodedMessage)
        }
      } catch {
        case e: Throwable =>
          log.error(e, "Frame decoding error")
          context.parent ! Router.PeerDown
          context.stop(self)
      }
    })
    .to(Sink.onComplete((f) => {
        log.info("Closed connection with remote peer")
        context.parent ! Router.PeerDown
        context.stop(self)
      })
    )
  
  // Handler to be associated to the TCP connection
  val handler = Flow.fromSinkAndSourceMat(handlerSink, handlerSource)(Keep.both)
  
  /**
   * Initial Receive method
   */
  def receive = LoggingReceive {
    
    // Received server connection
    case connection: Tcp.IncomingConnection => 
        log.info("Connected to {}", connection.remoteAddress.getHostString)
        connection.handleWith(handler) match {
          case (ks, q) => 
            killSwitch = Some(ks)
            remoteAddr = Some(connection.remoteAddress.getHostString)
            context.become(receiveConnected(ks, q, connection.remoteAddress.getHostString))
      }
  }
  
  /**
   * Receive method for connected Actor
   */
  def receiveConnected(ks: KillSwitch, q: SourceQueueWithComplete[ByteString], remote: String) : Receive = {
    
    case BaseDiameterMessageReceive(decodedMessage) =>
      if(!decodedMessage.isRequest) {
        requestMapOut(decodedMessage.hopByHopId) match {
          case Some(RequestEntry(hopByHopId, requestTimestamp, sendingActor, key)) =>
            MetricsOps.pushDiameterAnswerReceived(metricsServer, peerHostName, decodedMessage, requestTimestamp)
            log.debug(s">> Received diameter answer decodedMessage")
          case None =>
            // Unsolicited or stalled response. 
            MetricsOps.pushDiameterDiscardedAnswer(metricsServer, peerHostName, decodedMessage)
        }
      } else{
        MetricsOps.pushDiameterRequestReceived(metricsServer, peerHostName, decodedMessage)
        log.debug(s">> Received diameter request decodedMessage")
      }
          
      handleDiameterBase(decodedMessage)
      
    // Non base message received
    case DiameterMessageReceive(decodedMessage) =>
      // If request, route message
      if(decodedMessage.isRequest){
        context.parent ! decodedMessage
        MetricsOps.pushDiameterRequestReceived(metricsServer, peerHostName, decodedMessage)
        log.debug(s">> Received diameter request $decodedMessage")
      }
      // If response, check where to send it to, and clean from map
      else {
        requestMapOut(decodedMessage.hopByHopId) match {
          case Some(RequestEntry(hopByHopId, requestTimestamp, destActor, messageKey)) => 
            destActor ! decodedMessage
            MetricsOps.pushDiameterAnswerReceived(metricsServer, peerHostName, decodedMessage, requestTimestamp)
            log.debug(s">> Received diameter answer $decodedMessage")
          case None =>
            MetricsOps.pushDiameterDiscardedAnswer(metricsServer, peerHostName, decodedMessage)
            log.warning(s"Unsolicited or staled response $decodedMessage")
        }
      }
      
    case BaseDiameterMessageSend(message) =>
      q.offer(message.getBytes)
      if(message.isRequest){
        requestMapIn(message, self)
        MetricsOps.pushDiameterRequestSent(metricsServer, peerHostName, message)
        log.debug(s"<< Sent diameter request $message")
      } else{
        MetricsOps.pushDiameterAnswerSent(metricsServer, peerHostName, message)
        log.debug(s"<< Sent diameter answer $message")
      }
      
    // Message to send a answer to peer
    case message: DiameterMessage => 
      q.offer(message.getBytes)
      MetricsOps.pushDiameterAnswerSent(metricsServer, peerHostName, message)
      log.debug(s"<< Sent diameter answer $message")
      
    // Message to send request to peer
    case RoutedDiameterMessage(message, originActor) =>
      requestMapIn(message, originActor)
      q.offer(message.getBytes)
      MetricsOps.pushDiameterRequestSent(metricsServer, peerHostName, message)
      log.debug(s"<< Sent diameter request $message")

    case Clean => 
      requestMapClean
      cleanTimer = Some(context.system.scheduler.scheduleOnce(cleanIntervalMillis milliseconds, self, Clean))
      MetricsOps.updateDiameterPeerRequestQueueGauge(metricsServer, peerHostName, requestMap.size)
      
    case CERTimeout =>
      log.error("No answer to CER received from Peer")
      context.parent ! Router.PeerDown
      context.stop(self)
      
    case DWRTimeout =>
      log.error("No answer to DWR received from Peer")
      context.parent ! Router.PeerDown
      context.stop(self)
      
    case SendDWR =>
      sendDWR
      dwrTimer = Some(context.system.scheduler.scheduleOnce(watchdogIntervalMillis milliseconds, self, SendDWR))
      
    case SendCER =>
      sendCER
  }
  
  override def preStart = {
    // If active peer, try to establish connection here
    config match {
      case Some(conf) if(conf.connectionPolicy == "active") =>
        log.info(s"Peer has active policy. Trying to connect to $config")
        Tcp().outgoingConnection(
            new InetSocketAddress(conf.IPAddress, conf.port), 
            Some(new InetSocketAddress(DiameterConfigManager.diameterConfig.bindAddress, 0)), 
            connectTimeout = 5 seconds)
            .joinMat(handler)(Keep.both).run match {
          case (cf, (ks, q)) => 
            // The materialized result from the connection is a Future[Connection]
            cf.onComplete {
              case Success(connection) => 
                log.info(s"Connected to Peer $config")
                context.become(receiveConnected(ks, q, connection.remoteAddress.getHostString))
                
                // TODO: Warning, this is executed out of the actor thread
                killSwitch = Some(ks)
                remoteAddr = Some(connection.remoteAddress.getHostString)
                self ! SendCER
                // peerHostMap update will take place when Capabilities-Exchange process is finished
                
              case Failure(e) =>
                log.warning(s"Unable to connect to Peer $config due to $e")
                // Failure will show also in the handler flow and unregistration takes place there
            }
        }
      case _ => 
        // Peer not with "active" policy. Do not try to establish connection
    }
  
    // Send first cleaning
    cleanTimer = Some(context.system.scheduler.scheduleOnce(cleanIntervalMillis milliseconds, self, Clean))
  }
  
  override def postStop = {
    // TODO: Check if this is called on restart
    log.info("Peer postStop. Closing resources")
    killSwitch.map(_.shutdown())
    
    // Clean all timers
    cleanTimer.map(_.cancel)
    cerTimeoutTimer.map(_.cancel)
    dwrTimeoutTimer.map(_.cancel)
    dwrTimer.map(_.cancel)
    
    // Signal that this peer now has no queue size to report
    MetricsOps.updateDiameterPeerRequestQueueGauge(metricsServer, peerHostName, -1)
  }
  
  ////////////////////////////////////////////////////////////////////////////
  // Request Map
  ////////////////////////////////////////////////////////////////////////////
  case class RequestEntry(hopByHopId: Long, requestTimestamp: Long, sendingActor: ActorRef, key: DiameterMessageKey)
  
  val requestMap = scala.collection.mutable.Map[Int, RequestEntry]()
  
  def requestMapIn(diameterMessage: DiameterMessage, sendingActor: ActorRef) = {
    log.debug("Request Map -> in {}", diameterMessage.hopByHopId)
    requestMap(diameterMessage.hopByHopId) = RequestEntry(diameterMessage.hopByHopId, System.currentTimeMillis(), sendingActor, diameterMessage.key)
  }
  
  def requestMapOut(hopByHopId : Int) : Option[RequestEntry] = {
    log.debug("Request Map <- out {}", hopByHopId)
    requestMap.remove(hopByHopId)
  }
  
  def requestMapClean = {
    val targetTimestamp = System.currentTimeMillis() - responseTimeoutMillis
    requestMap.retain((k, v) => v.requestTimestamp > targetTimestamp)
  }
  
  ////////////////////////////////////////////////////////////////////////////
  // Handlers
  ////////////////////////////////////////////////////////////////////////////
  
  def handleDiameterBase(diameterMessage: DiameterMessage) = {
    
    diameterMessage.command match {
      case "Capabilities-Exchange" if(diameterMessage.isRequest) => handleCER(diameterMessage)
      case "Capabilities-Exchange" if(!diameterMessage.isRequest) => handleCEA(diameterMessage)
      case "Device-Watchdog" if(diameterMessage.isRequest) => handleDWR(diameterMessage)
      case "Device-Watchdog" if(!diameterMessage.isRequest) => handleDWA(diameterMessage)
      case "Disconnect-Peer" if(diameterMessage.isRequest) => handleDPR(diameterMessage)
      case "Disconnect-Peer" if(!diameterMessage.isRequest) => handleDPA(diameterMessage)
      case _ =>
        log.error("Unknown message ${diameterMessage.command}")
    }
  }
  
  /////////////////////////////////////////////////////////////////////////////
  // Capabilities-Exchange
  /////////////////////////////////////////////////////////////////////////////
  
  // Active policy
  
  def sendCER = {
    val message = DiameterMessage.request("Base", "Capabilities-Exchange")
    
    message << ("Host-IP-Address" -> config.get.IPAddress)
    message << ("Vendor-Id" -> 1)
    message << ("Product-Name" -> "Yaas")
    message << ("Firmware-Revision" -> 1)
    message << ("Origin-State-Id" -> 1)
    
    // Supported applications into a set to remove duplicates
    val supportedAppDictItems = for {
      route <- DiameterConfigManager.diameterRouteConfig.toSet if(route.applicationId != "*")
    } yield DiameterDictionary.appMapByName.get(route.applicationId)
    
    // Add to message
    for (Some(dictItem) <- supportedAppDictItems) {
        if(dictItem.appType == Some("auth")) message << ("Auth-Application-Id", dictItem.code)
        if(dictItem.appType == Some("acct")) message << ("Acct-Application-Id", dictItem.code)
    }
    
    // Add supported vendors
    DiameterDictionary.vendorNames.foreach{ case (code, vendorName) => message << ("Supported-Vendor-Id" -> code)}
    
    // Send the message
    self ! BaseDiameterMessageSend(message)
    
    // Setup timer (10 seconds)
    cerTimeoutTimer = Some(context.system.scheduler.scheduleOnce(cerTimeoutMillis milliseconds, self, CERTimeout))
    
    log.info(s"CER --> $peerHostName")
  }
  
  def handleCEA(message: DiameterMessage) = {
    
    log.info(s"CEA <-- $peerHostName")
    
    // Clear Capabilities-Exchange timer
    cerTimeoutTimer.map(_.cancel)
    
    // Ignore whatever is received and set Peer relationship as established
    context.parent ! config.get
    
    // Start DWR process
    self ! SendDWR
  }
  
  
  // Passive policy
  
  def handleCER(message: DiameterMessage) = {
    
    log.info(s"CER <-- $peerHostName")
    
    val answer = DiameterMessage.answer(message)
    
    // Check peer parameters
    DiameterConfigManager.findDiameterPeer(remoteAddr.getOrElse("<none>"), message >> "Origin-Host") match {
      case Some(diameterConfig) => 
        sendSuccess(diameterConfig)
      
      case _ =>
        log.warning("Origin-Host {} does not match the remoteAddress {}", message >> "Origin-Host", remoteAddr.getOrElse("<none>"))
        sendFailure
    }
    
    def sendFailure = {
      answer << ("Result-Code" -> DiameterMessage.DIAMETER_UNKNOWN_PEER)
      
      self ! BaseDiameterMessageSend(answer)
      log.warning(s"Sending CEA with failure because Peer is unknonw. Received message $message")
      
      self ! PoisonPill
      
      log.info(s"CEA (Failure) --> $peerHostName")
    }
    
    def sendSuccess(diameterPeerConfig: DiameterPeerConfig) = {
      peerHostName = diameterPeerConfig.diameterHost
      
      // Add basic parameters
      val diameterConfig = DiameterConfigManager.diameterConfig
      val bindAddress = diameterConfig.bindAddress
          
      if(diameterConfig.bindAddress != "0.0.0.0") answer << ("Host-IP-Address" -> diameterConfig.bindAddress)
      answer << ("Vendor-Id" -> diameterConfig.vendorId)
      answer << ("Firmware-Revision" -> diameterConfig.firmwareRevision)
      
      // Add supported applications
      for (route <- DiameterConfigManager.diameterRouteConfig){
         if(route.applicationId != "*"){
           DiameterDictionary.appMapByName.get(route.applicationId).map(dictItem => {
             if(dictItem.appType == Some("auth")) answer << ("Auth-Application-Id", dictItem.code)
             if(dictItem.appType == Some("acct")) answer << ("Acct-Application-Id", dictItem.code)
           })
         }
      }
      
      answer << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
      
      // Answer with CEA
      self ! BaseDiameterMessageSend(answer)
      
      // Register actor in router
      context.parent ! diameterPeerConfig
      
      // Start DWR process
      watchdogIntervalMillis = diameterPeerConfig.watchdogIntervalMillis
      self ! SendDWR
      
      log.info(s"CEA --> $peerHostName")
      log.info("Sent CEA. New Peer Actor is up for {}", peerHostName)
    }
  }
  
  /////////////////////////////////////////////////////////////////////////////
  // Disconnect-Peer
  /////////////////////////////////////////////////////////////////////////////
    
  def handleDPR(message: DiameterMessage) = {
    
    log.info(s"DPR <-- $peerHostName")
    
    val answer = DiameterMessage.answer(message)
    
    answer << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
    
    self ! BaseDiameterMessageSend(answer)
    
    context.parent ! Router.PeerDown
    
    self ! PoisonPill
    
    log.info(s"DPA --> $peerHostName")
  }
  
  def handleDPA(message: DiameterMessage) = {
    // Do nothing
    log.info(s"DPA <-- $peerHostName")
  }  
  
  /////////////////////////////////////////////////////////////////////////////
  // Device-Watchdog
  /////////////////////////////////////////////////////////////////////////////
  
  def sendDWR = {
    val request = DiameterMessage.request("Base", "Device-Watchdog")
    
    self ! BaseDiameterMessageSend(request)
    
    // Setup timer
    dwrTimeoutTimer = Some(context.system.scheduler.scheduleOnce(watchdogIntervalMillis / 2 milliseconds, self, DWRTimeout))
   
    log.info(s"DWR -->  $peerHostName")
  }
  
  def handleDWA(message: DiameterMessage) = {
    log.info(s"DWA <-- $peerHostName")
    
    dwrTimeoutTimer.map(_.cancel)
  }
  
  def handleDWR(message: DiameterMessage) = {
    log.info(s"DWR <-- $peerHostName")
    
    val answer = DiameterMessage.answer(message)
    
    answer << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
    
    self ! BaseDiameterMessageSend(answer)
    
    log.info(s"DWA --> $peerHostName")
  }

}