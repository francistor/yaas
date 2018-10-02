package yaas.instrumentation

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill }
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model._
import akka.http.scaladsl._
import akka.pattern.ask
import com.typesafe.config._
import scala.util.{Success, Failure}
import scala.concurrent.duration._
import yaas.server.Router._
import yaas.server.DiameterPeerPointer
import yaas.stats.StatsServer._
import yaas.stats.{DiameterStatsItem, RadiusStatsItem}

import de.heikoseeberger.akkahttpjson4s.Json4sSupport
//import org.json4s._
//import org.json4s.JsonDSL._
//import org.json4s.jackson.JsonMethods._

object RESTProvider {
  def props(statsServer: ActorRef) = Props(new RESTProvider(statsServer))
}

trait JsonSupport extends Json4sSupport {
  implicit val serialization = org.json4s.jackson.Serialization
  implicit val json4sFormats = org.json4s.DefaultFormats
}

class RESTProvider(statsServer: ActorRef) extends Actor with ActorLogging with JsonSupport {
  
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
  
  val route = pathPrefix("diameter"){
    pathPrefix("peers"){
      get {
        onComplete(context.parent ? IXGetPeerStatus) {
          case Success(peers) =>
            val p = peers.asInstanceOf[Map[String, DiameterPeerPointer]]
            //complete(HttpEntity(MediaTypes.`application/json`.toContentType, compact(render(Extraction.decompose(p)))))
            complete(p)
          case Failure(ex) =>
            log.error(ex, ex.getMessage)
            complete(StatusCodes.ServiceUnavailable)
        }
      }
    } ~
    pathPrefix("stats"){
      pathPrefix("diameterRequestReceived") {
        parameter('agg) { aggValue =>
          val q = statsServer ? GetDiameterStats("diameterRequestReceived", aggValue.split(",").toList)
          complete(q.mapTo[List[DiameterStatsItem]])
        }
      }
    }
  }
  
  val bindFuture = Http().bindAndHandle(route, bindAddress, bindPort)
  
  bindFuture.onComplete {
    case Success(binding) =>
      log.info("Instrumentaiton REST server bound to {}", binding.localAddress )
    case Failure(e) =>
       log.error(e.getMessage)
  }
  
}