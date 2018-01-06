package yaas.diameterServer

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill }
import akka.event.{ Logging, LoggingReceive }

import yaas.diameterServer.config.DiameterConfigManager
import yaas.diameterServer.dictionary.DiameterDictionary
import yaas.diameterServer.DiameterRouter.RoutedDiameterMessage
import yaas.diameterServer.coding.DiameterMessage
import yaas.diameterServer.coding.DiameterConversions._
import yaas.diameterServer.config.{DiameterPeerConfig}
import akka.stream._
import akka.stream.scaladsl._
import scala.concurrent.duration._
import akka.util.ByteString

// Best practise
object DiameterPeer {
  def props(config: Option[DiameterPeerConfig]) = Props(new DiameterPeer(config))
  case class Disconnect()
  case class Clean()
}

class DiameterPeer(val config: Option[DiameterPeerConfig]) extends Actor with ActorLogging {
  
  import DiameterPeer._
  import context.dispatcher
  
  val handlerSource = Source.queue[ByteString](1000, akka.stream.OverflowStrategy.dropTail)
  val handlerSink = Flow[ByteString]
    // Framing.lengthField does not seem to work as expected if the computeFrameSize is not used
    // may be that is because the frame length excludes the offset
    .via(Framing.lengthField(3, 1, 10000, java.nio.ByteOrder.BIG_ENDIAN, (offsetBytes, calcFrameSize) => {calcFrameSize}))
    .viaMat(KillSwitches.single)(Keep.right)
    .map(frame => {
      // Decode message
      try{
        val decodedMessage = yaas.diameterServer.coding.DiameterMessage(frame)
        // If Base, handle locally
        if(decodedMessage.applicationId == 0) handleDiameterBase(decodedMessage)
        else {
          // If request, route message
          if(decodedMessage.isRequest) context.parent ! decodedMessage
          // If response, check where to send it to, and clean from cache
          else {
            cacheOut(decodedMessage.hopByHopId) match {
              case Some(destActor) => destActor ! decodedMessage
              case None =>
                log.warning("Unsolicited or staled response")
            }
          }
        }
      } catch {
        case e: Exception =>
          log.error(e, "Frame decoding error")
      }
    })
    .to(Sink.onComplete((f) => {
        log.info("Closed connection with remote peer")
        inputQueue = None
        // If peer is "passive" policy, or config was not defined, unregister and destroy Actor
        if(config.isEmpty || config.get.connectionPolicy != "active"){
          context.parent ! DiameterRouter.PeerDown
          self ! PoisonPill
        } 
      })
    )
  val handler = Flow.fromSinkAndSourceMat(handlerSink, handlerSource)(Keep.both)
  
  var killSwitch : Option[KillSwitch] = None
  var inputQueue : Option[SourceQueueWithComplete[ByteString]] = None
  var remoteAddress : Option[String] = None
  
  implicit val materializer = ActorMaterializer()
  
  // TODO: If active peer, try to establish connection here
    
  def receive = LoggingReceive {
    // New connection from peer
    case connection: Tcp.IncomingConnection => 
      if(inputQueue.isDefined){
        log.error("Peer {} is already connected", connection.remoteAddress)
        connection.handleWith(Flow.fromSinkAndSource(Sink.cancelled, Source.empty))
      } else {
        log.info("Will handle connection with {}", connection.remoteAddress.getHostString)
        connection.handleWith(handler) match {
          case (ks, q) => 
            killSwitch = Some(ks)
            inputQueue = Some(q)
            remoteAddress = Some(connection.remoteAddress.getHostString)
        }
      }
    
    // Message to send a response to peer
    case message: DiameterMessage =>
      if(inputQueue.isDefined){
        inputQueue.get.offer(message.getBytes)
      }
      else log.warning("Discarding message to unconnected peer")
      
    // Message to send request to peer
    case RoutedDiameterMessage(message, originActor) =>
      if(inputQueue.isDefined){
        cacheIn(message.hopByHopId, originActor)
        inputQueue.get.offer(message.getBytes)
      }
      else log.warning("Discarding message to unconnected peer")
      
    // This user initiated disconnect
    case Disconnect() => 
      log.info("Disconnecting peer")
      killSwitch.map(_.shutdown())
      killSwitch = None
      inputQueue = None
      remoteAddress = None
      
    case Clean() => 
      cacheClean
      context.system.scheduler.scheduleOnce(200 milliseconds, self, Clean())
  }
  
