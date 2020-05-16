package yaas.server

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, actorRef2Scala}
import akka.event.LoggingReceive
import akka.stream.ActorMaterializer
import javax.script.ScriptEngineManager
import yaas.coding.{DiameterMessage, RadiusPacket}
import yaas.config.ConfigManager
import yaas.instrumentation.MetricsOps
import yaas.server.RadiusActorMessages._
import yaas.server.Router._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future, Promise}
import scala.io.Source
import scala.util.{Failure, Success}

// Diameter Exceptions
class DiameterResponseException(msg: String) extends Exception(msg)
class NoPeerAvailableResponseException(msg: String) extends DiameterResponseException(msg)
class DiameterTimeoutException(msg: String) extends DiameterResponseException(msg)

// Radius Exceptions
class RadiusResponseException(msg:String) extends Exception(msg)
class RadiusTimeoutException(msg: String) extends RadiusResponseException(msg)

// Context classes. Just to avoid passing too many opaque parameters from request to response
case class DiameterRequestContext(diameterRequest: DiameterMessage, originActor: ActorRef, requestTimestamp: Long)
case class RadiusRequestContext(requestPacket: RadiusPacket, origin: RadiusEndpoint, secret: String, originActor: ActorRef, receivedTimestamp: Long)

object MessageHandler {
  // Messages
  case class DiameterRequestTimeout(e2eId: Long) 
  case class RadiusRequestTimeout(radiusId: Long)
  case class DiameterRequestInternal(promise: Promise[DiameterMessage], requestMessage: DiameterMessage, timeoutMillis: Int)
  case class RadiusGroupRequestInternal(promise: Promise[RadiusPacket], serverGroupName: String, requestPacket: RadiusPacket, baseRadiusId: Long, timeoutMillis: Int, retries: Int, retryNum: Int)
}

/**
 * Base class for all handlers, including also methods for sending messages.
 *
 */
class MessageHandler(statsServer: ActorRef, configObject: Option[String]) extends Actor with ActorLogging {
  
  import MessageHandler._
  
  implicit val executionContext: ExecutionContextExecutor = context.system.dispatcher
  
  ////////////////////////////////
  // Diameter 
  ////////////////////////////////
  
  /**
   * Sends a Diameter Answer.
   *
   * To be used in handler classes.
   */
  def sendDiameterAnswer(answerMessage: DiameterMessage) (implicit ctx: DiameterRequestContext): Unit = {
    ctx.originActor ! answerMessage
    
    MetricsOps.pushDiameterHandlerServer(statsServer, ctx.diameterRequest, answerMessage, ctx.requestTimestamp)
    log.debug(">> Diameter answer sent\n {}\n", answerMessage.toString())
  }
  
  /**
   * Sends a Diameter Request.
   * 
   * To be used externally. Will use internal Actor message to avoid thread synchronization issues. It may be used from
   * any thread
   */
  def sendDiameterRequest(requestMessage: DiameterMessage, timeoutMillis: Int): Future[DiameterMessage] = {
    val promise = Promise[DiameterMessage]
    val requestTimestamp = System.currentTimeMillis
    
    // Use message to avoid threading issues
    self ! DiameterRequestInternal(promise, requestMessage, timeoutMillis)
    
    // Side-effect action when future is resolved
    promise.future.andThen {
      case Success(ans) =>
        MetricsOps.pushDiameterHandlerClient(statsServer, requestMessage.key, ans, requestTimestamp)
      case Failure(ex) =>
        log.debug("Diameter timeout: {}", ex.getMessage)
        MetricsOps.pushDiameterHandlerClientTimeout(statsServer, requestMessage.key)
    }
  }
  
  // For internal use
  private def sendDiameterRequestInternal(promise: Promise[DiameterMessage], requestMessage: DiameterMessage, timeoutMillis: Int): Unit = {
    // Publish in request map
    diameterRequestMapIn(requestMessage.endToEndId, timeoutMillis, promise)
    
    // Send request using router
    context.parent ! requestMessage
    log.debug(">> Diameter request sent\n {}\n", requestMessage.toString())
  }
  
  // To be overridden in Handler Classes
  def handleDiameterMessage(ctx: DiameterRequestContext): Unit = {
    log.warning("Default Diameter handleMessage does nothing")
  }
  
