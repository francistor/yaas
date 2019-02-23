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

class StatsRESTProvider(statsServer: ActorRef) extends Actor with ActorLogging with JsonSupport {
  
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
  
  
  val route = 
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
            val allKeys = statName match {
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
            val allKeys = statName match {
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
            
            val inputKeys = params.get("agg").map(_.split(",").toList).getOrElse(allKeys)
            val invalidKeys = inputKeys.filter(!allKeys.contains(_))
            if(invalidKeys.length == 0) complete((statsServer ? GetRadiusStats(statName, inputKeys)).mapTo[List[RadiusStatsItem]])  
            else complete(StatusCodes.NotAcceptable, invalidKeys.mkString(","))
          }
        }
      }
    }

  
  val bindFuture = Http().bindAndHandle(route, bindAddress, bindPort)
  
  bindFuture.onComplete {
    case Success(binding) =>
      log.info("Instrumentation REST server bound to {}", binding.localAddress )
    case Failure(e) =>
       log.error(e.getMessage)
  }
  
  
  // Helpers
  def completeDiameterStatsRequest(statName: String, allKeys: List[String], qParam: Option[String]) = {
    val keyList = qParam.map(_.split(",").toList).getOrElse(allKeys)
    val invalidKeys = keyList.filter(!allKeys.contains(_))
    if(invalidKeys.length == 0) complete((statsServer ? GetDiameterStats("diameterRequestReceived", keyList)).mapTo[List[DiameterStatsItem]])
      else complete(StatusCodes.NotAcceptable, invalidKeys.mkString(","))
  }
  
}