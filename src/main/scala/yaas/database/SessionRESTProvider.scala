package yaas.database

import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model._
import akka.http.scaladsl._
import akka.http.scaladsl.server.Route

import com.typesafe.config._
import scala.concurrent.duration._
import akka.actor.{ActorSystem, Actor, ActorLogging, ActorRef, Props}
import scala.util.{Success, Failure}

import de.heikoseeberger.akkahttpjson4s.Json4sSupport

import yaas.database._

object SessionRESTProvider {
  def props() = Props(new SessionRESTProvider)
}

trait JsonSupport extends Json4sSupport {
  implicit val serialization = org.json4s.jackson.Serialization
  implicit val json4sFormats = org.json4s.DefaultFormats
}

class SessionRESTProvider extends Actor with ActorLogging with JsonSupport {
  
  import SessionRESTProvider._
  
  implicit val actorSystem = context.system
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher
  
  val config = ConfigFactory.load().getConfig("aaa.sessionsDatabase")
  val bindAddress= config.getString("bindAddress")
  val bindPort = config.getInt("bindPort")
  implicit val askTimeout : akka.util.Timeout = 3000 millis
  
  def receive = {
    case _ =>
  }
  
  val route = pathPrefix("sessions") {
    get {
      pathPrefix("find") {
        parameterMap { params => {
            log.debug(s"find radius sessions for $params")
            if(params.size == 0) complete(StatusCodes.NotAcceptable, "Required ipAddress, MACAddress or clientId parameter")
            else params.keys.map(_ match {
              case "ipAddress" => complete(SessionDatabase.findSessionsByIPAddress(params("ipAddress")))
              case "MACAddress" => complete(SessionDatabase.findSessionsByMACAddress(params("macAddress")))
              case "clientId" => complete(SessionDatabase.findSessionsByClientId(params("clientId")))
              case _ => complete(StatusCodes.NotAcceptable, "Required ipAddress, MACAddress or clientId, parameter")
            }).head
          }
        }
      } 
    }
  }
  
  val bindFuture = Http().bindAndHandle(route, bindAddress, bindPort)
  
  bindFuture.onComplete {
    case Success(binding) =>
      log.info("Sessions database REST server bound to {}", binding.localAddress )
    case Failure(e) =>
       log.error(e.getMessage)
  }

}