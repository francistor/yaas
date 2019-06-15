package yaas.handlers.test

import akka.actor.{ActorSystem, Actor, ActorRef, Props}

import yaas.server._
import yaas.coding._
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

abstract class TestClientBase(metricsServer: ActorRef) extends MessageHandler(metricsServer) with JsonSupport {
  
  log.info("Instantiated Radius/Diameter client application")
  
  val nRequests = Option(System.getenv("YAAS_TEST_REQUESTS")).map(req => Integer.parseInt(req)).getOrElse(10000)
  
  implicit val materializer = ActorMaterializer()
  
  val http = Http(context.system)
  
  //////////////////////////////////////////////////////////////////////////////
  // Helper functions
  
  def wait[T](r: Awaitable[T]) = Await.result(r, 10 second)
  
  def sleep = {
    Thread.sleep(6000)
    nextTest
  }
  
  def intToIPAddress(i: Int) = {
    val bytes = Array[Byte]((i >> 24).toByte, (i >> 16).toByte, (i >> 8).toByte, (i % 8).toByte)
    java.net.InetAddress.getByAddress(bytes).getHostAddress
  }
  
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
  
  def checkMetric(jMetric: JValue, targetCounter: Int, key: Map[String, String], legend: String) = {
    val counter = getCounterForKey(jMetric, key)
    if(targetCounter == counter) ok(legend + ": " + counter) else fail(legend + ": " + counter + " expected: " + targetCounter)
  }
  
  //////////////////////////////////////////////////////////////////////////////
  
  val clientMetricsURL : String
  val serverMetricsURL : String
  val superServerMetricsURL : String
  val superServerSessionsURL : String
  
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
  val tests : IndexedSeq[() => Unit]
  
  println("STARTING TESTS")
  
  var lastTestIdx = -1
  def nextTest(): Unit = {
    lastTestIdx = lastTestIdx + 1
    if(tests.length > lastTestIdx) tests(lastTestIdx)() else {
      println("FINISHED")
      System.exit(0)
    }
  }
  
  
  /////////////////////////////////////////////////////////////////////////
  // Test functions
  /////////////////////////////////////////////////////////////////////////
  
  // Finishes the tests because does not call nextText
  def stop(): Unit = {
    println("Tests stopped")
  }
  
  def checkConnectedPeer(url: String, connectedPeer: String)(): Unit = {
      println(s"[TEST] $url connected to $connectedPeer")
      val peerStatus = getJson(s"${url}/diameter/peers")
      if((peerStatus \ connectedPeer \ "status").extract[Int] == PeerStatus.STATUS_READY) ok(s"Connected to $connectedPeer") else fail(s"Not connected to $connectedPeer") 
      nextTest
  }
  
  def checkNotConnectedPeer(url: String, notConnectedPeer: String)(): Unit = {
      println(s"[TEST] $url connected to notConnectedPeer")
      val peerStatus = getJson(s"${url}/diameter/peers")
      if((peerStatus \ notConnectedPeer \ "status").extract[Int] != PeerStatus.STATUS_READY) ok(s"$notConnectedPeer status is != 2") else fail(s"Connected to $notConnectedPeer") 
      nextTest
  }
  
  def clientPeerConnections(): Unit = {
      // Test-Client is connected to server.yaasserver, and not connected to non-existing-server.yaasserver
      println("[TEST] Client Peer connections")
      val testClientPeers = getJson(s"${clientMetricsURL}/diameter/peers")
      if((testClientPeers \ "server.yaasserver" \ "status").extract[Int] == PeerStatus.STATUS_READY) ok("Connected to server") else fail("Not connected to server") 
      if((testClientPeers \ "non-existing-server.yaasserver" \ "status").extract[Int] != PeerStatus.STATUS_READY) ok("non-existing-server status is != 2") else fail("Connected to non-existing-server!") 
      nextTest
  }
  
