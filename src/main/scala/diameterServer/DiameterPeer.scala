package diameterServer

import akka.actor.{ ActorSystem, Actor, ActorRef, Props }
import akka.event.{ Logging, LoggingReceive }

import diameterServer.DiameterRouter.RoutedDiameterMessage
import diameterServer.coding.{DiameterMessage}
import diameterServer.config.{DiameterPeerConfig}
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString

// Peer tables are made of these items
case class DiameterPeerPointer(config: DiameterPeerConfig, actorRef: ActorRef)

// Best practise
object DiameterPeer {
  def props(config: DiameterPeerConfig) = Props(new DiameterPeer(config))
  case class SendReply(diameterMessage: DiameterMessage)
}

class DiameterPeer(config: DiameterPeerConfig) extends Actor {
  
  // Initialize logger
  val logger = Logging.getLogger(context.system, this)
  
  implicit val materializer = ActorMaterializer()
  val handlerSource = Source.queue[ByteString](1000, akka.stream.OverflowStrategy.dropTail)
  val handlerSink = Flow[ByteString]
    // Framing.lengthField does not seem to work as expected if the computeFrameSize is not used
    // may be that is because the frame length excludes the offset
    .via(Framing.lengthField(3, 1, 10000, java.nio.ByteOrder.BIG_ENDIAN, (offsetBytes, calcFrameSize) => {calcFrameSize}))
    .viaMat(KillSwitches.single)(Keep.right)
    .map(frame => {
      // TODO: what happens in case of framing error?
      context.parent ! diameterServer.coding.DiameterMessage(frame)
      // println(diameterServer.coding.DiameterMessage(frame).toString)
    })
    .to(Sink.ignore)
  val handler = Flow.fromSinkAndSourceMat(handlerSink, handlerSource)(Keep.both)
  
  var killSwitch : Option[KillSwitch] = None
  var inputQueue : Option[SourceQueueWithComplete[ByteString]] = None
  
  def receive = {
    case connection: Tcp.IncomingConnection => 
      if(inputQueue.isDefined){
        logger.error("Peer is already connected")
        connection.handleWith(Flow.fromSinkAndSource(Sink.cancelled, Source.empty))
      } else {
        logger.info("Will handle connection with {}", connection.remoteAddress)
        connection.handleWith(handler) match {
          case (ks, q) => 
            killSwitch=Some(ks)
            inputQueue=Some(q)
        }
      }
    
    // Response
    case message: DiameterMessage =>
      if(inputQueue.isDefined) inputQueue.get.offer(message.getBytes)
      else logger.warning("Discarding message to unconnected peer")
  }
  
  def handleRecvMessage(recvMessage: ByteString) : Unit = {
    
    val diameterMessage = DiameterMessage(recvMessage)
    logger.debug("Received Message {}", diameterMessage)
    
    context.parent ! diameterMessage
  }
}