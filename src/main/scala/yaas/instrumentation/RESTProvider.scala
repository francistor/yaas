package yaas.instrumentation

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill }
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model._
import akka.http.scaladsl._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import com.typesafe.config._
import scala.util.{Success, Failure}
import scala.concurrent.Future
import scala.concurrent.duration._
import yaas.server.Router._
import yaas.server.DiameterPeerPointer
import yaas.instrumentation.MetricsServer._

import de.heikoseeberger.akkahttpjson4s.Json4sSupport

object RESTProvider {
  def props(statsServer: ActorRef) = Props(new RESTProvider(statsServer))
}

trait JsonSupport extends Json4sSupport {
  implicit val serialization = org.json4s.jackson.Serialization
  implicit val json4sFormats = org.json4s.DefaultFormats
}

class RESTProvider(metricsServer: ActorRef) extends Actor with ActorLogging {
  
  import RESTProvider._
  
  implicit val actorSystem = context.system
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher
  
  val config = ConfigFactory.load().getConfig("aaa.instrumentation")
  val bindPort = config.getInt("bindPort")
  val bindAddress= config.getString("bindAddress")
  implicit val askTimeout : akka.util.Timeout = config.getInt("timeoutMillis") millis
  
  
  def receive = {
    case _ =>
  }
  
  def diameterKeys(statName: String) = {
    statName match {
      case "diameterRequestReceived" => List("peer", "oh", "or", "dh", "dr", "ap", "cm")
      case "diameterAnswerReceived" => List("peer", "oh", "or", "dh", "dr", "ap", "cm", "rc", "rt")
      case "diameterRequestTimeout" => List("peer", "oh", "or", "dh", "dr", "ap", "cm")
      case "diameterAnswerSent" => List("peer", "oh", "or", "dh", "dr", "ap", "cm", "rc")
      case "diameterRequestSent" => List("peer", "oh", "or", "dh", "dr", "ap", "cm")
      
      case "diameterRequestDropped" => List("oh", "or", "dh", "dr", "ap", "cm")
      
      case "diameterHandlerServer" => List("oh", "or", "dh", "dr", "ap", "cm", "rc", "rt")
      case "diameterHandlerClient" => List("oh", "or", "dh", "dr", "ap", "cm", "rc", "rt")
      case "diameterHandlerClientTimeout" => List("oh", "or", "dh", "dr", "ap", "cm")
      case _ => List()
    }
  }
  
  def radiusKeys(statName: String) = {
    statName match {
      case "radiusServerRequest" => List("rh", "rq")
      case "radiusServerDropped" => List("rh")
      case "radiusServerResponse" => List("rh", "rs")
      
      case "radiusClientRequest" => List("rh", "rq")
      case "radiusClientResponse" => List("rh", "rq", "rs", "rt")
      case "radiusClientTimeout" => List("rh", "rq")
      case "radiusClientDropped" => List("rh")
      
      case "radiusHandlerResponse" => List("rh", "rq", "rs", "rt")
      case "radiusHandlerDropped" => List("rh", "rq")
      case "radiusHandlerRequest" => List("rh", "rq", "rs", "rt")
      case "radiusHandlerRetransmission" => List("group", "rq")
      case "radiusHandlerTimeout" => List("group", "rq")
      case _ => List()
    }
  }
  
  val configRoute =
    pathPrefix("config"){
      pathPrefix("reload"){
        patch {
            parameterMap { params =>
              val parameter = params.getOrElse("fileName", "all")
              log.info(s"Reload ${parameter}")
              
              complete((context.parent ? yaas.server.Router.IXReloadConfig(params.get("fileName"))).mapTo[String])
            }
        }
      } ~
      pathPrefix("setLogLevel"){
        patch {
          parameterMap { params =>
            val loggerOption = params.get("loggerName")
            val levelOption = params.get("level")
            if(loggerOption.isEmpty || levelOption.isEmpty || (! List("TRACE", "DEBUG", "INFO", "WARN", "ERROR").contains(levelOption.get.toUpperCase))){
              complete(404, s"Invalid loggerName or level parameters: $params")
            } else {            
             
              import org.slf4j.LoggerFactory
              import ch.qos.logback.classic.Level
              import ch.qos.logback.classic.Logger
              
              log.info(s"Setting log level for ${loggerOption.get} to ${levelOption.get}")
              val logger = LoggerFactory.getLogger(loggerOption.get).asInstanceOf[ch.qos.logback.classic.Logger]
              logger.setLevel(Level.toLevel(levelOption.get))
              complete(200, "OK")
            }
          }
        }
      }
  }
  