  def serverPeerConnections(): Unit = {
      // Test-Server is connected to client.yaasclient and superserver.yaassuperserver
      println("[TEST] Server Peer connections")
      val testServerPeers = getJson(s"${serverMetricsURL}/diameter/peers")
      if((testServerPeers \ "superserver.yaassuperserver" \ "status").extract[Int] == PeerStatus.STATUS_READY) ok("Connected to supersserver") else fail("Not connected to superserver") 
      if((testServerPeers \ "client.yaasclient" \ "status").extract[Int] == PeerStatus.STATUS_READY) ok("Connected to client") else fail("Not connected to client")
      nextTest
  }
  
  def superserverPeerConnections(): Unit = {
      // Test-SuperServer is connected to client.yaasclient and superserver.yaassuperserver
      println("[TEST] Superserver Peer connections")
      val testSuperServerPeers = getJson(s"${superServerMetricsURL}/diameter/peers")
      if((testSuperServerPeers \ "server.yaasserver" \ "status").extract[Int] == PeerStatus.STATUS_READY) ok("Connected to server") else fail("Not connected to server") 
      nextTest
  }
  
  /*
   * User-Name coding will determine the actions taken by the upstream servers
   * If domain contains
   * 	"reject" --> superserver replies with access-reject (auth)
   *  "drop" --> superserver drops the request (auth and acct)
   *  "clientdb" --> server looks for the username (without realm) in the client database (auth)
   *  "sessiondb" --> superserver stores or deletes the session in the session database (acct)
   * 
   * Radius client
   * -------------
   * 
   * 	group: all-servers
   * 		non-existing-server
   * 		test-server
   * 
   *  group: test-server
   *  	test-server
   *  
   *  Radius server
   *  -------------
   *  
   *  group: all-servers
   *  	non-existing-server
   *  	test-superserver
   *  
   *  group: test-superserver
   *  	test-superserver
   * 
   */
  
  /*
   * 1 Access-Request with Accept. Retried by the client (timeout from nes)
   * 1 Access-Request with Reject. Retried by the client (timeout from nes)
   * 1 Access-Request dropped. Retried by the client and the server (timeout from nes, timeout from server)
   * 
   * 1 Accounting with Response. Retried by the client (timeout from nes)
   * 1 Accounting to be dropped. Retried by the client and the server (timeout server)
   */
  
  def testAccessRequestWithAccept(): Unit = {
    println("[TEST] Access Request --> With accept")
    val userPassword = "The user-password!"
    val accessRequest = 
      RadiusPacket.request(ACCESS_REQUEST) << 
      ("User-Name" -> "user_1@clientdb.accept") << 
      ("User-Password" -> userPassword)
      
    // Will generate an unsuccessful request to "non-existing-server" and a successful request to yaasserver
    // Server echoes password
    sendRadiusGroupRequest("allServers", accessRequest, 1000, 1).onComplete {
      case Success(response) =>
        if(OctetOps.fromHexToUTF8(response >> "User-Password") != userPassword) fail("Password attribute is " + OctetOps.fromHexToUTF8(response >> "User-Password") + "!= " + userPassword)
        else if(response >>++ "Class" != "legacy_1") fail("Received class is " + (response >>++ "Class"))
        else {
          ok("Password attribute and class received correctly")
          nextTest
        }
      case Failure(ex) => 
        fail("Response not received")
        nextTest
    }
  }
  
  def testAccessRequestWithReject(): Unit = {
    println("[TEST] Access Request --> With reject")
    val accessRequest = 
      RadiusPacket.request(ACCESS_REQUEST) << 
      ("User-Name" -> "test@reject")
      
    // Will generate an unsuccessful request to "non-existing-server" and a successful request to yaasserver
    sendRadiusGroupRequest("allServers", accessRequest, 1000, 1).onComplete {
      case Success(response) => 
        if(response.code == RadiusPacket.ACCESS_REJECT){
          ok("Reject received correctly")
        } else fail("Response is not a reject")
        
        if((response >>++ "Reply-Message") == "The reply message!"){
          ok("Reply message is correct")
        } else fail("Response message is incorrect")
        nextTest
        
      case Failure(ex) => 
        fail("Response not received")
        nextTest
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
        nextTest
        
      case Failure(ex) => 
        ok("Response not received")
        nextTest
    }
  }
  