  ////////////////////////////////
  // Diameter Request Map
  ////////////////////////////////
  private case class DiameterRequestMapEntry(promise: Promise[DiameterMessage], timer: Cancellable)
  private val diameterRequestMap = scala.collection.mutable.Map[Long, DiameterRequestMapEntry]()
  
  private def diameterRequestMapIn(e2eId: Long, timeoutMillis: Int, promise: Promise[DiameterMessage]): Unit = {
    // Schedule timer
    val timer = context.system.scheduler.scheduleOnce(timeoutMillis.milliseconds, self, DiameterRequestTimeout(e2eId))
 
    // Add to map
    diameterRequestMap.put(e2eId, DiameterRequestMapEntry(promise, timer))
    
    log.debug("Diameter RequestMap In -> {}", e2eId)
  }
  
  private def diameterRequestMapOut(e2eId: Long, messageOrError: Either[DiameterMessage, Exception]): Unit = {
    // Remove Map entry
    diameterRequestMap.remove(e2eId) match {
      case Some(requestEntry) =>
        // Cancel timer
        requestEntry.timer.cancel()
        messageOrError match {
          // If response received, fulfill promise with success
          case Left(diameterAnswer) =>
            log.debug("Diameter Request Map Out <- {}", e2eId)
            requestEntry.promise.success(diameterAnswer)
            
          // If error, fulfill promise with error
          case Right(e) =>
            log.debug("Diameter Timeout <- {}", e2eId)
            requestEntry.promise.failure(e)
        }
      
      case None =>
        log.warning("Diameter Request not found for E2EId: {}. Unsolicited or stalled answer", e2eId)
    }
  }
  
  ////////////////////////////////
  // Radius
  ////////////////////////////////
  /**
   * Sends a Radius Response packet
   */
  def sendRadiusResponse(responsePacket: RadiusPacket)(implicit ctx: RadiusRequestContext): Unit = {
    ctx.originActor ! RadiusServerResponse(responsePacket, ctx.origin, ctx.secret)
    
    MetricsOps.pushRadiusHandlerResponse(statsServer, ctx.origin, ctx.requestPacket.code, responsePacket.code, ctx.receivedTimestamp)
    if(log.isDebugEnabled) log.debug(">> Radius response sent\n {}\n", responsePacket.toString())
  }
  
  /**
   * To be used by the handler to signal to the stats that the packet has been dropped
   */
  def dropRadiusPacket(implicit ctx: RadiusRequestContext): Unit = {
    MetricsOps.pushRadiusHandlerDrop(statsServer, ctx.origin, ctx.requestPacket.code)
    if(log.isDebugEnabled) log.debug(">> Dropping request \n {}\n", ctx.requestPacket.toString())
  }
  
  /**
   * Sends a Radius Request to the specified group.
   * 
   * To be used externally. Will use internal Actor message to avoid thread synchronization issues. It may be used from
   * any thread because the requestMap manipulation takes place in the Actor Thread
   */
  def sendRadiusGroupRequest(serverGroupName: String, requestPacket: RadiusPacket, timeoutMillis: Int, retries: Int, retryNum: Int = 0, prevRadiusId: Option[Long] = None): Future[RadiusPacket] = {
    val promise = Promise[RadiusPacket]
    val sentTimestamp = System.currentTimeMillis
    
    // The baseRadiusId will be stable across retransmissions
    // See comment in IDGenerator
    val baseRadiusId = prevRadiusId match {
      case Some(rId) => rId
      case None => yaas.util.IDGenerator.nextRadiusId
    }
    
    self ! RadiusGroupRequestInternal(promise, serverGroupName, requestPacket, baseRadiusId, timeoutMillis, retries, retryNum)
    
    promise.future.recoverWith {
      case _ if retries > 0 =>
        MetricsOps.pushRadiusHandlerRetransmission(statsServer, serverGroupName, requestPacket.code)
        sendRadiusGroupRequest(serverGroupName, requestPacket, timeoutMillis, retries - 1, retryNum + 1, Some(baseRadiusId))
    }.andThen {
      case Failure(_) =>
        MetricsOps.pushRadiusHandlerTimeout(statsServer, serverGroupName, requestPacket.code)
      case Success(responsePacket) =>
        MetricsOps.pushRadiusHandlerRequest(statsServer, serverGroupName, requestPacket.code, responsePacket.code, sentTimestamp) 
    }
  }
  