  val prometheusRoute = 
    pathPrefix("metrics"){
      // Prometheus
      val futList = List(
        getPrometheusDiameterMetricFut("diameterRequestReceived"),
        getPrometheusDiameterMetricFut("diameterAnswerReceived"),
        getPrometheusDiameterMetricFut("diameterRequestTimeout"),
        getPrometheusDiameterMetricFut("diameterAnswerSent"),
        getPrometheusDiameterMetricFut("diameterRequestDropped"),
        getPrometheusDiameterMetricFut("diameterHandlerServer"),
        getPrometheusDiameterMetricFut("diameterHandlerClient"),
        getPrometheusDiameterMetricFut("diameterHandlerClientTimeout"),
        
        getPrometheusRadiusStatsFut("radiusServerRequest"),
        getPrometheusRadiusStatsFut("radiusServerDropped"),
        getPrometheusRadiusStatsFut("radiusServerResponse"),
        getPrometheusRadiusStatsFut("radiusClientRequest"),
        getPrometheusRadiusStatsFut("radiusClientResponse"),
        getPrometheusRadiusStatsFut("radiusClientTimeout"),
        getPrometheusRadiusStatsFut("radiusClientDropped"),
        getPrometheusRadiusStatsFut("radiusHandlerResponse"),
        getPrometheusRadiusStatsFut("radiusHandlerDropped"),
        getPrometheusRadiusStatsFut("radiusHandlerRequest"),
        getPrometheusRadiusStatsFut("radiusHandlerRetransmission"),
        getPrometheusRadiusStatsFut("radiusHandlerTimeout")
      )
      complete(Future.reduce(futList)((a, b) => a + b))
    }
  
  class RestRouteProvider extends JsonSupport {
    
    def restRoute = 
    patch {
      pathPrefix("diameter"){
        pathPrefix("metrics" / "reset"){
          metricsServer ! MetricsServer.DiameterMetricsReset
          log.info("Diameters Metrics Reset")
          complete(200, "OK")
        }
      } ~
      pathPrefix("radius"){
        pathPrefix("metrics" / "reset"){
          metricsServer ! MetricsServer.RadiusMetricsReset
          log.info("Radius Metrics Reset")
          complete(200, "OK")
        }
      }
    } ~
    get {
      pathPrefix("diameter"){
        pathPrefix("peers"){
          complete((context.parent ? IXGetPeerStatus).mapTo[Map[String, MetricsOps.DiameterPeerStatus]])
        } ~
        pathPrefix("requestQueues"){
          complete((metricsServer ? GetDiameterPeerRequestQueueGauges).mapTo[Map[String, Int]])
        } ~
        pathPrefix("metrics"){
          path(Remaining){ statName =>
            parameterMap { params =>
              
              // To validate the input and generate the full list if none is specified
              val allKeys = diameterKeys(statName)
              val inputKeys = params.get("agg").map(_.split(",").toList).getOrElse(allKeys)
              val invalidKeys = inputKeys.filter(!allKeys.contains(_))
              if(invalidKeys.length == 0) complete((metricsServer ? GetDiameterMetrics(statName, inputKeys)).mapTo[List[DiameterMetricsItem]])  
              else complete(StatusCodes.NotAcceptable, invalidKeys.mkString(","))
            }
          }
        }
      } ~ 
      pathPrefix("radius") {
        pathPrefix("requestQueues"){
          complete((metricsServer ? GetRadiusServerRequestQueueGauges).mapTo[Map[String, Int]])
        } ~
        pathPrefix("metrics") {
          path(Remaining) { statName =>
            parameterMap { params =>
              // To validate the input and generate the full list if none is specified
              val allKeys = radiusKeys(statName)
              val inputKeys = params.get("agg").map(_.split(",").toList).getOrElse(allKeys)
              val invalidKeys = inputKeys.filter(!allKeys.contains(_))
              if(invalidKeys.length == 0) complete((metricsServer ? GetRadiusMetrics(statName, inputKeys)).mapTo[List[RadiusMetricsItem]])  
              else complete(StatusCodes.NotAcceptable, invalidKeys.mkString(","))
            }
          }
        }
      }
    }
  }