  def testAccountingRequest(): Unit = {
    
    // Accounting request
    println("[TEST] Accounting request")
    
    val ipAddress = "199.0.0.1"
    val acctSessionId = "radius-session-1"
    
    val accountingRequest= RadiusPacket.request(ACCOUNTING_REQUEST) << 
      ("User-Name" -> "test@sessiondb") <<
      ("Acct-Session-Id" -> acctSessionId) <<
      ("Framed-IP-Address" -> ipAddress) <<
      ("Acct-Status-Type" -> "Start") 
      
    // Will generate an unsuccessful request to "non-existing-server" and a successful request to yaasserver
    sendRadiusGroupRequest("allServers", accountingRequest, 2000, 1).onComplete {
      case Success(response) => 
        ok("Received response")
        
        // Find session
        val session = getJson(superServerSessionsURL + "/sessions/find?ipAddress=" + ipAddress)
        if(((session \ "acctSessionId")(0)).extract[String] == acctSessionId){
          ok("Session found")
        }
        else fail("Session not found")
        nextTest

      case Failure(ex) => 
        fail("Response not received")
        nextTest
    }
  }
  
  def testAccountingRequestWithDrop(): Unit = {
    println("[TEST] Accounting request with drop")
    // Generate another one to be discarded by the superserver. The servers re-sends the request to superserver
    sendRadiusGroupRequest("testServer", RadiusPacket.request(ACCOUNTING_REQUEST) << ("User-Name" -> "test@drop"), 500, 0).onComplete {
      case _ => nextTest
    }
  }
  
  // Diameter NASREQ application, AA request
  def testAA(): Unit = {
    println("[TEST] AA Requests")
    // Send AA Request with
    // Framed-Interface-Id to be echoed as one "Class" attribute
    // CHAP-Ident to be echoed as another "Class" attribute
    val sentFramedInterfaceId = "abcdef"
    val sentCHAPIdent = "abc"
    val chapAuthAVP: GroupedAVP =  ("CHAP-Auth", Seq()) <-- ("CHAP-Algorithm", "CHAP-With-MD5") <-- ("CHAP-Ident", sentCHAPIdent)
    
    val request = DiameterMessage.request("NASREQ", "AA")
    request << 
      "Destination-Realm" -> "yaasserver" <<
      ("Framed-Interface-Id" -> sentFramedInterfaceId)  <<
      chapAuthAVP
    
    sendDiameterRequest(request, 3000).onComplete{
      case Success(answer) =>
        // Check answer
        val classAttrs = answer >>+ "Class" map {avp => OctetOps.fromHexToUTF8(avp.toString)}
        if (classAttrs sameElements Seq(sentFramedInterfaceId, sentCHAPIdent)) ok("Received correct Class attributes") else fail(s"Incorrect Class Attributes: $classAttrs")
        nextTest
        
      case Failure(e) =>
        fail(e.getMessage)
        nextTest
    }
  }
  
  // Diameter NASREQ application, AC request
  def testAC(): Unit = {
    println("[TEST] AC Requests")
    
    val ipAddress = "200.0.0.1"
    val acctSessionId = "diameter-session-1"
    val userName = "user@sessiondb"
    
    val request = DiameterMessage.request("NASREQ", "AC")
    request << 
      "Destination-Realm" -> "yaasserver" << 
      "Session-Id" -> acctSessionId << 
      "Framed-IP-Address" -> ipAddress <<
      "User-Name" -> userName <<
      "Accounting-Record-Type" -> "START_RECORD"<<
      ("Tunneling" -> Seq(("Tunnel-Type" -> "L2TP"), ("Tunnel-Client-Endpoint" -> "my-tunnel-endpoint")))
    
    sendDiameterRequest(request, 3000).onComplete{
      case Success(answer) =>
        // Check answer
        if(avpCompare(answer >> "Result-Code", DiameterMessage.DIAMETER_SUCCESS)) ok("Received Success Result-Code") else fail("Not received success code")
        
        // Find session
        val session = getJson(superServerSessionsURL + "/sessions/find?acctSessionId=" + acctSessionId)
        if(((session \ "ipAddress")(0)).extract[String] == ipAddress){
          ok("Session found")
        }
        else fail("Session not found")
        nextTest

      case Failure(e) =>
        fail(e.getMessage)
        nextTest
    }
  }
  
