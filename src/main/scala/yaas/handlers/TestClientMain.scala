package yaas.handlers

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._
import yaas.coding._
import yaas.util.IDGenerator
import yaas.coding.DiameterConversions._
import yaas.config.DiameterConfigManager
import yaas.dictionary.DiameterDictionary
import yaas.coding.RadiusPacket._
import yaas.coding.RadiusConversions._

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

class TestClientMain(statsServer: ActorRef) extends MessageHandler(statsServer) with JsonSupport {
  
  log.info("Instantiated Radius/Diameter client application")
  
  implicit val idGen = new IDGenerator
  
  implicit val materializer = ActorMaterializer()
  
  val http = Http(context.system)
  
  // Wait some time before starting the tests.
  // peerCheckTimeSeconds should be configured with about 10 seconds. Starting the tests after
  // 15 seconds will give some time to retry connections that will have initially failed due 
  // to all servers starting at almost the same time
  override def preStart = {
    context.system.scheduler.scheduleOnce(15 seconds, self, "Start")
  }
  
  // To receive the start message
  override def receive = {
    case "Start" =>
      runTest
    case _ => super.receive
  }
  
  // Helper functions
  def wait[T](r: Awaitable[T]) = Await.result(r, 3 second)
  def getJson(url: String) = {
    wait(for {
      r <- http.singleRequest(HttpRequest(uri = url))
      j <- Unmarshal(r.entity).to[JValue]
    } yield j)
  }
  def ok(msg: String = "") = println(s"\t[OK] $msg")
  def fail(msg: String = "") = println(s"\t[FAIL] $msg")
  
  def runTest = {
    try {
      
      // Test-Client is connected to server.yaasserver, and not connected to non-existing-server.yaasserver
      println("[TEST] Client Peer connections")
      val testClientPeers = getJson("http://localhost:19001/diameter/peers")
      if((testClientPeers \ "server.yaasserver" \ "status").extract[Int] == 2) ok("Connected to server") else fail("Not connected to server") 
      if((testClientPeers \ "non-existing-server.yaasserver" \ "status").extract[Int] != 2) ok("non-existing-server status is != 2") else fail("Connected to non-existing-server!")
      
      // Test-Server is connected to client.yaasclient and superserver.yaassuperserver
      println("[TEST] Server Peer connections")
      val testServerPeers = getJson("http://localhost:19002/diameter/peers")
      if((testServerPeers \ "superserver.yaassuperserver" \ "status").extract[Int] == 2) ok("Connected to supersserver") else fail("Not connected to superserver") 
      if((testServerPeers \ "client.yaasclient" \ "status").extract[Int] == 2) ok("Connected to client") else fail("Not connected to client")
  
      // Test-SuperServer is connected to client.yaasclient and superserver.yaassuperserver
      println("[TEST] Superserver Peer connections")
      val testSuperServerPeers = getJson("http://localhost:19003/diameter/peers")
      if((testSuperServerPeers \ "server.yaasserver" \ "status").extract[Int] == 2) ok("Connected to server") else fail("Not connected to server") 
      
      // Auth Request
      val accessRequest= RadiusPacket.request(ACCESS_REQUEST) << 
        ("User-Name" -> "accept!") << 
        ("User-Password" -> "The user-password!")
      
      val response = wait(sendRadiusGroupRequest("allServers", accessRequest, 1000, 1))
      
    } catch {
      case e: Throwable =>
        println(e.getMessage)
    }
  }
}