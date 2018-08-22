package yaas.server

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill, Cancellable }
import akka.event.{ Logging, LoggingReceive }

import yaas.config.DiameterConfigManager
import yaas.dictionary.DiameterDictionary
import yaas.server.Router.RoutedDiameterMessage
import yaas.coding.diameter.DiameterMessage
import yaas.coding.diameter.DiameterConversions._
import yaas.config.{DiameterPeerConfig}
import akka.stream._
import akka.stream.scaladsl._
import scala.concurrent.duration._
import scala.util.{Success, Failure}
import akka.util.ByteString
import java.net.InetSocketAddress

// Best practise
object DiameterPeer {
  def props(config: Option[DiameterPeerConfig]) = Props(new DiameterPeer(config))
  
  case object Clean
  case object CERTimeout
  case object DWRTimeout
  case object DWR
  case object CER
  
  case class BaseDiameterMessageReceived(message: DiameterMessage)
  case class BaseDiameterMessageToSend(message: DiameterMessage)
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

class DiameterPeer(val config: Option[DiameterPeerConfig]) extends Actor with ActorLogging {
  
  val messageQueueSize = 1000
  val cleanIntervalMillis = 250
  val cerTimeoutMillis = 10000
  
  import DiameterPeer._
  import context.dispatcher
  
  implicit val idGen = new yaas.util.IDGenerator
  implicit val materializer = ActorMaterializer()
  implicit val actorSytem = context.system
  
  // var peerConfig: Option[DiameterPeerConfig] = config
  var remoteAddr: Option[String] = None
  var peerHostName = config.map(_.diameterHost).getOrElse("<HostName-Not-Estabished>")
  var killSwitch: Option[KillSwitch] = None
  var cleanTimer: Option[Cancellable] = None
  var cerTimeoutTimer: Option[Cancellable] = None
  var dwrTimeoutTimer: Option[Cancellable] = None
  var dwrTimer: Option[Cancellable] = None
  var watchdogIntervalMillis = config.map(_.watchdogIntervalMillis).getOrElse(30000)
  