  // Diameter Gx application
  // JSON generated
  // Routed from server to superserver (not proxied)
  // The super-server will reply with a Charging-Rule-Install -> Charging-Rule-Name containing the Subscription-Id-Data 
  def testGxRouting(): Unit = {
    
    println("[TEST] Gx Routing")
    
    val subscriptionId = "the-subscription-id"
    val gxRequest: DiameterMessage = 
      ("applicationId" -> "Gx") ~
      ("commandCode" -> "Credit-Control") ~
      ("isRequest" -> true) ~
      ("avps" ->
        ("Origin-Host" -> "client.yaasclient") ~
        ("Origin-Realm" -> "yaasclient") ~
        ("Destination-Realm" -> "yaassuperserver") ~
        ("Subscription-Id" ->
          ("Subscription-Id-Type" -> "EndUserIMSI") ~
          ("Subscription-Id-Data" -> subscriptionId)
        ) ~
        ("Framed-IP-Address" -> "1.2.3.4") ~
        ("Session-Id" -> "This-is-the-session-id")
      )
      
    sendDiameterRequest(gxRequest, 3000).onComplete{
      case Success(answer) =>
        // Check answer in binary format
        if(avpCompare(answer >> "Result-Code", DiameterMessage.DIAMETER_SUCCESS)) ok("Received Success Result-Code") else fail("Not received success code")
        
        // Check answer in JSON format
        val gxResponse: JValue = answer
        if(OctetOps.fromHexToUTF8((gxResponse \ "avps" \ "3GPP-Charging-Rule-Install" \ "3GPP-Charging-Rule-Name").extract[String]) == subscriptionId) ok("Received subscriptionId") else fail("Bad subscriptionId")
        nextTest
        
      case Failure(e) =>
        fail(e.getMessage)
        nextTest
    }
  }
   
