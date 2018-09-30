package yaas.handlers

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._
import yaas.coding._
import yaas.util.IDGenerator
import yaas.coding.DiameterConversions._
import yaas.config.DiameterConfigManager
import yaas.dictionary.DiameterDictionary

import scala.util.{Success, Failure}
import yaas.server.MessageHandler

import scala.concurrent._
import scala.concurrent.duration._

import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.model._
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

trait JsonSupport extends Json4sSupport {
  implicit val serialization = org.json4s.jackson.Serialization
  implicit val json4sFormats = org.json4s.DefaultFormats
}

class Main(statsServer: ActorRef) extends MessageHandler(statsServer) with JsonSupport {
  
  log.info("Instantiated Radius/Diameter client application")
  
  implicit val idGen = new IDGenerator
  
  implicit val materializer = ActorMaterializer()
  
  val http = Http(context.system)
  
  // Wait some time before starting the tests
  override def preStart = {
    context.system.scheduler.scheduleOnce(10 seconds, self, "Start")
  }
  
  // To receive the start message
  override def receive = {
    case "Start" =>
      runTest
    case _ => super.receive
  }
  
  def runTest = {
    try {
      println("[TEST] Requesting diameter peer info")
      
      val httpRes = Await.result(http.singleRequest(HttpRequest(uri = "http://localhost:19000/diameter/peers")), 3 second)
      val j = Await.result(Unmarshal(httpRes.entity).to[JValue], 1 second)
      println(pretty(render(j)))
      
      
      
    } catch {
      case e: Throwable =>
        println(e.getMessage)
        e.printStackTrace()
    }
  }
}