  // To be used internally
  private def sendRadiusGroupRequestInternal(promise: Promise[RadiusPacket], serverGroupName: String, requestPacket: RadiusPacket, baseRadiusId: Long, timeoutMillis: Int, retries: Int, retryNum: Int): Unit = {
    
    val radiusId = baseRadiusId + retryNum
    
    // Publish in request Map
    radiusRequestMapIn(radiusId, timeoutMillis, promise)
    
    // Send request using router
    context.parent ! RadiusGroupClientRequest(requestPacket, serverGroupName, radiusId, retryNum)
    
    log.debug(">> Radius request sent\n {}\n", requestPacket.toString())
  }
  
  // To be overridden in Handler Classes
  def handleRadiusMessage(radiusRequestContext: RadiusRequestContext): Unit = {
    log.warning("Default radius handleMessage does nothing")
  }
  
  ////////////////////////////////
  // Radius Request Map
  ////////////////////////////////
  case class RadiusRequestMapEntry(promise: Promise[RadiusPacket], timer: Cancellable, sentTimestamp: Long)
  private val radiusRequestMap = scala.collection.mutable.Map[Long, RadiusRequestMapEntry]()
  
  private def radiusRequestMapIn(radiusId: Long, timeoutMillis: Int, promise: Promise[RadiusPacket]): Unit = {
    // Schedule timer
    val timer = context.system.scheduler.scheduleOnce(timeoutMillis.milliseconds, self, MessageHandler.RadiusRequestTimeout(radiusId))
    
    // Add to map
    radiusRequestMap.put(radiusId, RadiusRequestMapEntry(promise, timer, System.currentTimeMillis))
    
    log.debug("Radius Request Map In -> {}", radiusId)
  }
  
  private def radiusRequestMapOut(radiusId: Long, radiusPacketOrError: Either[RadiusPacket, Exception]): Unit  = {
    radiusRequestMap.remove(radiusId) match {
      case Some(requestEntry) =>
        requestEntry.timer.cancel()
        radiusPacketOrError match {
          case Left(radiusPacket) =>
            log.debug("Radius Request Map Out <- {}", radiusId)
            requestEntry.promise.success(radiusPacket)
            
          case Right(e) =>
            log.debug("Radius Request Map Timeout <- {}", radiusId)
            requestEntry.promise.failure(e)
        }
        
      case None =>
        log.warning("Radius Request (Not Found) {}. Unsolicited or stalled response", radiusId)
    }
  }
  
  ////////////////////////////////
  // Actor receive method
  ////////////////////////////////
  def receive: Receive = LoggingReceive {
    
    // Diameter
    case DiameterRequestTimeout(e2eId) =>
      diameterRequestMapOut(e2eId, Right(new DiameterTimeoutException("Timeout")))
    
    case RoutedDiameterMessage(diameterRequest, originActor) =>
      log.debug("<< Diameter request received\n {}\n", diameterRequest.toString())
      handleDiameterMessage(DiameterRequestContext(diameterRequest, originActor, System.currentTimeMillis))
      
    case diameterAnswer: DiameterMessage =>
      log.debug("<< Diameter answer received\n {}\n", diameterAnswer.toString())
      diameterRequestMapOut(diameterAnswer.endToEndId, Left(diameterAnswer))
      
    case DiameterRequestInternal(promise, requestMessage, timeoutMillis) =>
      sendDiameterRequestInternal(promise, requestMessage, timeoutMillis)
      
    // Radius
    case RadiusRequestTimeout(radiusId) =>
      log.debug("<< Radius timeout\n {}\n", radiusId)
      radiusRequestMapOut(radiusId, Right(new RadiusTimeoutException("Timeout")))
        
    case RadiusServerRequest(requestPacket, originActor, origin, secret) =>
      log.debug("<< Radius request received\n {}\n", requestPacket.toString())
      handleRadiusMessage(RadiusRequestContext(requestPacket, origin, secret, originActor, System.currentTimeMillis))
      
    case RadiusClientResponse(radiusResponse: RadiusPacket, radiusId: Long) =>
      log.debug("<< Radius response received\n {}\n", radiusResponse.toString())
      radiusRequestMapOut(radiusId, Left(radiusResponse))
      
    case RadiusGroupRequestInternal(promise, serverGroupName, requestPacket, baseRadiusId, timeoutMillis, retries, retryNum) =>
      sendRadiusGroupRequestInternal(promise, serverGroupName, requestPacket, baseRadiusId, timeoutMillis, retries, retryNum)
	}
  
  
  ////////////////////////////////
  // Javascript integration
  ////////////////////////////////