  def checkSuperserverRadiusStats(): Unit = {

      println("[TEST] Superserver stats")

      // Requests received
      val jServerRequests = getJson(s"${superServerMetricsURL}/radius/metrics/radiusServerRequest")
      // 1 accept, 1 reject, 2 drop
      checkMetric(jServerRequests, 4, Map("rh" -> "127.0.0.1", "rq" -> "1"), "Access-Request received")
      // 1 acct ok, 2 acct drop
      checkMetric(jServerRequests, 3, Map("rh" -> "127.0.0.1", "rq" -> "4"), "Accounting-Request received")
 
      // Responses sent
      val jServerResponses = getJson(s"${superServerMetricsURL}/radius/metrics/radiusServerResponse")
      // 1 access accept
      checkMetric(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "2"), "Access-Accept sent")
      // 1 access reject
      checkMetric(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "3"), "Access-Reject sent")
      // 1 accounting response
      checkMetric(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "5"), "Accounting-Response sent")
      
      // Packets dropped by the server (not the handler)
      val jServerDrops = getJson(s"${superServerMetricsURL}/radius/metrics/radiusServerDropped")
      // No packets dropped. Stat not shown 
      checkMetric(jServerDrops, -1, Map(), "Packets dropped")
      
      // Packets answered by handler
      val jHandlerResponses = getJson(s"${superServerMetricsURL}/radius/metrics/radiusHandlerResponse?agg=rs")
      // 1 access accept
      checkMetric(jHandlerResponses, 1, Map("rs" -> "2"), "Access-Accept responses")
      // 1 access reject
      checkMetric(jHandlerResponses, 1, Map("rs" -> "3"), "Access-Reject responses")

      // Packets dropped by handler
      val jHandlerDrops = getJson(s"${superServerMetricsURL}/radius/metrics/radiusHandlerDropped?agg=rq")
      // 2 packet dropped each, since the server will retry to superserver
      checkMetric(jHandlerDrops, 2, Map("rq" -> "1"), "Access-Request dropped")
      checkMetric(jHandlerDrops, 2, Map("rq" -> "4"), "Accounting-Request dropped")
      
      nextTest
  }
  
  def checkServerRadiusStats(): Unit = {

      println("[TEST] Server stats")
      
      // Requests received
      val jServerRequests = getJson(s"${serverMetricsURL}/radius/metrics/radiusServerRequest")
      // 1 accept, 1 reject, 1 drop
      checkMetric(jServerRequests, 3, Map("rh" -> "127.0.0.1", "rq" -> "1"), "Access-Request received")
      // 1 acct ok, 1 acct drop
      checkMetric(jServerRequests, 2, Map("rh" -> "127.0.0.1", "rq" -> "4"), "Accounting-Request received")
 
      // Responses sent
      val jServerResponses = getJson(s"${serverMetricsURL}/radius/metrics/radiusServerResponse")
      // 1 access accept
      checkMetric(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "2"), "Access-Accept sent")
      // 1 access reject
      checkMetric(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "3"), "Access-Reject sent")
      // 1 accounting respone
      checkMetric(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "5"), "Accounting-Response sent")
      
      // Packets dropped by the server (not the handler)
      val jServerDrops = getJson(s"${serverMetricsURL}/radius/metrics/radiusServerDropped")
      // No packets dropped. Stat not shown 
      checkMetric(jServerDrops, -1, Map(), "Packets dropped")
      
      // Packets answered by handler
      val jHandlerResponses = getJson(s"${serverMetricsURL}/radius/metrics/radiusHandlerResponse?agg=rs")
      // 1 access accept
      checkMetric(jHandlerResponses, 1, Map("rs" -> "2"), "Access-Accept responses")
      // 1 access reject
      checkMetric(jHandlerResponses, 1, Map("rs" -> "3"), "Access-Reject responses")
      // 1 accounting response
      checkMetric(jHandlerResponses, 1, Map("rs" -> "5"), "Accounting responses")

      // Packets dropped by handler
      val jHandlerDrops = getJson(s"${serverMetricsURL}/radius/metrics/radiusHandlerDropped?agg=rq")
      // Server drops the packets for which it receives no response from non-existing-server
      checkMetric(jHandlerDrops, 1, Map("rq" -> "1"), "Access-Request dropped")
      checkMetric(jHandlerDrops, 1, Map("rq" -> "4"), "Accounting-Request dropped")
      
      nextTest
  }
  
  def checkClientRadiusStats(): Unit = {
      println("[TEST] Client stats")
      
      // 3 requests to the non-existing-server
      val jClientRequests1 = getJson(s"${clientMetricsURL}/radius/metrics/radiusClientRequest?agg=rh")
      checkMetric(jClientRequests1, 3, Map("rh" -> "1.1.1.1:1812"), "Requests sent to non existing server")
      
      // 3 access requests, 2 accounting requests to server
      val jClientRequests2 = getJson(s"${clientMetricsURL}/radius/metrics/radiusClientRequest?agg=rh,rq")
      checkMetric(jClientRequests2, 3, Map("rq" -> "1", "rh" -> "127.0.0.1:1812"), "Access-Requests sent to server")
      checkMetric(jClientRequests2, 2, Map("rq" -> "4", "rh" -> "127.0.0.1:1813"), "Acounting-Requests sent to server")
      
      // Responses received
      val jResponsesReceived = getJson(s"${clientMetricsURL}/radius/metrics/radiusClientResponse?agg=rs")
      checkMetric(jResponsesReceived, 1, Map("rs" -> "2"), "Access-Accept received from server")
      checkMetric(jResponsesReceived, 1, Map("rs" -> "3"), "Access-Reject received from server")
      checkMetric(jResponsesReceived, 1, Map("rs" -> "5"), "Accouning-Response received from server")
      
      // Timeouts
      val jTimeouts = getJson(s"${clientMetricsURL}/radius/metrics/radiusClientTimeout?agg=rh,rq")
      // One per each to non-existing-server
      checkMetric(jTimeouts, 3, Map("rq" -> "1", "rh" -> "1.1.1.1:1812"), "Access-Request timeouts from non existing server")
      // The one explicitly dropped
      checkMetric(jTimeouts, 1, Map("rq" -> "1", "rh" -> "127.0.0.1:1812"), "Access-Request timeouts from server")
      // Just one, in the first try
      checkMetric(jTimeouts, 1, Map("rq" -> "4", "rh" -> "1.1.1.1:1813"), "Accounting-Request timeouts from non existing server")
      // The one explicitly dropped
      checkMetric(jTimeouts, 1, Map("rq" -> "4", "rh" -> "127.0.0.1:1813"), "Accounting-Request timeouts from server")
      
      nextTest
  }
  
  def checkSuperserverDiameterStats(): Unit = {
      println("[TEST] Superserver stats")
      
      // Requests received
      val jRequestsReceived = getJson(s"${superServerMetricsURL}/diameter/metrics/diameterRequestReceived?agg=peer,ap,cm")
      // 1 AA, 1AC, 1CCR
      checkMetric(jRequestsReceived, 1, Map("peer" -> "server.yaasserver", "ap" -> "1", "cm" -> "265"), "NASREQ AAR received")
      checkMetric(jRequestsReceived, 1, Map("peer" -> "server.yaasserver", "ap" -> "1", "cm" -> "271"), "NASREQ ACR received")
      checkMetric(jRequestsReceived, 1, Map("peer" -> "server.yaasserver", "ap" -> "16777238", "cm" -> "272"), "Gx CCR received")

      // Ansers sent
      val jAnswerSent = getJson(s"${superServerMetricsURL}/diameter/metrics/diameterAnswerSent?agg=peer,ap,cm,rc")
      // 1 AA, 1AC
      checkMetric(jAnswerSent, 1, Map("peer" -> "server.yaasserver", "ap" -> "1", "cm" -> "265", "rc" -> "2001"), "NASREQ AAA sent")
      checkMetric(jAnswerSent, 1, Map("peer" -> "server.yaasserver", "ap" -> "1", "cm" -> "271", "rc" -> "2001"), "NASREQ ACA sent")
      checkMetric(jAnswerSent, 1, Map("peer" -> "server.yaasserver", "ap" -> "16777238", "cm" -> "272", "rc" -> "2001"), "Gx CCA sent")
      
      // Handled requests
      val jHandlerServer = getJson(s"${superServerMetricsURL}/diameter/metrics/diameterHandlerServer?agg=oh,dr,rc")
      // 1 AA, 1AC
      checkMetric(jHandlerServer, 2, Map("oh" -> "server.yaasserver", "dr" -> "yaassuperserver", "rc" -> "2001"), "AA/C Handled")
      // 1 CCR
      checkMetric(jHandlerServer, 1, Map("oh" -> "client.yaasclient", "dr" -> "yaassuperserver", "rc" -> "2001"), "Gx CCR Handled")
      
      val jHandlerClient = getJson(s"${superServerMetricsURL}/diameter/metrics/diameterHandlerClient?agg=oh")
      // 1 AA, 1AC
      checkMetric(jHandlerClient, -1, Map("oh" -> "server.yaasserver"), "AA Handled")
      
      nextTest
  }
  
  def checkServerDiameterStats(): Unit = {
      println("[TEST] Server stats")
      
      val jRequestsReceived = getJson(s"${serverMetricsURL}/diameter/metrics/diameterRequestReceived?agg=peer,ap")
      // 1 AA, 1AC
      checkMetric(jRequestsReceived, 2, Map("peer" -> "client.yaasclient", "ap" -> "1"), "NASREQ requests received")
      // 1 Gx CCR
      checkMetric(jRequestsReceived, 1, Map("peer" -> "client.yaasclient", "ap" -> "16777238"), "Gx requests received")
      
      val jRequestsSent = getJson(s"${serverMetricsURL}/diameter/metrics/diameterRequestSent?agg=peer,ap")
      // 1 AA, 1AC
      checkMetric(jRequestsSent, 2, Map("peer" -> "superserver.yaassuperserver", "ap" -> "1"), "NASREQ requests sent")
      // 1 Gx CCR
      checkMetric(jRequestsSent, 1, Map("peer" -> "superserver.yaassuperserver", "ap" -> "16777238"), "Gx requests sent")
      
      val jAnswersReceived = getJson(s"${serverMetricsURL}/diameter/metrics/diameterAnswerReceived?agg=ap")
      // 1 AA, 1AC
      checkMetric(jAnswersReceived, 2, Map("ap" -> "1"), "NASREQ answers received")
      // Gx CCA
      checkMetric(jAnswersReceived, 1, Map("ap" -> "16777238"), "Gx answers received")

      val jAnswerSent = getJson(s"${serverMetricsURL}/diameter/metrics/diameterAnswerSent?agg=ap,rc")
      // 1 AA, 1AC
      checkMetric(jAnswerSent, 2, Map("ap" -> "1", "rc" -> "2001"), "NASREQ answers sent")
      // 1 CCR
      checkMetric(jAnswerSent, 1, Map("ap" -> "16777238", "rc" -> "2001"), "Gx answers sent")
      
      val jHandlerServer = getJson(s"${serverMetricsURL}/diameter/metrics/diameterHandlerServer?agg=oh,ap")
      // 1 AA, 1AC
      checkMetric(jHandlerServer, 2, Map("oh" -> "client.yaasclient", "ap" -> "1"), "AA Handled")
      // 0 Gx
      checkMetric(jHandlerServer, -1, Map("oh" -> "client.yaasclient", "ap" -> "16777238"), "AA Handled")
      
      val jHandlerClient = getJson(s"${serverMetricsURL}/diameter/metrics/diameterHandlerClient?agg=oh,ap,rc")
      // 1 AA, 1AC
      checkMetric(jHandlerClient, 2, Map("oh" -> "server.yaasserver", "ap" -> "1", "rc" -> "2001"), "AA Handled")
      
      nextTest
  }
  
  def checkRadiusPerformance(requestType: Int, domain: String, nRequests: Int, nThreads: Int, testName: String)(): Unit = {
    
    println(s"[Test] RADIUS Performance. $testName")
    
    val serverGroup = "testServer"
    
    val startTime = System.currentTimeMillis()
    
    // Number of requests being performed
    var i = new java.util.concurrent.atomic.AtomicInteger(0)
    
    // Each thread does this
    def requestLoop: Future[Unit] = {
      
      val promise = Promise[Unit]()
      
      def loop: Unit = {
        val reqIndex = i.getAndIncrement
        print("\r" +  reqIndex)
        if(reqIndex < nRequests){
          // The server will echo the User-Password. We'll test this
          val password = "test-password!"
          val accessRequest = 
            RadiusPacket.request(requestType) << 
            ("User-Name" -> ("user_"+ (reqIndex % 1000) + domain)) << 
            ("User-Password" -> password) <<
            ("Acct-Session-Id" -> ("session-" + testName + "-" + reqIndex)) <<
            ("Acct-Status-Type" -> "Start") <<
            ("Framed-IP-Address" -> intToIPAddress(reqIndex))
            
          sendRadiusGroupRequest(serverGroup, accessRequest, 3000, 1).onComplete {
            case Success(response) =>
              if(response.code == ACCOUNTING_RESPONSE|| (response.code == RadiusPacket.ACCESS_ACCEPT && OctetOps.fromHexToUTF8(response >>++ "User-Password") == password)) loop
              else promise.failure(new Exception("Bad Radius Response"))
              
            case Failure(e) =>
              promise.failure(e)
          }
        } else promise.success(Unit)
      }
      
      loop
      promise.future
    }
    
    // Launch the threads
    val requesters = List.fill(nThreads)(requestLoop)
    Future.reduce(requesters)((a, b) => Unit).onComplete {
      case Success(v) =>
        val total = i.get
        if(total < nRequests) fail(s"Not completed. Got $total requests") 
        else{
          // Print results
          print("\r")
          val totalTime = System.currentTimeMillis() - startTime
          ok(s"$nRequests in ${totalTime} milliseconds, ${1000 * nRequests / totalTime} per second")
        }
        nextTest
        
      case Failure(e) =>
        fail(e.getMessage)
        nextTest
    }
  }
  
  def checkDiameterPerformance(requestType: String, domain: String, nRequests: Int, nThreads: Int, testName: String)(): Unit = {
    
    println(s"[Test] Diameter Performance. $testName")
    
    val startTime = System.currentTimeMillis()
    
    // Number of requests being performed
    var i = new java.util.concurrent.atomic.AtomicInteger(0)
    
    // Each thread does this
    def requestLoop: Future[Unit] = {
      
      val promise = Promise[Unit]()
      
      def loop: Unit = {
        val reqIndex = i.getAndIncrement
        print("\r" +  reqIndex)
        if(reqIndex < nRequests){
          
          if(requestType == "AA"){
            // Send AA Request with
            // Framed-Interface-Id to be echoed as one "Class" attribute
            // CHAP-Ident to be echoed as another "Class" attribute
            val sentFramedInterfaceId = "abcdef"
            val sentCHAPIdent = "abc"
            val chapAuthAVP: GroupedAVP =  ("CHAP-Auth", Seq()) <-- ("CHAP-Algorithm", "CHAP-With-MD5") <-- ("CHAP-Ident", sentCHAPIdent)
            
            val request = DiameterMessage.request("NASREQ", "AA")
            request << 
              "Destination-Realm" -> "yaasserver" <<
              ("Framed-Interface-Id" -> sentFramedInterfaceId)  <<
              chapAuthAVP
            
            sendDiameterRequest(request, 5000).onComplete{
              case Success(answer) =>
                // Check answer
                val classAttrs = answer >>+ "Class" map {avp => OctetOps.fromHexToUTF8(avp.toString)}
                if (classAttrs sameElements Seq(sentFramedInterfaceId, sentCHAPIdent)) loop
                else promise.failure(new Exception("Bad answer"))
                
              case Failure(e) =>
                promise.failure(e)
            }
          } 
          else {
            val ipAddress = "200.0.0.1"
            val acctSessionId = "diameter-session-1"
            val userName = "user_" + (reqIndex % 1000) + domain
            
            val request = DiameterMessage.request("NASREQ", "AC")
            request << 
              "Destination-Realm" -> "yaasserver" << 
              "Session-Id" -> acctSessionId << 
              "Framed-IP-Address" -> ipAddress <<
              "User-Name" -> userName <<
              "Accounting-Record-Type" -> "START_RECORD"<<
              ("Tunneling" -> Seq(("Tunnel-Type" -> "L2TP"), ("Tunnel-Client-Endpoint" -> "my-tunnel-endpoint")))
            
            sendDiameterRequest(request, 5000).onComplete{
              case Success(answer) =>
                // Check answer
                if(avpCompare(answer >> "Result-Code", DiameterMessage.DIAMETER_SUCCESS)) loop
                else promise.failure(new Exception("Bad answer"))
        
              case Failure(e) =>
                promise.failure(e)
            }
          }
        } else promise.success(Unit)
      }
      
      loop
      promise.future
    }
    
    // Launch the threads
    val requesters = List.fill(nThreads)(requestLoop)
    Future.reduce(requesters)((a, b) => Unit).onComplete {
      case Success(v) =>
        val total = i.get
        if(total < nRequests) fail(s"Not completed. Got $total requests") 
        else{
          // Print results
          print("\r")
          val totalTime = System.currentTimeMillis() - startTime
          ok(s"$nRequests in ${totalTime} milliseconds, ${1000 * nRequests / totalTime} per second")
        }
        nextTest
        
      case Failure(e) =>
        fail(e.getMessage)
        nextTest
    }
  }
}