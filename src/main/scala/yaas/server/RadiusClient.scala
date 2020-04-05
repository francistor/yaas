package yaas.server

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}
import com.typesafe.config.{Config, ConfigFactory}
import yaas.coding.RadiusPacket
import yaas.instrumentation.MetricsOps
import yaas.server.RadiusActorMessages._
import yaas.util._

import scala.collection.mutable
import scala.collection.mutable.Map
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

// This Actor handles the communication with upstream radius servers
object RadiusClient {
  def props(bindIPAddress: String, basePort: Int, numPorts: Int, metricsServer: ActorRef): Props = Props(new RadiusClient(bindIPAddress, basePort, numPorts, metricsServer))
 
  // Messages
  // To be sent periodically to generate timeouts and delete the corresponding outstanding request entries
  case object Clean
}

class RadiusClient(bindIPAddress: String, basePort: Int, numPorts: Int, metricsServer: ActorRef) extends Actor with ActorLogging {

  case class RadiusRequestRef(originActor: ActorRef, authenticator: Array[Byte], radiusId: Long, endPoint: RadiusEndpoint, secret: String, requestCode: Int, requestTimestamp: Long)
  case class RadiusPortId(port: Int, id: Int)
  
  import RadiusClient._
  
  private val config: Config = ConfigFactory.load().getConfig("aaa.radius")

  // TODO: Each request should have its own timeout
  private val cleanMapIntervalMillis = config.getInt("clientMapIntervalMillis")
  private val clientMapTimeoutMillis = config.getInt("clientMapTimeoutMillis")
  private var cleanTimer: Option[Cancellable] = None
  
  private implicit val executionContext: ExecutionContextExecutor = context.system.dispatcher
    
  // For each radius destination, the map of identifiers (port + id) to requests
  private val requestMap = mutable.Map[RadiusEndpoint, mutable.Map[RadiusPortId, RadiusRequestRef]]().withDefaultValue(mutable.Map())
  
  // Stores the last identifier used for the key RadiusDestination
  private val lastRadiusPortIds = mutable.Map[RadiusEndpoint, RadiusPortId]().withDefaultValue(RadiusPortId(basePort, 0))
  
  // For each radius destination, store the stats
  private var endpointSuccesses = mutable.Map[RadiusEndpoint, Int]().withDefaultValue(0)
  private var endpointErrors = mutable.Map[RadiusEndpoint, Int]().withDefaultValue(0)
  
  // Span actors. One for each socket
  private val socketActors = for(i <- basePort to basePort + numPorts) yield context.actorOf(RadiusClientSocket.props(bindIPAddress, i), "RadiusClientSocket-"+ i)
  
