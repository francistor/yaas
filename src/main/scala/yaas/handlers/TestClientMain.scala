package yaas.handlers

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._
import yaas.coding._
import yaas.util.IDGenerator
import yaas.coding.DiameterConversions._
import yaas.config.DiameterConfigManager
import yaas.dictionary.DiameterDictionary
import yaas.util.OctetOps
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
  
  //////////////////////////////////////////////////////////////////////////////
  // Helper functions
  
  def wait[T](r: Awaitable[T]) = Await.result(r, 10 second)
  
  def getJson(url: String) = {
    wait(for {
      r <- http.singleRequest(HttpRequest(uri = url))
      j <- Unmarshal(r.entity).to[JValue].recover{case _ => JNothing}
    } yield j)
  }
  
  def ok(msg: String = "") = println(s"\t[OK] $msg")
  
  def fail(msg: String = "") = println(s"\t[FAIL] $msg")
  
  // Returns -1 on error
  def getCounterForKey(stats: JValue, keymap: Map[String, String]) = {
    val out = for {
      JArray(statValues) <- stats
      statValue <- statValues
      JInt(counterValue) <- statValue \ "counter" if((statValue \ "keyMap" diff keymap) == Diff(JNothing, JNothing, JNothing))
    } yield counterValue
    
    if(out.length == 0) -1 else out(0)
  }
  
  def checkStat(jStat: JValue, targetCounter: Int, key: Map[String, String], legend: String) = {
    val counter = getCounterForKey(jStat, key)
    if(targetCounter == counter) ok(legend + ": " + counter) else fail(legend + ": " + counter + " expected: " + targetCounter)
  }
  
  //////////////////////////////////////////////////////////////////////////////
  
  // Wait some time before starting the tests.
  // peerCheckTimeSeconds should be configured with about 10 seconds. Starting the tests after
  // 15 seconds will give some time to retry connections that will have initially failed due 
  // to all servers starting at almost the same time
  override def preStart = {
    context.system.scheduler.scheduleOnce(7 seconds, self, "Start")
  }
  
  // To receive the start message
  override def receive = {
    case "Start" =>
      // Start testing
      nextTest
      
    case message: Any => 
      super.receive(message)
  }
  
  // _ is needed to promote the method (no arguments) to a function
  val tests = IndexedSeq[() => Unit](
      clientPeerConnections _, 
      serverPeerConnections _, 
      superserverPeerConnections _, 
      testAccessRequestWithAccept _,
      testAccessRequestWithReject _, 
      testAccessRequestWithDrop _,
      testAccountingRequest _,
      testAccountingRequestWithDrop _,
      checkSuperserverRadiusStats _,
      checkServerRadiusStats _,
      checkClientRadiusStats _
  )
  var lastTestIdx = -1
  def nextTest(): Unit = {
    lastTestIdx = lastTestIdx + 1
    if(tests.length > lastTestIdx) tests(lastTestIdx)() else {
      println("FINISHED")
    }
  }
  
  
  /////////////////////////////////////////////////////////////////////////
  // Test functions
  /////////////////////////////////////////////////////////////////////////
  
  /*
   * 1 Access-Request with Accept. Retried by the client (timeout from nes)
   * 1 Access-Request with Reject. Retried by the client (timeout from nes)
   * 1 Access-Request dropped. Retried by the client and the server (timeout from nes, timeout from server)
   * 
   * 1 Accounting with Response. Retried by the client (timeout from nes)
   * 1 Accounting to be dropped. Retried by the client and the server (timeout server)
   */
  
  def clientPeerConnections(): Unit = {
      // Test-Client is connected to server.yaasserver, and not connected to non-existing-server.yaasserver
      println("[TEST] Client Peer connections")
      val testClientPeers = getJson("http://localhost:19001/diameter/peers")
      if((testClientPeers \ "server.yaasserver" \ "status").extract[Int] == 2) ok("Connected to server") else fail("Not connected to server") 
      if((testClientPeers \ "non-existing-server.yaasserver" \ "status").extract[Int] != 2) ok("non-existing-server status is != 2") else fail("Connected to non-existing-server!") 
      nextTest
  }
  
  def serverPeerConnections(): Unit = {
      // Test-Server is connected to client.yaasclient and superserver.yaassuperserver
      println("[TEST] Server Peer connections")
      val testServerPeers = getJson("http://localhost:19002/diameter/peers")
      if((testServerPeers \ "superserver.yaassuperserver" \ "status").extract[Int] == 2) ok("Connected to supersserver") else fail("Not connected to superserver") 
      if((testServerPeers \ "client.yaasclient" \ "status").extract[Int] == 2) ok("Connected to client") else fail("Not connected to client")
      nextTest
  }
  
  def superserverPeerConnections(): Unit = {
      // Test-SuperServer is connected to client.yaasclient and superserver.yaassuperserver
      println("[TEST] Superserver Peer connections")
      val testSuperServerPeers = getJson("http://localhost:19003/diameter/peers")
      if((testSuperServerPeers \ "server.yaasserver" \ "status").extract[Int] == 2) ok("Connected to server") else fail("Not connected to server") 
      nextTest
  }
  
  def testAccessRequestWithAccept(): Unit = {
    println("[TEST] Access Request --> With accept")
    val userPassword = "The user-password!"
    val accessRequest= RadiusPacket.request(ACCESS_REQUEST) << 
      ("User-Name" -> "test@accept") << 
      ("User-Password" -> userPassword)
      
    // Will generate an unsuccessful request to "non-existing-server" and a successful request to yaasserver
    sendRadiusGroupRequest("allServers", accessRequest, 1000, 1).onComplete {
      case Success(response) => 
        if(OctetOps.fromHexToUTF8(response >> "User-Password") == userPassword){
          ok("Password attribute received correctly")
          nextTest
        }
        else fail("Password attribute is " + OctetOps.fromHexToUTF8(response >> "User-Password") + "!= " + userPassword)
      case Failure(ex) => fail("Response not received")
    }
  }
  
  def testAccessRequestWithReject(): Unit = {
    println("[TEST] Access Request --> With reject")
    val accessRequest= RadiusPacket.request(ACCESS_REQUEST) << 
      ("User-Name" -> "test@reject")
      
    // Will generate an unsuccessful request to "non-existing-server" and a successful request to yaasserver
    sendRadiusGroupRequest("allServers", accessRequest, 1000, 1).onComplete {
      case Success(response) => 
        if(response.code == RadiusPacket.ACCESS_REJECT){
          ok("Reject received correctly")
        } else fail("Response is not a reject")
        
        if((response ->> "Reply-Message") == "The reply message!"){
          ok("Reply message is correct")
        }
        nextTest
        
      case Failure(ex) => fail("Response not received")
    }
  }
  
  def testAccessRequestWithDrop(): Unit = {
    println("[TEST] Access Request --> With drop")
    val accessRequest= RadiusPacket.request(ACCESS_REQUEST) << 
      ("User-Name" -> "test@drop") 
      
    // Will generate an unsuccessful request to "non-existing-server". Yaasserver will also send it twice to supserserver
    sendRadiusGroupRequest("allServers", accessRequest, 1500, 1).onComplete {
      case Success(response) => 
        fail("Received response")
        
      case Failure(ex) => 
        ok("Response not received")
        nextTest
    }
  }
  
  def testAccountingRequest(): Unit = {
    
    // Accounting request
    println("[TEST] Accounting request")
    val accountingRequest= RadiusPacket.request(ACCOUNTING_REQUEST) << 
      ("User-Name" -> "test@test") 
      
    // Will generate an unsuccessful request to "non-existing-server" and a successful request to yaasserver
    sendRadiusGroupRequest("allServers", accountingRequest, 2000, 1).onComplete {
      case Success(response) => 
        ok("Received response")
        nextTest
        
      case Failure(ex) => 
        fail("Response not received")
    }
  }
  
  def testAccountingRequestWithDrop(): Unit = {
    
    // Generate another one to be discarded by the superserver
    sendRadiusGroupRequest("testServer", RadiusPacket.request(ACCOUNTING_REQUEST) << ("User-Name" -> "test@drop"), 500, 0).onComplete {
      case _ => nextTest
    }
  }
   
  def checkSuperserverRadiusStats(): Unit = {
      // Superserver
      println("[TEST] Superserver stats")
      val port = 19003
      
      // Requests received
      val jServerRequests = getJson(s"http://localhost:${port}/radius/stats/radiusServerRequest")
      // 1 accept, 1 reject, 2 drop
      checkStat(jServerRequests, 4, Map("rh" -> "127.0.0.1", "rq" -> "1"), "Access-Request received")
      // 1 acct ok, 2 acct drop
      checkStat(jServerRequests, 3, Map("rh" -> "127.0.0.1", "rq" -> "4"), "Accounting-Request received")
 
      // Responses sent
      val jServerResponses = getJson(s"http://localhost:${port}/radius/stats/radiusServerResponse")
      // 1 access accept
      checkStat(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "2"), "Access-Accept sent")
      // 1 access reject
      checkStat(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "3"), "Access-Reject sent")
      // 1 accounting response
      checkStat(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "5"), "Accounting-Response sent")
      
      // Packets dropped by the server (not the handler)
      val jServerDrops = getJson(s"http://localhost:${port}/radius/stats/radiusServerDropped")
      // No packets dropped. Stat not shown 
      checkStat(jServerDrops, -1, Map(), "Packets dropped")
      
      // Packets answered by handler
      val jHandlerResponses = getJson(s"http://localhost:${port}/radius/stats/radiusHandlerResponse?agg=rs")
      // 1 access accept
      checkStat(jHandlerResponses, 1, Map("rs" -> "2"), "Access-Accept responses")
      // 1 access reject
      checkStat(jHandlerResponses, 1, Map("rs" -> "3"), "Access-Reject responses")

      // Packets dropped by handler
      val jHandlerDrops = getJson(s"http://localhost:${port}/radius/stats/radiusHandlerDropped?agg=rq")
      // 2 packet dropped each, since the server will rety to superserver
      checkStat(jHandlerDrops, 2, Map("rq" -> "1"), "Access-Request dropped")
      checkStat(jHandlerDrops, 2, Map("rq" -> "4"), "Accounting-Request dropped")
      
      nextTest
  }
  
  def checkServerRadiusStats(): Unit = {
      // Superserver
      println("[TEST] Server stats")
      val port = 19002
      
      // Requests received
      val jServerRequests = getJson(s"http://localhost:${port}/radius/stats/radiusServerRequest")
      // 1 accept, 1 reject, 1 drop
      checkStat(jServerRequests, 3, Map("rh" -> "127.0.0.1", "rq" -> "1"), "Access-Request received")
      // 1 acct ok, 1 acct drop
      checkStat(jServerRequests, 2, Map("rh" -> "127.0.0.1", "rq" -> "4"), "Accounting-Request received")
 
      // Responses sent
      val jServerResponses = getJson(s"http://localhost:${port}/radius/stats/radiusServerResponse")
      // 1 access accept
      checkStat(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "2"), "Access-Accept sent")
      // 1 access reject
      checkStat(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "3"), "Access-Reject sent")
      // 1 accounting respone
      checkStat(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "5"), "Accounting-Response sent")
      
      // Packets dropped by the server (not the handler)
      val jServerDrops = getJson(s"http://localhost:${port}/radius/stats/radiusServerDropped")
      // No packets dropped. Stat not shown 
      checkStat(jServerDrops, -1, Map(), "Packets dropped")
      
      // Packets answered by handler
      val jHandlerResponses = getJson(s"http://localhost:${port}/radius/stats/radiusHandlerResponse?agg=rs")
      // 1 access accept
      checkStat(jHandlerResponses, 1, Map("rs" -> "2"), "Access-Accept responses")
      // 1 access reject
      checkStat(jHandlerResponses, 1, Map("rs" -> "3"), "Access-Reject responses")
      // 1 accounting response
      checkStat(jHandlerResponses, 1, Map("rs" -> "5"), "Accounting responses")

      // Packets dropped by handler
      val jHandlerDrops = getJson(s"http://localhost:${port}/radius/stats/radiusHandlerDropped?agg=rq")
      // Server does not drop packets
      checkStat(jHandlerDrops, -1, Map("rq" -> "1"), "Access-Request dropped")
      checkStat(jHandlerDrops, -1, Map("rq" -> "4"), "Accounting-Request dropped")
      
      nextTest
  }
  
  def checkClientRadiusStats(): Unit = {
      // Superserver
      println("[TEST] Client stats")
      val port = 19001
      
      // 3 requests to the non-existing-server
      val jClientRequests1 = getJson(s"http://localhost:${port}/radius/stats/radiusClientRequest?agg=rh")
      checkStat(jClientRequests1, 3, Map("rh" -> "1.1.1.1:1812"), "Requests sent to non existing server")
      
      // 3 access requests, 2 accounting requests to server
      val jClientRequests2 = getJson(s"http://localhost:${port}/radius/stats/radiusClientRequest?agg=rh,rq")
      checkStat(jClientRequests2, 3, Map("rq" -> "1", "rh" -> "127.0.0.1:1812"), "Access-Requests sent to server")
      checkStat(jClientRequests2, 2, Map("rq" -> "4", "rh" -> "127.0.0.1:1813"), "Acounting-Requests sent to server")
      
      // Responses received
      val jResponsesReceived = getJson(s"http://localhost:${port}/radius/stats/radiusClientResponse?agg=rs")
      checkStat(jResponsesReceived, 1, Map("rs" -> "2"), "Access-Accept received from server")
      checkStat(jResponsesReceived, 1, Map("rs" -> "3"), "Access-Reject received from server")
      checkStat(jResponsesReceived, 1, Map("rs" -> "5"), "Accouning-Response received from server")
      
      // Timeouts
      val jTimeouts = getJson(s"http://localhost:${port}/radius/stats/radiusClientResponse?agg=rh,rq")
      // One per each to non-existing-server
      checkStat(jTimeouts, 3, Map("rq" -> "1", "rh" -> "1.1.1.1:1812"), "Access-Request timeouts from non existing server")
      // The one explicitly dropped
      checkStat(jTimeouts, 1, Map("rq" -> "1", "rh" -> "127.0.0.1:1812"), "Access-Request timeouts from server")
      // Just one, in the first try
      checkStat(jTimeouts, 1, Map("rq" -> "4", "rh" -> "1.1.1.1:1813"), "Accounting-Request timeouts from non existing server")
      // The one explicitly dropped
      checkStat(jTimeouts, 1, Map("rq" -> "4", "rh" -> "127.0.0.1:1813"), "Accounting-Request timeouts from server")
      
      nextTest
  }
}