  val handlerSource = Source.queue[ByteString](messageQueueSize, akka.stream.OverflowStrategy.dropTail)
  val handlerSink = Flow[ByteString]
    // Framing.lengthField does not seem to work as expected if the computeFrameSize is not used
    // may be that is because the frame length excludes the offset
    .via(Framing.lengthField(3, 1, 10000, java.nio.ByteOrder.BIG_ENDIAN, (offsetBytes, calcFrameSize) => {calcFrameSize}))
    .viaMat(KillSwitches.single)(Keep.right)
    .map(frame => {
      // Decode message
      try{
        val decodedMessage = yaas.coding.diameter.DiameterMessage(frame)
        // If Base, handle in this PeerActor
        if(decodedMessage.applicationId == 0) self ! BaseDiameterMessageReceived(decodedMessage)
        else {
          // If request, route message
          if(decodedMessage.isRequest) context.parent ! decodedMessage
          // If response, check where to send it to, and clean from cache
          else {
            cacheOut(decodedMessage.hopByHopId) match {
              case Some(destActor) => destActor ! decodedMessage
              case None =>
                log.warning(s"Unsolicited or staled response $decodedMessage")
            }
          }
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
    
    case BaseDiameterMessageReceived(message) =>
      log.debug(s"Received Base Diameter Message $message")
      handleDiameterBase(message)
      
    case BaseDiameterMessageToSend(message) =>
      log.debug(s"Sending Base Diameter Message $message")
      q.offer(message.getBytes)
      
    // Message to send a response to peer or Diameter Base generated by this Actor
    case message: DiameterMessage => 
      q.offer(message.getBytes)
      
    // Message to send request to peer
    case RoutedDiameterMessage(message, originActor) =>
      cacheIn(message.hopByHopId, originActor)
      q.offer(message.getBytes)

    case Clean => 
      cacheClean
      cleanTimer = Some(context.system.scheduler.scheduleOnce(cleanIntervalMillis milliseconds, self, Clean))
      
    case CERTimeout =>
      log.error("No answer to CER received from Peer")
      context.parent ! Router.PeerDown
      context.stop(self)
      
    case DWRTimeout =>
      log.error("No answer to DWR received from Peer")
      context.parent ! Router.PeerDown
      context.stop(self)
      
    case DWR =>
      sendDWR
      dwrTimer = Some(context.system.scheduler.scheduleOnce(watchdogIntervalMillis milliseconds, self, DWR))
      
    case CER =>
      sendCER
  }
  
  override def preStart = {
    
    // If active peer, try to establish connection here
    config match {
      case Some(conf) if(conf.connectionPolicy == "active") =>
        log.info(s"Peer has active policy. Trying to connect to $config")
        Tcp().outgoingConnection(
            new InetSocketAddress(conf.IPAddress, conf.port), 
            Some(new InetSocketAddress(DiameterConfigManager.getDiameterConfig.bindAddress, 0)), 
            connectTimeout = 10 seconds)
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
                self ! CER
                // peerHostMap update will take place when Capabilities-Exchange process is finished
                
              case Failure(e) =>
                log.error(s"Unable to connect to Peer $config due to $e")
                context.stop(self)
            }
        }
      case _ => 
        // Peer not in "active" policy. Do not try to establish connection
    }
  
    // Send first cleaning
    cleanTimer = Some(context.system.scheduler.scheduleOnce(250 milliseconds, self, Clean))
  }
  
  override def postStop = {
    // TODO: Check if this is called on restart
    log.info("Closing connection")
    killSwitch.map(_.shutdown())
    
    // Clean all timers
    cleanTimer.map(_.cancel)
    cerTimeoutTimer.map(_.cancel)
    dwrTimeoutTimer.map(_.cancel)
    dwrTimer.map(_.cancel)
  }
  
  ////////////////////////////////////////////////////////////////////////////
  // Cache
  ////////////////////////////////////////////////////////////////////////////
  case class RequestEntry(timestamp: Long, sendingActor: ActorRef)
  val requestCache = scala.collection.mutable.Map[Int, RequestEntry]()
  
  def cacheIn(hopByHopId: Int, sendingActor: ActorRef) = {
    log.debug("Cache in {}", hopByHopId)
    requestCache(hopByHopId) = RequestEntry(System.currentTimeMillis(), sendingActor)
  }
  
  def cacheOut(hopByHopId : Int) : Option[ActorRef] = {
    log.debug("Cache out {}", hopByHopId)
    requestCache.remove(hopByHopId).map(_.sendingActor)
  }
  
  def cacheClean = {
    val targetTimestamp = System.currentTimeMillis() - 10000 // Fixed 10 seconds timeout to delete old messages. TODO: This should be a configuration parameter
    requestCache.retain((k, v) => v.timestamp > targetTimestamp)
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
    
    // Add supported applications
    for (route <- DiameterConfigManager.getDiameterRouteConfig){
      if(route.applicationId != "*"){
        DiameterDictionary.appMapByName.get(route.applicationId).map(dictItem => {
        if(dictItem.appType == Some("auth")) message << ("Auth-Application-Id", dictItem.code)
        if(dictItem.appType == Some("acct")) message << ("Acct-Application-Id", dictItem.code)
        })
      }
    }
    
    // Add supported vendors
    DiameterDictionary.vendorNames.foreach{ case (code, vendorName) => message << ("Supported-Vendor-Id" -> code)}
    
    // Send the message
    self ! BaseDiameterMessageToSend(message)
    
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
    self ! DWR
  }
  
  
  // Passive policy
  
  def handleCER(message: DiameterMessage) = {
    
    log.info(s"CER <-- $peerHostName")
    
    val reply = DiameterMessage.reply(message)
    
    // Check host name
    DiameterConfigManager.getDiameterPeerConfig.get(message >> "Origin-Host") match {
      case diameterConfig @ Some(DiameterPeerConfig(diameterHost, ipAddr, _, _, _)) => 
        if(ipAddr == remoteAddr.getOrElse("<none>")) sendSuccess (diameterConfig.get)
        else {
          log.warning("Origin-Host {} does not match the remoteAddress {}", message >> "Origin-Host", remoteAddr.getOrElse("<none>"))
          sendFailure
        }
      case _ => 
        log.warning("Origin-Host {} not found in Peer table", message >> "Origin-Host")
        sendFailure
    }
    
    def sendFailure = {
      reply << ("Result-Code" -> DiameterMessage.DIAMETER_UNKNOWN_PEER)
      
      self ! BaseDiameterMessageToSend(reply)
      log.warning(s"Sending CEA with failure because Peer is unknonw. Received message $message")
      
      self ! PoisonPill
      
      log.info(s"CEA (Failure) --> $peerHostName")
    }
    
    def sendSuccess(diameterPeerConfig: DiameterPeerConfig) = {
      peerHostName = diameterPeerConfig.diameterHost
      
      // Add basic parameters
      val diameterConfig = DiameterConfigManager.getDiameterConfig
      val bindAddress = diameterConfig.bindAddress
          
      if(diameterConfig.bindAddress != "0.0.0.0") reply << ("Host-IP-Address" -> diameterConfig.bindAddress)
      reply << ("Vendor-Id" -> diameterConfig.vendorId)
      reply << ("Firmware-Revision" -> diameterConfig.firmwareRevision)
      
      // Add supported applications
      for (route <- DiameterConfigManager.getDiameterRouteConfig){
         if(route.applicationId != "*"){
           DiameterDictionary.appMapByName.get(route.applicationId).map(dictItem => {
             if(dictItem.appType == Some("auth")) reply << ("Auth-Application-Id", dictItem.code)
             if(dictItem.appType == Some("acct")) reply << ("Acct-Application-Id", dictItem.code)
           })
         }
      }
      
      reply << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
      
      // Reply with CEA
      self ! BaseDiameterMessageToSend(reply)
      
      // Register actor in router
      context.parent ! diameterPeerConfig
      
      // Start DWR process
      watchdogIntervalMillis = diameterPeerConfig.watchdogIntervalMillis
      self ! DWR
      
      log.info(s"CEA --> $peerHostName")
      log.info("Sent CEA. New Peer Actor is up for {}", peerHostName)
    }
  }
  
  /////////////////////////////////////////////////////////////////////////////
  // Disconnect-Peer
  /////////////////////////////////////////////////////////////////////////////
    
  def handleDPR(message: DiameterMessage) = {
    
    log.info(s"DPR <-- $peerHostName")
    
    val reply = DiameterMessage.reply(message)
    
    reply << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
    
    self ! BaseDiameterMessageToSend(reply)
    
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
    
    self ! BaseDiameterMessageToSend(request)
    
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
    
    val reply = DiameterMessage.reply(message)
    
    reply << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
    
    self ! BaseDiameterMessageToSend(reply)
    
    log.info(s"DWA --> $peerHostName")
  }

}