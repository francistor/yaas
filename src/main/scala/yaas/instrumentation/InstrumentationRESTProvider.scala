package yaas.instrumentation

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.http.scaladsl._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import com.typesafe.config._
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization
import yaas.instrumentation.MetricsServer._
import yaas.server.Router._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

object InstrumentationRESTProvider {
  def props(statsServer: ActorRef): Props = Props(new InstrumentationRESTProvider(statsServer))
}

trait JsonSupport extends Json4sSupport {
  implicit val serialization: Serialization.type = org.json4s.jackson.Serialization
  implicit val json4sFormats: DefaultFormats.type = org.json4s.DefaultFormats
}

/**
 * Actor that implements a REST server for instrumentation.
 *
 * JSON metrics are of the form
 <code>
 [
   {"keymap":{"key1": "value1", "key2": "value2", ...}, "value": 0}}
 ]
 </code>
 * @param metricsServer the Actor holding the metrics
 */
class InstrumentationRESTProvider(metricsServer: ActorRef) extends Actor with ActorLogging {
  
  private implicit val actorSystem: ActorSystem = context.system
  private implicit val materializer: ActorMaterializer = ActorMaterializer()
  private implicit val executionContext: ExecutionContextExecutor = actorSystem.dispatcher
  
  private val isDatabaseServer = ConfigFactory.load().getConfig("aaa.sessionsDatabase").getString("role") == "server"
  
  private val config = ConfigFactory.load().getConfig("aaa.instrumentation")
  private val bindPort = config.getInt("bindPort")
  private val bindAddress= config.getString("bindAddress")
  implicit val askTimeout : akka.util.Timeout = config.getInt("timeoutMillis").millis

  def receive: Receive = {
    case _ =>
  }

