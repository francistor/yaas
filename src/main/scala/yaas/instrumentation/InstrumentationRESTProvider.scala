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

object InstrumentationRESTProvider {
  def props(statsServer: ActorRef) = Props(new InstrumentationRESTProvider(statsServer))
}

trait JsonSupport extends Json4sSupport {
  implicit val serialization = org.json4s.jackson.Serialization
  implicit val json4sFormats = org.json4s.DefaultFormats
}

class InstrumentationRESTProvider(metricsServer: ActorRef) extends Actor with ActorLogging {
  
  import InstrumentationRESTProvider._
  
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
      
      case "diameterPeerQueueSize" => List("peer")
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
      
      case "radiusClientQueueSize" => List("rh")
      
      case _ => List()
    }
  }
  
  def httpKeys(statName: String) = {
    statName match {
      case "httpOperation" => List("oh", "method", "path", "rc")
      case _ => List()
    }
  }
  
  def sessionKeys(statName: String) = {
    statName match {
      // Depends on this code in InstrumentationRestProvider: if(paramList.length == 0 || paramList.length > 4) metricsItems
      case "sessionGroups" => List("1", "2", "3", "4", "5")
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
        getPrometheusDiameterMetricFut("diameterPeerQueueSize"),
        
        getPrometheusRadiusMetricFut("radiusServerRequest"),
        getPrometheusRadiusMetricFut("radiusServerDropped"),
        getPrometheusRadiusMetricFut("radiusServerResponse"),
        getPrometheusRadiusMetricFut("radiusClientRequest"),
        getPrometheusRadiusMetricFut("radiusClientResponse"),
        getPrometheusRadiusMetricFut("radiusClientTimeout"),
        getPrometheusRadiusMetricFut("radiusClientDropped"),
        getPrometheusRadiusMetricFut("radiusHandlerResponse"),
        getPrometheusRadiusMetricFut("radiusHandlerDropped"),
        getPrometheusRadiusMetricFut("radiusHandlerRequest"),
        getPrometheusRadiusMetricFut("radiusHandlerRetransmission"),
        getPrometheusRadiusMetricFut("radiusHandlerTimeout"),
        getPrometheusRadiusMetricFut("radiusClientQueueSize"),
        
        getPrometheusHttpStatsFut("httpOperation")
      )
      complete(Future.reduce(futList)((a, b) => a + b))
    }
  
  class RestRouteProvider extends JsonSupport {
    
  def completeMetrics(metricsType: Metrics, statName: String, params: Map[String, String]) = {
    val allKeys = metricsType match {
      case DiameterMetrics => diameterKeys(statName)
      case RadiusMetrics => radiusKeys(statName)
      case HttpMetrics => httpKeys(statName)
      case SessionMetrics => sessionKeys(statName)
    }
    val inputKeys = params.get("agg").map(_.split(",").toList).getOrElse(allKeys)
    val invalidKeys = inputKeys.filter(!allKeys.contains(_))
    if(invalidKeys.length == 0) complete((metricsServer ? GetMetrics(metricsType, statName, inputKeys)).mapTo[List[MetricsItem]])  
    else complete(StatusCodes.NotAcceptable, "Invalid aggregation key : " + invalidKeys.mkString(","))
  }
    
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
      } ~
      pathPrefix("http"){
        pathPrefix("metrics" / "reset"){
          metricsServer ! MetricsServer.HttpMetricsReset
          log.info("HTTP Metrics Reset")
          complete(200, "OK")
        }
      }
    } ~
    get {
      pathPrefix("diameter"){
        pathPrefix("peers"){
          complete((context.parent ? IXGetPeerStatus).mapTo[Map[String, MetricsOps.DiameterPeerStatus]])
        } ~
        pathPrefix("metrics"){
          path(Remaining){ statName =>
            parameterMap { params => completeMetrics(DiameterMetrics, statName, params)
            }
          }
        }
      } ~ 
      pathPrefix("radius") {
        pathPrefix("metrics") {
          path(Remaining) { statName =>
            parameterMap { params =>
              completeMetrics(RadiusMetrics, statName, params)
            }
          }
        }
      } ~ 
      pathPrefix("http"){
        pathPrefix("metrics"){
          path(Remaining) { statName =>
            parameterMap { params =>
              completeMetrics(HttpMetrics, statName, params)
            }
          }
        }
      } ~
      pathPrefix("session"){
        pathPrefix("metrics"){
          path(Remaining) { statName =>
            parameterMap { params =>
              completeMetrics(SessionMetrics, statName, params)
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
  
  
  def genPrometheusString(stats: List[MetricsItem], metricName: String, helpString: String, isCounter: Boolean = true): String = {
      val theType = if(isCounter) "counter" else "gauge"
		  s"\n# HELP $metricName $helpString\n# TYPE $metricName $theType\n" +
				  stats.map(dsi => s"$metricName{${dsi.keyMap.map{case (k, v) => s"""$k="$v""""}.mkString(",")}} ${dsi.value}").mkString("\n") +
				  "\n"
  }
 				  
  def getPrometheusDiameterMetricFut(statName: String) = {
		  def toPrometheus(statName: String, stats: List[MetricsItem]): String = {

				  statName match {
  				  case "diameterRequestReceived" => genPrometheusString(stats, "diameter_requests_received", "The number of Diameter requests received")
  				  case "diameterAnswerReceived" => genPrometheusString(stats, "diameter_answers_received", "The number of Diameter answers received")
  				  case "diameterRequestTimeout" => genPrometheusString(stats, "diameter_requests_timeout", "The number of Diameter timeouts")
  				  case "diameterAnswerSent" => genPrometheusString(stats, "diameter_answers_sent", "The number of Diameter answers sent")
  				  case "diameterRequestSent" => genPrometheusString(stats, "diameter_requests_sent", "The number of Diameter requests sent")
  				  case "diameterRequestDropped" => genPrometheusString(stats, "diameter_requests_dropped", "The number of Diameter requests dropped")
  				  case "diameterHandlerServer" => genPrometheusString(stats, "diameter_handler_server", "The number of Diameter requests handled")
  				  case "diameterHandlerClient" => genPrometheusString(stats, "diameter_handler_client", "The number of Diameter requests sent by handlers")
  				  case "diameterHandlerClientTimeout" => genPrometheusString(stats, "diameter_handler_client_timeout", "The number of Diameter requests sent by handler timed out")
  				  case "diameterPeerQueueSize" => genPrometheusString(stats, "diameter_peer_queue_size", "The queue of requests to be processed", false)
  
  				  case _ => ""

				  }
		  }
		  (metricsServer ? GetMetrics(DiameterMetrics, statName, diameterKeys(statName))).mapTo[List[MetricsItem]].map(f => toPrometheus(statName, f))
  }
  

  def getPrometheusRadiusMetricFut(statName: String) = {
	  def toPrometheus(statName: String, stats: List[MetricsItem]): String = {

		  statName match {
			  case "radiusServerRequest" => genPrometheusString(stats, "radius_server_requests", "The number of Radius server requests received")
			  case "radiusServerDropped" => genPrometheusString(stats, "radius_server_dropped", "The number of Radius server requests dropped")
			  case "radiusServerResponse" => genPrometheusString(stats, "radius_server_responses", "The number of Radius server requests responsed")
			  case "radiusClientRequest" => genPrometheusString(stats, "radius_client_requests", "The number of Radius client requests sent")
			  case "radiusClientResponse" => genPrometheusString(stats, "radius_client_responses", "The number of Radius client responses received")
			  case "radiusClientTimeout" => genPrometheusString(stats, "radius_client_timeout", "The number of Radius client requests timed out")
			  case "radiusClientDropped" => genPrometheusString(stats, "radius_client_dropped", "The number of Radius client responses dropped")
			  case "radiusHandlerResponse" => genPrometheusString(stats, "radius_handler_responses", "The number of Radius responses sent by handlers")
			  case "radiusHandlerDropped" => genPrometheusString(stats, "radius_handler_dropped", "The number of Radius requests dropped by handlers")
			  case "radiusHandlerRequest" => genPrometheusString(stats, "radius_handler_requests", "The number of Radius requests received by handlers")
			  case "radiusHandlerRetransmission" => genPrometheusString(stats, "radius_handler_retransmission", "The number of Radius requests retransmitted")
			  case "radiusHandlerTimeout" => genPrometheusString(stats, "radius_handler_timeout", "The number of Radius requests timed out")
				case "radiusClientQueueSize" => genPrometheusString(stats, "radius_client_queue_size", "The queue of requests to be processed", false)

			  case _ => ""
		  }
	  }

		(metricsServer ? GetMetrics(RadiusMetrics, statName, radiusKeys(statName))).mapTo[List[MetricsItem]].map(f => toPrometheus(statName, f))
  }
  
  def getPrometheusHttpStatsFut(statName: String) = {
	  def toPrometheus(statName: String, stats: List[MetricsItem]): String = {

		  statName match {
		  case "httpOperation" => genPrometheusString(stats, "http_operations", "The number of http operations processed")
		  case _ => ""

		  }
	  }

	  (metricsServer ? GetMetrics(HttpMetrics, statName, httpKeys(statName))).mapTo[List[MetricsItem]].map(f => toPrometheus(statName, f))
  }
  
  def getPrometheusSessionStatsFut(statName: String) = {
	  def toPrometheus(statName: String, stats: List[MetricsItem]): String = {

		  statName match {
			  case "sessionGroups" => genPrometheusString(stats, "sessions", "The number of radius and diameter sessions", false)
			  case _ => ""

		  }
	  }

	  (metricsServer ? GetMetrics(SessionMetrics, statName, httpKeys(statName))).mapTo[List[MetricsItem]].map(f => toPrometheus(statName, f))
  }
    
}