  if(bindAddress.contains(".")){
    val bindFuture = Http().bindAndHandle(new RestRouteProvider().restRoute ~ prometheusRoute ~ configRoute, bindAddress, bindPort)
    
    bindFuture.onComplete {
      case Success(binding) =>
        log.info("Instrumentation REST server bound to {}", binding.localAddress)
      case Failure(e) =>
        log.error(e.getMessage)
    }
  }
  
  /**
   * Helper functions
   */
  def getPrometheusDiameterMetricFut(statName: String) = {
		  def toPrometheus(statName: String, stats: List[DiameterMetricsItem]): String = {

				  def genPrometheusString(counterName: String, helpString: String): String = {
						  s"\n# HELP $counterName $helpString\n# TYPE $counterName counter\n" +
								  stats.map(dsi => s"$counterName{${dsi.keyMap.map{case (k, v) => s"""$k="$v""""}.mkString(",")}} ${dsi.counter}").mkString("\n") +
								  "\n"
				  }

				  statName match {
				  case "diameterRequestReceived" => genPrometheusString("diameter_requests_received", "The number of Diameter requests received")
				  case "diameterAnswerReceived" => genPrometheusString("diameter_answers_received", "The number of Diameter answers received")
				  case "diameterRequestTimeout" => genPrometheusString("diameter_requests_timeout", "The number of Diameter timeouts")
				  case "diameterAnswerSent" => genPrometheusString("diameter_answers_sent", "The number of Diameter answers sent")
				  case "diameterRequestSent" => genPrometheusString("diameter_requests_sent", "The number of Diameter requests sent")
				  case "diameterRequestDropped" => genPrometheusString("diameter_requests_dropped", "The number of Diameter requests dropped")
				  case "diameterHandlerServer" => genPrometheusString("diameter_handler_server", "The number of Diameter requests handled")
				  case "diameterHandlerClient" => genPrometheusString("diameter_handler_client", "The number of Diameter requests sent by handlers")
				  case "diameterHandlerClientTimeout" => genPrometheusString("diameter_handler_client_timeout", "The number of Diameter requests sent by handler timed out")

				  case _ => ""

				  }
		  }
		  (metricsServer ? GetDiameterMetrics(statName, diameterKeys(statName))).mapTo[List[DiameterMetricsItem]].map(f => toPrometheus(statName, f))
  }

  def getPrometheusRadiusStatsFut(statName: String) = {
		  def toPrometheus(statName: String, stats: List[RadiusMetricsItem]): String = {

				  def genPrometheusString(counterName: String, helpString: String): String = {
						  s"\n# HELP $counterName $helpString\n# TYPE $counterName counter\n" +
								  stats.map(dsi => s"$counterName{${dsi.keyMap.map{case (k, v) => s"""$k="$v""""}.mkString(",")}} ${dsi.counter}").mkString("\n") +
								  "\n"
				  }

				  statName match {
				  case "radiusServerRequest" => genPrometheusString("radius_server_requests", "The number of Radius server requests received")
				  case "radiusServerDropped" => genPrometheusString("radius_server_dropped", "The number of Radius server requests dropped")
				  case "radiusServerResponse" => genPrometheusString("radius_server_responses", "The number of Radius server requests responsed")
				  case "radiusClientRequest" => genPrometheusString("radius_client_requests", "The number of Radius client requests sent")
				  case "radiusClientResponse" => genPrometheusString("radius_client_responses", "The number of Radius client responses received")
				  case "radiusClientTimeout" => genPrometheusString("radius_client_timeout", "The number of Radius client requests timed out")
				  case "radiusClientDropped" => genPrometheusString("radius_client_dropped", "The number of Radius client responses dropped")
				  case "radiusHandlerResponse" => genPrometheusString("radius_handler_responses", "The number of Radius responses sent by handlers")
				  case "radiusHandlerDropped" => genPrometheusString("radius_handler_dropped", "The number of Radius requests dropped by handlers")
				  case "radiusHandlerRequest" => genPrometheusString("radius_handler_requests", "The number of Radius requests received by handlers")
				  case "radiusHandlerRetransmission" => genPrometheusString("radius_handler_retransmission", "The number of Radius requests retransmitted")
				  case "radiusHandlerTimeout" => genPrometheusString("radius_handler_timeout", "The number of Radius requests timed out")

				  case _ => ""

				  }
		  }

		  (metricsServer ? GetRadiusMetrics(statName, radiusKeys(statName))).mapTo[List[RadiusMetricsItem]].map(f => toPrometheus(statName, f))
  }
    
}