  override def preStart = {
    // Send first cleaning
    context.system.scheduler.scheduleOnce(200 milliseconds, self, Clean())
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
    val targetTimestamp = System.currentTimeMillis() - 10000 // Fixed 10 seconds timeout
    requestCache.retain((k, v) => v.timestamp > targetTimestamp)
  }
  
  ////////////////////////////////////////////////////////////////////////////
  // Handlers
  ////////////////////////////////////////////////////////////////////////////
  
  def handleDiameterBase(diameterMessage: DiameterMessage) = {
    
    log.debug("Received request message for Base application\n {}\n", diameterMessage.toString())
    
    diameterMessage.command match {
      case "Capabilities-Exchange" => handleCER(diameterMessage)
      case "Device-Watchdog" => handleDWR(diameterMessage)
      case "Disconnect-Peer" => handleDPR(diameterMessage)
    }
  }
  
  /*
   * Capabilities-Exchange
   */
  
  def handleCER(message: DiameterMessage) = {
    val reply = DiameterMessage.reply(message)
    
    // Check host name
    DiameterConfigManager.getDiameterPeerConfig.get(message >> "Origin-Host") match {
      case diameterConfig @ Some(DiameterPeerConfig(diameterHost, ipAddr, _, _, _)) => 
        if(ipAddr == remoteAddress.getOrElse("<none>")) sendSuccess (diameterConfig.get)
        else {
          log.warning("Origin-Host {} does not match the remoteAddress {}", message >> "Origin-Host", remoteAddress.getOrElse("<none>"))
          sendFailure
        }
      case _ => 
        log.warning("Origin-Host {} not found in Peer table", message >> "Origin-Host")
        sendFailure
    }
    
    def sendFailure = {
      reply << ("Result-Code" -> DiameterMessage.DIAMETER_UNKNOWN_PEER)
      
      self ! reply
      log.debug("Sent response message for Base application\n {}\n", reply.toString())
      
      self ! Disconnect()
      self ! PoisonPill
    }
    
    def sendSuccess(diameterPeerConfig: DiameterPeerConfig) = {
      // Add basic parameters
      val diameterConfig = DiameterConfigManager.getDiameterConfig
      val bindAddress = diameterConfig.bindAddress
          
      if(diameterConfig.bindAddress != "0.0.0.0") reply << ("Host-IP-Address" -> diameterConfig.bindAddress)
      reply << ("Vendor-Id" -> diameterConfig.vendorId)
      reply << ("Firmware-Revision" -> diameterConfig.firmwareRevision)
      
      // Add supported applications
      for (route <- DiameterConfigManager.getDiameterRouteConfig){
         if(route.applicationId != "*"){
           DiameterDictionary.appMapByName.get(route.applicationId).map(dictItem =>{
             if(dictItem.appType == Some("auth")) reply << ("Auth-Application-Id", dictItem.code)
             if(dictItem.appType == Some("acct")) reply << ("Acct-Application-Id", dictItem.code)
           })
         }
      }
      
      reply << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
      
      // Reply with CEA
      self ! reply
      log.debug("Sent response message for Base application\n {}\n", reply.toString())
      
      // Register actor in router
      context.parent ! diameterPeerConfig
      
      log.info("Sent CEA. New Peer Actor is up for {}", diameterPeerConfig.diameterHost)
    }
  }
  
  /*
   * Disconnect-Peer
   */
    
  def handleDPR(message: DiameterMessage) = {
    val reply = DiameterMessage.reply(message)
    
    reply << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
    
    self ! reply
    log.debug("Sent response message for Base application\n {}\n", reply.toString())
    
    self ! Disconnect()
  }
  
  /*
   * Device-Watchdog
   */
  
  def handleDWR(message: DiameterMessage) = {
    val reply = DiameterMessage.reply(message)
    
    reply << ("Result-Code" -> DiameterMessage.DIAMETER_SUCCESS)
    
    self ! reply
    log.debug("Sent response message for Base application\n {}\n", reply.toString())
  }
}