  /**
   * Invokes the javascript whose location is passed as parameter, and may refer to a URL.
   *
   * @param scriptResource the name of the javascript resource to run
   */
  def runJS(scriptResource: String): Future[String] = {

    val promise = Promise[String]()

    // Need to pass a class to Javascript
    class Notifier {
      def success(message: String): Unit = promise.success(message)
      def failure(message: String): Unit = promise.failure(new Exception(message))
    }

    // Instantiate
    val engine = new ScriptEngineManager().getEngineByName("nashorn")

    // Put objects in scope
    // Radius/Diameter/HTTP helper
    engine.put("Yaas", YaasJS)

    // Base location of the script
    val scriptURL = ConfigManager.getConfigObjectURL(scriptResource).getPath
    engine.put("baseURL", scriptURL.substring(0, scriptURL.indexOf(scriptResource)))

    // To signal finalization
    // JScript will invoke Notifier.end
    engine.put("Notifier", new Notifier())

    // Publish command line
    engine.put("commandLine", ConfigManager.popCommandLine.toList)

    // Execute Javascript
    engine.eval(s"load(baseURL + '$scriptResource');")

    // Return the promise
    promise.future
  }

  object YaasJS {
    
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.unmarshalling.Unmarshal
    import org.json4s._
    import org.json4s.jackson.JsonMethods._
    import yaas.coding.DiameterConversions._
    import yaas.coding.RadiusConversions._

    implicit val actorSystem: ActorSystem = context.system

    private val http = Http(context.system)

    // Test helping functions must invoke a callback of the form
    // function(error, responseString)

    /**
     * Sends a Radius request
     * @param serverGroupName server group name
     * @param requestPacket stringified JSON with request packet
     * @param timeoutMillis timeout
     * @param retries retries
     * @param callback of the form function(error, responseString). responseString contains the stringified JSON
     */
    def radiusRequest(serverGroupName: String, requestPacket: String, timeoutMillis: Int, retries: Int, callback: jdk.nashorn.api.scripting.JSObject): Unit = {
        
      val responseFuture = sendRadiusGroupRequest(serverGroupName, parse(requestPacket), timeoutMillis, retries)
      responseFuture.onComplete{
        case Success(response) =>
          // Force conversion
          val jResponse: JValue = response
          callback.call(null, null, compact(render(jResponse)))
          
        case Failure(error) =>
          callback.call(null, error)
      }
    }

    /**
     * Sends a Diameter request
     * @param requestMessage stringified Diameter Request
     * @param timeoutMillis timeout
     * @param callback of the form function(error, responseString). responseString contains the stringified JSON
     */
    def diameterRequest(requestMessage: String, timeoutMillis: Int, callback: jdk.nashorn.api.scripting.JSObject): Unit = {
      val responseFuture = sendDiameterRequest(parse(requestMessage), timeoutMillis)
      responseFuture.onComplete{
        case Success(response) =>
          // Force conversion
          val jResponse: JValue = response
          callback.call(null, null, compact(render(jResponse)))
          
        case Failure(error) =>
          callback.call(null, error)
      }
    }

    /**
     * Invokes a URL that may return a JSON
     * @param url URL to invoke
     * @param method GET or POST
     * @param json stringified JSON to POST
     * @param callback of the form function(error, responseString). responseString contains the stringified JSON
     */
    def httpRequest(url: String, method: String, json: String, callback: jdk.nashorn.api.scripting.JSObject): Unit = {
      val responseFuture = http.singleRequest(HttpRequest(HttpMethods.getForKey(method).get, uri = url, entity = HttpEntity(ContentTypes.`application/json`, json)))
      (for {
        re <- responseFuture
        r <- Unmarshal(re.entity).to[String]
      } yield r ) onComplete {
        case Success(response) =>
          callback.call(null, null, response)
          
        case Failure(error) =>
          callback.call(null, error)
      }
    }

    /**
     * Reads the full contents of a file
     * @param fileName file name
     * @param callback of the form function(error, responseString). responseString contains the full file contents
     */
    def readFile(fileName: String, callback: jdk.nashorn.api.scripting.JSObject): Unit = {
      try {
        val source = Source.fromFile(fileName)
        callback.call(null, null, source.getLines.mkString)
        source.close
      } catch {
        case error: Exception =>
          callback.call(null, error)
      }
    }
  }
}