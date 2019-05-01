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
import yaas.instrumentation.StatsServer._

import de.heikoseeberger.akkahttpjson4s.Json4sSupport

object StatsRESTProvider {
  def props(statsServer: ActorRef) = Props(new StatsRESTProvider(statsServer))
}

trait JsonSupport extends Json4sSupport {
  implicit val serialization = org.json4s.jackson.Serialization
  implicit val json4sFormats = org.json4s.DefaultFormats
}

class StatsRESTProvider(statsServer: ActorRef) extends Actor with ActorLogging {
  
  import StatsRESTProvider._
  
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
    }
  }
  
  val textRoute = 
    pathPrefix("metrics"){
      // Prometheus
      val futList = List(
        getPrometheusDiameterStatsFut("diameterRequestReceived"),
        getPrometheusDiameterStatsFut("diameterAnswerReceived"),
        getPrometheusDiameterStatsFut("diameterRequestTimeout"),
        getPrometheusDiameterStatsFut("diameterAnswerSent"),
        getPrometheusDiameterStatsFut("diameterRequestDropped"),
        getPrometheusDiameterStatsFut("diameterHandlerServer"),
        getPrometheusDiameterStatsFut("diameterHandlerClient"),
        getPrometheusDiameterStatsFut("diameterHandlerClientTimeout"),
        
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
      pathPrefix("diameter"){
        pathPrefix("peers"){
          get {
            complete((context.parent ? IXGetPeerStatus).mapTo[Map[String, StatOps.DiameterPeerStat]])
          }
        } ~
        pathPrefix("stats"){
          path(Remaining){ statName =>
            parameterMap { params =>
              
              // To validate the input and generate the full list if none is specified
              val allKeys = diameterKeys(statName)
              val inputKeys = params.get("agg").map(_.split(",").toList).getOrElse(allKeys)
              val invalidKeys = inputKeys.filter(!allKeys.contains(_))
              if(invalidKeys.length == 0) complete((statsServer ? GetDiameterStats(statName, inputKeys)).mapTo[List[DiameterStatsItem]])  
              else complete(StatusCodes.NotAcceptable, invalidKeys.mkString(","))
            }
          }
        }
      } ~ 
      pathPrefix("radius") {
        pathPrefix("stats") {
          path(Remaining) { statName =>
            parameterMap { params =>
              // To validate the input and generate the full list if none is specified
              val allKeys = radiusKeys(statName)
              val inputKeys = params.get("agg").map(_.split(",").toList).getOrElse(allKeys)
              val invalidKeys = inputKeys.filter(!allKeys.contains(_))
              if(invalidKeys.length == 0) complete((statsServer ? GetRadiusStats(statName, inputKeys)).mapTo[List[RadiusStatsItem]])  
              else complete(StatusCodes.NotAcceptable, invalidKeys.mkString(","))
            }
          }
        }
      }
  }

  
  val bindFuture = Http().bindAndHandle(new RestRouteProvider().restRoute ~ textRoute, bindAddress, bindPort)
  
  bindFuture.onComplete {
    case Success(binding) =>
      log.info("Instrumentation REST server bound to {}", binding.localAddress)
    case Failure(e) =>
      log.error(e.getMessage)
  }
  
  def getPrometheusDiameterStatsFut(statName: String) = {
		  def toPrometheus(statName: String, stats: List[DiameterStatsItem]): String = {

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
		  (statsServer ? GetDiameterStats(statName, diameterKeys(statName))).mapTo[List[DiameterStatsItem]].map(f => toPrometheus(statName, f))
  }
  
    def getPrometheusRadiusStatsFut(statName: String) = {
		  def toPrometheus(statName: String, stats: List[RadiusStatsItem]): String = {

				  def genPrometheusString(counterName: String, helpString: String): String = {
						  s"\n# HELP $counterName $helpString\n# TYPE $counterName counter\n" +
						  stats.map(dsi => s"$counterName{${dsi.keyMap.map{case (k, v) => s"""$k="$v""""}.mkString(",")}} ${dsi.counter}").mkString("\n") +
						  "\n"
				  }

				  statName match {
  				  case "radiusServerRequest" => genPrometheusString("radius_server_requests_received", "The number of Radius server requests received")
  				  case "radiusServerDropped" => genPrometheusString("radius_server_requests_dropped", "The number of Radius server requests dropped")
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
		  
		  (statsServer ? GetRadiusStats(statName, radiusKeys(statName))).mapTo[List[RadiusStatsItem]].map(f => toPrometheus(statName, f))
  }
    
}