  def customExceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case e: InvalidLabelException => complete(400, e.msg)
      case e: InvalidMetricException => complete(400, e.msg)
      case e: Throwable => complete(500, e.getMessage())
    }

  private def genPrometheusString(stats: List[MetricsItem], metricName: String, helpString: String, isCounter: Boolean = true): String = {
    val theType = if(isCounter) "counter" else "gauge"
    s"\n# HELP $metricName $helpString\n# TYPE $metricName $theType\n" +
      stats.map(dsi => s"$metricName{${dsi.keyMap.map{case (k, v) => s"""$k="$v""""}.mkString(",")}} ${dsi.value}").mkString("\n") +
      "\n"
  }

  private val configRoute =
    pathPrefix("ready"){
      complete(200, "OK")
    } ~
    pathPrefix("config"){
      pathPrefix("reload"){
        patch {
          parameters("fileName" ? "all") {
            fileName =>
              log.info(s"Reload $fileName")
              complete((context.parent ? yaas.server.Router.IXReloadConfig(Some(fileName))).mapTo[String])
          }
        }
      } ~
      pathPrefix("setLogLevel"){
        patch {
          parameters("loggerName", "level"){
            (loggerName, level) =>
              if(! List("TRACE", "DEBUG", "INFO", "WARN", "ERROR").contains(level.toUpperCase)) complete(404, s"Invalid level: $level")
              else {
                import ch.qos.logback.classic.Level
                import org.slf4j.LoggerFactory

                log.info(s"Setting log level for $loggerName to $level")
                LoggerFactory.getLogger(loggerName).asInstanceOf[ch.qos.logback.classic.Logger].setLevel(Level.toLevel(level))
                complete(200, "OK")
              }
          }
        }
      }
  }

  private val prometheusRoute =
    pathPrefix("metrics"){
      def gatherMetricsFut(metricType: Metrics, metricName: String, prometheusMetricName: String, prometheusHelpString: String, isCounter: Boolean = true) = {
        (metricsServer ? GetMetrics(metricType, metricName, List())).mapTo[List[MetricsItem]]
          .map(metricsItem => genPrometheusString(metricsItem, prometheusMetricName, prometheusHelpString, isCounter))
      }

      // Prometheus
      val futList = List(
        gatherMetricsFut(DiameterMetrics, "diameterRequestReceived", "diameter_requests_received", "The number of Diameter requests received"),
        gatherMetricsFut(DiameterMetrics, "diameterAnswerReceived", "diameter_answers_received", "The number of Diameter answers received"),
        gatherMetricsFut(DiameterMetrics, "diameterRequestTimeout", "diameter_requests_timeout", "The number of Diameter timeouts"),
        gatherMetricsFut(DiameterMetrics, "diameterAnswerSent", "diameter_answers_sent", "The number of Diameter answers sent"),
        gatherMetricsFut(DiameterMetrics, "diameterRequestSent", "diameter_requests_sent", "The number of Diameter requests sent"),
        gatherMetricsFut(DiameterMetrics, "diameterRequestDropped", "diameter_requests_dropped", "The number of Diameter requests dropped"),
        gatherMetricsFut(DiameterMetrics, "diameterHandlerServer", "diameter_handler_server", "The number of Diameter requests received by handlers"),
        gatherMetricsFut(DiameterMetrics, "diameterHandlerClient", "diameter_handler_client", "The number of Diameter requests sent by handlers"),
        gatherMetricsFut(DiameterMetrics, "diameterHandlerClientTimeout", "diameter_handler_client_timeout", "The number of Diameter requests sent by handler timed out"),
        gatherMetricsFut(DiameterMetrics, "diameterPeerQueueSize", "diameter_peer_queue_size", "The queue of Diameter requests to be processed", isCounter = false),

        gatherMetricsFut(RadiusMetrics, "radiusServerRequest", "radius_server_requests", "The number of Radius server requests received"),
        gatherMetricsFut(RadiusMetrics, "radiusServerDropped", "radius_server_dropped", "The number of Radius server requests dropped"),
        gatherMetricsFut(RadiusMetrics, "radiusServerResponse", "radius_server_responses", "The number of Radius server requests responsed"),
        gatherMetricsFut(RadiusMetrics, "radiusClientRequest", "radius_client_requests", "The number of Radius client requests sent"),
        gatherMetricsFut(RadiusMetrics, "radiusClientResponse", "radius_client_responses", "The number of Radius client responses received"),
        gatherMetricsFut(RadiusMetrics, "radiusClientTimeout", "radius_client_timeout", "The number of Radius client requests timed out"),
        gatherMetricsFut(RadiusMetrics, "radiusClientDropped", "radius_client_dropped", "The number of Radius client responses dropped"),
        gatherMetricsFut(RadiusMetrics, "radiusHandlerResponse", "radius_handler_responses", "The number of Radius responses sent by handlers"),
        gatherMetricsFut(RadiusMetrics, "radiusHandlerDropped", "radius_handler_dropped", "The number of Radius requests dropped by handlers"),
        gatherMetricsFut(RadiusMetrics, "radiusHandlerRequest", "radius_handler_requests", "The number of Radius requests received by handlers"),
        gatherMetricsFut(RadiusMetrics, "radiusHandlerRetransmission", "radius_handler_retransmission", "The number of Radius requests retransmitted"),
        gatherMetricsFut(RadiusMetrics, "radiusHandlerTimeout", "radius_handler_timeout", "The number of Radius requests timed out"),
        gatherMetricsFut(RadiusMetrics, "radiusClientQueueSize", "radius_client_queue_size", "The queue of requests to be processed", isCounter = false),

        gatherMetricsFut(HttpMetrics, "httpOperation", "http_operations", "The number of http operations processed")
      )
      
      val completeFutList = if(isDatabaseServer) futList :+
        gatherMetricsFut(SessionMetrics, "sessions", "sessions", "The number of radius and diameter sessions")
      else futList
      
      complete(Future.reduce(completeFutList)((a, b) => a + b))
    }

  class RestRouteProvider extends JsonSupport {

    // agg contains the agg query string, a comma separated list of labels to aggregate
    private def completeMetrics(metricsType: Metrics, statName: String, agg: Option[String]) = {
      val labels = agg match {
        case Some(l) => l.split(",").toList
        case None => List[String]()
      }
      complete((metricsServer ? GetMetrics(metricsType, statName, labels)).mapTo[List[MetricsItem]])
    }

    def restRoute: Route =
      patch {
        pathPrefix("diameter") {
          pathPrefix("metrics" / "reset") {
            metricsServer ! MetricsServer.DiameterMetricsReset
            log.info("Diameters Metrics Reset")
            complete(200, "OK")
          }
        } ~
          pathPrefix("radius") {
            pathPrefix("metrics" / "reset") {
              metricsServer ! MetricsServer.RadiusMetricsReset
              log.info("Radius Metrics Reset")
              complete(200, "OK")
            }
          } ~
          pathPrefix("http") {
            pathPrefix("metrics" / "reset") {
              metricsServer ! MetricsServer.HttpMetricsReset
              log.info("HTTP Metrics Reset")
              complete(200, "OK")
            }
          }
      } ~
      get {
        pathPrefix("diameter") {
          pathPrefix("peers") {
            complete((context.parent ? IXGetPeerStatus).mapTo[Map[String, MetricsOps.DiameterPeerStatus]])
          } ~
          pathPrefix("metrics") {
            path(Remaining) { statName => {
              parameters("agg".?) {
                agg => completeMetrics(DiameterMetrics, statName, agg)
              }
            }
            }
          }
        } ~
        pathPrefix("radius") {
          pathPrefix("metrics") {
            path(Remaining) { statName => {
              parameters("agg".?) {
                agg => completeMetrics(RadiusMetrics, statName, agg)
              }
            }
            }
          }
        } ~
        pathPrefix("http") {
          pathPrefix("metrics") {
            path(Remaining) { statName => {
              parameters("agg".?) {
                agg => completeMetrics(HttpMetrics, statName, agg)
              }
            }
            }
          }
        } ~
        pathPrefix("session") {
          pathPrefix("metrics") {
            path(Remaining) { statName => {
              parameters("agg".?) {
                agg => completeMetrics(SessionMetrics, statName, agg)
              }
            }
            }
          }
        }
      }
  }

  // Launch Server
  if (bindAddress.contains(".")) {
    val route = new RestRouteProvider().restRoute ~ prometheusRoute ~ configRoute
    val bindFuture = Http().bindAndHandle(handleExceptions(customExceptionHandler)(route), bindAddress, bindPort)

    bindFuture.onComplete {
      case Success(_) =>
        log.info("Instrumentation REST server bound to {}:{}", bindAddress, bindPort)
      case Failure(e) =>
        log.error(e.getMessage)
        System.exit(-1)
    }
  }
}