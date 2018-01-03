package diameterServer

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props }
import akka.event.{ Logging, LoggingReceive }

import diameterServer.config.DiameterConfigManager
import diameterServer.dictionary.DiameterDictionary
import diameterServer.DiameterRouter.RoutedDiameterMessage
import diameterServer.coding.DiameterMessage
import diameterServer.coding.DiameterConversions._
import diameterServer.config.{DiameterPeerConfig}
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString


// Peer tables are made of these items
case class DiameterPeerPointer(config: DiameterPeerConfig, actorRef: ActorRef)

// Best practise
object DiameterPeer {
  def props(config: DiameterPeerConfig) = Props(new DiameterPeer(config))
  case class Disconnect()
}

class DiameterPeer(config: DiameterPeerConfig) extends Actor with ActorLogging {
  
  import DiameterPeer._
  import context.dispatcher
  
  implicit val materializer = ActorMaterializer()
  val handlerSource = Source.queue[ByteString](1000, akka.stream.OverflowStrategy.dropTail)
  val handlerSink = Flow[ByteString]
    // Framing.lengthField does not seem to work as expected if the computeFrameSize is not used
    // may be that is because the frame length excludes the offset
    .via(Framing.lengthField(3, 1, 10000, java.nio.ByteOrder.BIG_ENDIAN, (offsetBytes, calcFrameSize) => {calcFrameSize}))
    .viaMat(KillSwitches.single)(Keep.right)
    .map(frame => {
      // TODO: what happens in case of framing error?
      // Decode message
      try{
        val decodedMessage = diameterServer.coding.DiameterMessage(frame)
        // If Base, handle locally
        if(decodedMessage.applicationId == 0) handleDiameterBase(decodedMessage)
        else context.parent ! decodedMessage
      } catch {
        case e: Exception => e.printStackTrace()
      }
    })
    .to(Sink.onComplete((f) => {
        log.info("Closed connection with remote peer")
        inputQueue = None
      })
    )
  val handler = Flow.fromSinkAndSourceMat(handlerSink, handlerSource)(Keep.both)
  
  var killSwitch : Option[KillSwitch] = None
  var inputQueue : Option[SourceQueueWithComplete[ByteString]] = None
  var remoteAddress : Option[String] = None
  
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
      
    case Disconnect() => 
      log.info("Disconnecting peer")
      killSwitch.map(_.shutdown())
      killSwitch = None
      inputQueue = None
      remoteAddress = None
  }
  
  def handleDiameterBase(message: DiameterMessage) = {
    
    message.command match {
      case "Capabilities-Exchange" => handleCER(message)
    }
  }
  
  def handleCER(message: DiameterMessage) = {
    val reply = DiameterMessage.reply(message)
    
    // Check host name
    DiameterConfigManager.getDiameterPeerConfig.get(message >> "Origin-Host") match {
      case Some(DiameterPeerConfig(_, ipAddr, _, _, _)) => 
        if(ipAddr == remoteAddress.getOrElse("<none>")) sendSuccess 
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
      self ! Disconnect()
    }
    
    def sendSuccess = {
      // Add basic parameters
      val diameterConfig = DiameterConfigManager.getDiameterConfig
      val bindAddress = diameterConfig.bindAddress
      
      reply << ("Origin-Host" -> diameterConfig.diameterHost)
      reply << ("Origin-Realm" -> diameterConfig.diameterRealm)    
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
      
      self ! reply
      log.info("Sent CEA")
    }
    
  }
}