  def receive: Receive = {
    
    case RadiusClientRequest(requestPacket, endpoint, secret, originActor, radiusId) =>
      // Get port and id
      val radiusPortId = nextRadiusPortId(endpoint)
      
      // Get packet to send
      val bytes = requestPacket.getRequestBytes(secret, radiusPortId.id)
      
      // Populate request Map
      pushToRequestMap(endpoint, radiusPortId, RadiusRequestRef(originActor, requestPacket.authenticator, radiusId, endpoint, secret, requestPacket.code, System.currentTimeMillis))
      log.debug(s"Pushed entry to request Map: $endpoint - $radiusPortId")
      
      // Send message to socket Actor
      socketActors(radiusPortId.port - basePort) ! RadiusClientSocketRequest(bytes, endpoint)
      
      // Add stats
      MetricsOps.pushRadiusClientRequest(metricsServer, endpoint, requestPacket.code)
      
      if(log.isDebugEnabled) log.debug(s">> Sending radius client request $requestPacket")
      
    case RadiusClientSocketResponse(bytes, endpoint, clientPort) =>
      val identifier = UByteString.getUnsignedByte(bytes.slice(1, 2))
      // Look in request Map
      val radiusPortId = RadiusPortId(clientPort, identifier)
      log.debug(s"Looking for entry in request Map: $endpoint - $radiusPortId")
      
      requestMap(endpoint).remove(radiusPortId) match {
        case Some(RadiusRequestRef(originActor, reqAuthenticator, radiusId, ep, secret, reqCode, requestTimestamp)) =>
          val responsePacket = RadiusPacket(bytes, Some(reqAuthenticator), secret)
          
          // Check authenticator
          val code = responsePacket.code
          if((code != RadiusPacket.ACCOUNTING_RESPONSE) && !RadiusPacket.checkAuthenticator(bytes, reqAuthenticator, secret)){
            log.warning("Bad authenticator from {}. Request-Authenticator: {}. Response-Authenticator: {}", 
                ep, reqAuthenticator.map(b => "%02X".format(UByteString.fromUnsignedByte(b))).mkString(","), bytes.slice(4, 20).toArray.map(b => "%02X".format(UByteString.fromUnsignedByte(b))).mkString(","))
            MetricsOps.pushRadiusClientDrop(metricsServer, ep.ipAddress, ep.port)
          }
          else {
            originActor ! RadiusClientResponse(responsePacket, radiusId)
            MetricsOps.pushRadiusClientResponse(metricsServer, ep, reqCode, responsePacket.code, requestTimestamp)
          }
          
          // Update stats
          endpointSuccesses(ep) = endpointSuccesses(ep) + 1
          
          if(log.isDebugEnabled) log.debug(s"<< Received radius client response $responsePacket")
          
        case None =>
          log.warning(s"Radius request not found for response received from endpoint $endpoint to $radiusPortId")
          MetricsOps.pushRadiusClientDrop(metricsServer, endpoint.ipAddress, endpoint.port)
      }
      
    case Clean =>
      val thresholdTimestamp = System.currentTimeMillis - clientMapTimeoutMillis
      
      // Gather the requests that that are older than the timeout, and remove them
      for {
        (endpoint, endpointRequestMap) <- requestMap
        (radiusPortId, requestRef) <- endpointRequestMap
      } if(requestRef.requestTimestamp < thresholdTimestamp) {
        endpointErrors(endpoint) = endpointErrors(endpoint) + 1
        requestMap(endpoint).remove(radiusPortId).foreach(reqRef => MetricsOps.pushRadiusClientTimeout(metricsServer, reqRef.endPoint, reqRef.requestCode))
      }
      
      // Build immutable map to send as message
      val stats = for {
        endpoint <- (endpointSuccesses.keys.toSet ++ endpointErrors.keys.toSet).toList
      } yield (endpoint, (endpointSuccesses(endpoint), endpointErrors(endpoint)))
      
      // Report to the router the stats for the last interval
      if(stats.nonEmpty) context.parent ! Router.RadiusClientStats(stats.toMap)
      
      // Clear
      endpointSuccesses = mutable.Map().withDefaultValue(0)
      endpointErrors = mutable.Map().withDefaultValue(0)
      cleanTimer = Some(context.system.scheduler.scheduleOnce(cleanMapIntervalMillis.milliseconds, self, Clean))

      // Send to the metrics servers a map of endpoint to size of the queue
      MetricsOps.updateRadiusClientRequestQueueGauges(
          metricsServer, 
          (for{(endpoint, reqMap) <- requestMap} yield (endpoint, reqMap.size)).toMap
      )
      
    case _ =>
  }
  
  override def preStart: Unit = {
    cleanTimer = Some(context.system.scheduler.scheduleOnce(cleanMapIntervalMillis.milliseconds, self, Clean))
  }
  
  override def postStop: Unit = {
    cleanTimer.map(_.cancel)
  }
  
  /*
   * Add entry in request map
   */
  private def pushToRequestMap(destination: RadiusEndpoint, radiusPortId: RadiusPortId, reqRef: RadiusRequestRef): Unit = {
    requestMap.get(destination) match {
      case Some(endpointRequestMap) =>
        endpointRequestMap.put(radiusPortId, reqRef)
      case None =>
        requestMap.put(destination, mutable.Map[RadiusPortId, RadiusRequestRef]((radiusPortId, reqRef)))
    }
  }
  
  /*
   * Gets the portId to use for the specified destination
   * Increments port first, in order to force using different origin ports and thus getting a usually better load balancing
   */
  private def nextRadiusPortId(destination: RadiusEndpoint): RadiusPortId = {
    // Get item (with default value)
    val radiusPortId = lastRadiusPortIds(destination)
    
    val _port = radiusPortId.port + 1
    val _id = if(_port == basePort + numPorts) radiusPortId.id + 1 else radiusPortId.id // Increment if port has carryover
    val _radiusPortId = RadiusPortId (
        if(_port == basePort + numPorts) basePort else _port,
        if(_id == 256) 0 else _id
        )
    
    lastRadiusPortIds.put(destination, _radiusPortId)
    
    radiusPortId
  }
}