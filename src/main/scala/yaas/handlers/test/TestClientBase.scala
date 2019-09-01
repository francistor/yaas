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
import yaas.config.ConfigManager

import scala.util.Try
import scala.util.{Success, Failure}

import yaas.server.MessageHandler

import scala.concurrent._
import scala.concurrent.duration._

import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpMethods._
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._


trait JsonSupport extends Json4sSupport {
  implicit val serialization = org.json4s.jackson.Serialization
  implicit val json4sFormats = org.json4s.DefaultFormats
}

abstract class TestClientBase(metricsServer: ActorRef, configObject: Option[String]) extends MessageHandler(metricsServer, configObject) with JsonSupport {
  
  log.info("Instantiated Radius/Diameter client application")
  
  val nRequests = Option(System.getenv("YAAS_TEST_REQUESTS")).orElse(Option(System.getProperty("YAAS_TEST_REQUESTS"))).map(req => Integer.parseInt(req)).getOrElse(10000)
  val doLoop = Option(System.getenv("YAAS_TEST_LOOP")).orElse(Option(System.getProperty("YAAS_TEST_LOOP"))).map(_.toBoolean).getOrElse(false)

  implicit val materializer = ActorMaterializer()
  val http = Http(context.system)
  
  //////////////////////////////////////////////////////////////////////////////
  // Helper functions
  
  def wait[T](r: Awaitable[T]) = Await.result(r, 10 second)
  
  def intToIPAddress(i: Int) = {
    val bytes = Array[Byte]((i >> 24).toByte, (i >> 16).toByte, (i >> 8).toByte, (i % 8).toByte)
    java.net.InetAddress.getByAddress(bytes).getHostAddress
  }
  
  def jsonFromGet(url: String) = {
    wait(for {
      r <- http.singleRequest(HttpRequest(uri = url))
      j <- Unmarshal(r.entity).to[JValue].recover{case _ => JNothing}
    } yield j)
  }
  
  def jsonFromPostJson(url: String, json: String) = {
    wait(for {
      r <- http.singleRequest(HttpRequest(POST, uri = url, entity = HttpEntity(ContentTypes.`application/json`, json)))
      j <- Unmarshal(r.entity).to[JValue].recover{case _ => JNothing}
    } yield j)
  }
  
  def jsonFromPostJsonWithErrorTrace(url: String, json: String) = {
    wait(for {
      r <- http.singleRequest(HttpRequest(POST, uri = url, entity = HttpEntity(ContentTypes.`application/json`, json)))
      dummy = if(r.status.intValue() != 200) println(s"$url got ${r.status}")
      j <- Unmarshal(r.entity).to[JValue].recover{case _ => JNothing}
    } yield j)
  }
  
  def codeFromPostJson(url: String, json: String) = {
    wait(for {
      r <- http.singleRequest(HttpRequest(POST, uri = url, entity = HttpEntity(ContentTypes.`application/json`, json)))
    } yield r.status.intValue())
  }
  
  def codeFromDelete(url: String) = {
     wait(for {
      r <- http.singleRequest(HttpRequest(DELETE, uri = url))
    } yield r.status.intValue())   
  }
  
  def patchJson(url: String, json: String) = {
    wait(for {
      r <- http.singleRequest(HttpRequest(PATCH, uri = url, entity = HttpEntity(ContentTypes.`application/json`, json)))
    } yield r.status.intValue())
  }
  
  def ok(msg: String = "") = println(s"\t[OK] $msg")
  
  def fail(msg: String = "") = println(s"\t[FAIL] $msg")
  
  // Returns -1 on error
  def getCounterForKey(stats: JValue, keyMap: Map[String, String]) = {
    val out = for {
      JArray(statValues) <- stats
      statValue <- statValues
      JInt(counterValue) <- statValue \ "value" if((statValue \ "keyMap" diff keyMap) == Diff(JNothing, JNothing, JNothing))
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
  val iamBaseURL : String
  val iamSecondaryBaseURL : String
  
  val includingNeRadiusGroup : String
  val allServersRadiusGroup : String
  
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
      if(doLoop){
        println("LOOP")
        // Assuming at least one test is defined
        lastTestIdx = 0
        tests(0)()
      } 
      else {
        println("FINISHED")
        System.exit(0)
      }
    }
  }
  
  
  /////////////////////////////////////////////////////////////////////////
  // Test functions
  /////////////////////////////////////////////////////////////////////////
  
  def sleep(millis: Int)(): Unit = {
    Thread.sleep(millis)
    nextTest
  }
  
  // Finishes the tests because does not call nextText
  def stop(): Unit = {
    println("Tests stopped")
    System.exit(0);
  }
  
  def checkConnectedPeer(url: String, connectedPeer: String)(): Unit = {
      println(s"[TEST] $url connected to $connectedPeer")
      val peerStatus = jsonFromGet(s"${url}/diameter/peers")
      if((peerStatus \ connectedPeer \ "status").extract[Int] == PeerStatus.STATUS_READY) ok(s"Connected to $connectedPeer") else fail(s"Not connected to $connectedPeer") 
      nextTest
  }
  
  def checkNotConnectedPeer(url: String, notConnectedPeer: String)(): Unit = {
      println(s"[TEST] $url connected to notConnectedPeer")
      val peerStatus = jsonFromGet(s"${url}/diameter/peers")
      if((peerStatus \ notConnectedPeer \ "status").extract[Int] != PeerStatus.STATUS_READY) ok(s"$notConnectedPeer status is != 2") else fail(s"Connected to $notConnectedPeer") 
      nextTest
  }
  
  def clientPeerConnections(): Unit = {
      // Test-Client is connected to server.yaasserver, and not connected to non-existing-server.yaasserver
      println("[TEST] Client Peer connections")
      val testClientPeers = jsonFromGet(s"${clientMetricsURL}/diameter/peers")
      if((testClientPeers \ "server.yaasserver" \ "status").extract[Int] == PeerStatus.STATUS_READY) ok("Connected to server") else fail("Not connected to server") 
      if((testClientPeers \ "non-existing-server.yaasserver" \ "status").extract[Int] != PeerStatus.STATUS_READY) ok("non-existing-server status is != 2") else fail("Connected to non-existing-server!") 
      nextTest
  }
  
  def serverPeerConnections(): Unit = {
      // Test-Server is connected to client.yaasclient and superserver.yaassuperserver
      println("[TEST] Server Peer connections")
      val testServerPeers = jsonFromGet(s"${serverMetricsURL}/diameter/peers")
      if((testServerPeers \ "superserver.yaassuperserver" \ "status").extract[Int] == PeerStatus.STATUS_READY) ok("Connected to supersserver") else fail("Not connected to superserver") 
      if((testServerPeers \ "client.yaasclient" \ "status").extract[Int] == PeerStatus.STATUS_READY) ok("Connected to client") else fail("Not connected to client")
      nextTest
  }
  
  def superserverPeerConnections(): Unit = {
      // Test-SuperServer is connected to client.yaasclient and superserver.yaassuperserver
      println("[TEST] Superserver Peer connections")
      val testSuperServerPeers = jsonFromGet(s"${superServerMetricsURL}/diameter/peers")
      if((testSuperServerPeers \ "server.yaasserver" \ "status").extract[Int] == PeerStatus.STATUS_READY) ok("Connected to server") else fail("Not connected to server") 
      nextTest
  }
  
  /*
   * User-Name coding will determine the actions taken by the upstream servers
   *  login-name
   *  	may contain "accept", "reject" or "drop" to force the corresponding action on the superserver
   *    may contain "nosession" to avoid storing the session in Ignite
   *    
   *  realm
   *    "database" to do lookup in database
   *    "file" to do lookup in file
   */
  
  /*
   * 1 Access-Request with Accept. Retried by the client (timeout from nes)
   * 1 Access-Request with Reject. Retried by the client (timeout from nes)
   * 1 Access-Request dropped. Retried by the client and the server (timeout from nes, timeout from server)
   * 
   * 1 Accounting with Response. Retried by the client (timeout from nes)
   * 1 Accounting to be dropped. Retried by the client and the server (timeout server)
   */
  
  /**
   *  Sends Access-Request to "yaas-server-ne-group", the first one of which does not respond, and expects to receive
   * 	User-Password = password!_1
   *  Class = legacy_1
   *  
   *  The implementing server looks-up in the database using nasport:nasipaddress
   */
  def testAccessRequestWithAccept(): Unit = {
    println("[TEST] Access Request --> With accept")
    val userPassword = "password!_0"
    val lcId = "legacy_0"
    val serviceName = "service_0"
    
    val accessRequest = 
      RadiusPacket.request(ACCESS_REQUEST) << 
      ("User-Name" -> "user_1@database") << 
      ("User-Password" -> userPassword) << 
      ("NAS-IP-Address" -> "1.1.1.1") <<
      ("NAS-Port" -> 0) <<
      ("Acct-Session-Id" -> "radius-session-0")
      
    // Will generate an unsuccessful request to "non-existing-server" and a successful request to yaasserver
    // Server echoes password
    sendRadiusGroupRequest(includingNeRadiusGroup, accessRequest, 1000, 1).onComplete {
      case Success(response) =>
        // Class
        val classAttributes = (response >>+ "Class").map(avp => avp.stringValue)
        if(classAttributes.contains(s"C=$lcId")) ok(s"C=$lcId found") else fail(s"Class C=$lcId not found")
        if(classAttributes.contains(s"S=$serviceName")) ok(s"S=$serviceName found") else fail(s"Class S=$serviceName not found")
        // Echoed password
        if(OctetOps.fromHexToUTF8(response >> "User-Password") != userPassword) 
          fail("Password attribute is " + OctetOps.fromHexToUTF8(response >> "User-Password") + "!= " + userPassword)
        else {
          ok("Password attribute and class received correctly")
        }
        nextTest
      case Failure(ex) => 
        fail("Response not received")
        nextTest
    }
  }
  
  def testAccessRequestWithReject(): Unit = {
    println("[TEST] Access Request --> With reject")
    val accessRequest = 
      RadiusPacket.request(ACCESS_REQUEST) << 
      ("User-Name" -> "reject@file") <<
      ("User-Password" -> "mypassword") <<
      ("NAS-IP-Address" -> "1.1.1.1") <<
      ("NAS-Port" -> 0) <<
      ("Acct-Session-Id" -> "radius-session-reject")
      
      
    // Will generate an unsuccessful request to "non-existing-server" and a successful request to yaasserver
    sendRadiusGroupRequest(includingNeRadiusGroup, accessRequest, 1000, 1).onComplete {
      case Success(response) => 
        if(response.code == RadiusPacket.ACCESS_REJECT){
          ok("Reject received correctly")
        } else fail("Response is not a reject")
        
        if((response >>++ "Reply-Message") == "Proxy: Rejected by superserver!"){
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
      ("User-Name" -> "drop@file") <<
      ("User-Password" -> "mypassword") <<
      ("NAS-IP-Address" -> "1.1.1.1") <<
      ("NAS-Port" -> 0) <<
      ("Acct-Session-Id" -> "radius-session-drop")
      
    // Will generate an unsuccessful request to "non-existing-server". Yaasserver will also send it twice to supserserver
    sendRadiusGroupRequest(includingNeRadiusGroup, accessRequest, 1500, 1).onComplete {
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
    val acctSessionId = "radius-session-0"
    
    val sAccountingRequest = s"""
      {
        "code": 4,
        "avps": {
          "NAS-IP-Address": ["1.1.1.1"],
          "NAS-Port": [0],
          "User-Name": ["test@database"],
          "Acct-Session-Id": ["${acctSessionId}"],
          "Framed-IP-Address": ["${ipAddress}"],
          "Acct-Status-Type": ["Start"]
        }
      }
      """
      
    val accountingRequest: RadiusPacket = parse(sAccountingRequest)

    // Will generate an unsuccessful request to "non-existing-server" and a successful request to yaasserver
    sendRadiusGroupRequest(includingNeRadiusGroup, accountingRequest, 2000, 1).onComplete {
      case Success(response) => 
        ok("Received response")
        
        // Find session
        val session = jsonFromGet(superServerSessionsURL + "/sessions/find?ipAddress=" + ipAddress)
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
  
  def testAccountingInterim(): Unit = {
    
    // Accounting request
    println("[TEST] Accounting Interim")
    
    val ipAddress = "199.0.0.1"
    val acctSessionId = "radius-session-0"
    
    val sAccountingRequest = s"""
      {
        "code": 4,
        "avps": {
          "NAS-IP-Address": ["1.1.1.1"],
          "NAS-Port": [0],
          "User-Name": ["test@database"],
          "Acct-Session-Id": ["${acctSessionId}"],
          "Framed-IP-Address": ["${ipAddress}"],
          "Acct-Status-Type": ["Interim-Update"]
        }
      }
      """
          
    val accountingRequest: RadiusPacket = parse(sAccountingRequest)

    // Will go directly to the server
    sendRadiusGroupRequest(allServersRadiusGroup, accountingRequest, 2000, 1).onComplete {
      case Success(response) => 
        ok("Received response")
        
        // Find session
        val session = jsonFromGet(superServerSessionsURL + "/sessions/find?ipAddress=" + ipAddress)
        if((session(0) \ "acctSessionId").extract[String] == acctSessionId){
          ok("Session found")
        }
        else fail("Session not found")

        if((session(0) \ "data" \ "interim").extract[Boolean] == true){
          ok("Updated data found")
        }
        else fail("Updated data found")
        
        nextTest

      case Failure(ex) => 
        fail("Response not received")
        nextTest
    }
  }
  
  def testAccountingRequestWithDrop(): Unit = {
    println("[TEST] Accounting request with drop")
    
    val accountingRequest= RadiusPacket.request(ACCOUNTING_REQUEST) << 
      ("NAS-IP-Address" -> "1.1.1.1") <<
      ("NAS-Port" -> 9999) <<
      ("User-Name" -> "drop@file") <<
      ("Acct-Session-Id" -> "dropped-session-id") <<
      ("Framed-IP-Address" -> "199.0.0.2") <<
      ("Acct-Status-Type" -> "Start")  
      
    // Generate another one to be discarded by the superserver. The servers re-sends the request to superserver
    sendRadiusGroupRequest(allServersRadiusGroup, accountingRequest, 500, 0).onComplete {
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
    val userName = "sessiondb@file"
    
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
        val session = jsonFromGet(superServerSessionsURL + "/sessions/find?acctSessionId=" + acctSessionId)
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
      val jServerRequests = jsonFromGet(s"${superServerMetricsURL}/radius/metrics/radiusServerRequest")
      // 1 accept, 1 reject, 2 drop
      checkMetric(jServerRequests, 4, Map("rh" -> "127.0.0.1", "rq" -> "1"), "Access-Request received")
      // 1 acct ok, 2 acct drop
      checkMetric(jServerRequests, 3, Map("rh" -> "127.0.0.1", "rq" -> "4"), "Accounting-Request received")
 
      // Responses sent
      val jServerResponses = jsonFromGet(s"${superServerMetricsURL}/radius/metrics/radiusServerResponse")
      // 1 access accept
      checkMetric(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "2"), "Access-Accept sent")
      // 1 access reject
      checkMetric(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "3"), "Access-Reject sent")
      // 1 accounting response
      checkMetric(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "5"), "Accounting-Response sent")
      
      // Packets dropped by the server (not the handler)
      val jServerDrops = jsonFromGet(s"${superServerMetricsURL}/radius/metrics/radiusServerDropped")
      // No packets dropped. Stat not shown 
      checkMetric(jServerDrops, -1, Map(), "Packets dropped")
      
      // Packets answered by handler
      val jHandlerResponses = jsonFromGet(s"${superServerMetricsURL}/radius/metrics/radiusHandlerResponse?agg=rs")
      // 1 access accept
      checkMetric(jHandlerResponses, 1, Map("rs" -> "2"), "Access-Accept responses")
      // 1 access reject
      checkMetric(jHandlerResponses, 1, Map("rs" -> "3"), "Access-Reject responses")

      // Packets dropped by handler
      val jHandlerDrops = jsonFromGet(s"${superServerMetricsURL}/radius/metrics/radiusHandlerDropped?agg=rq")
      // 2 packet dropped each, since the server will retry to superserver
      checkMetric(jHandlerDrops, 2, Map("rq" -> "1"), "Access-Request dropped")
      checkMetric(jHandlerDrops, 2, Map("rq" -> "4"), "Accounting-Request dropped")
      
      nextTest
  }
  
  def checkServerRadiusStats(): Unit = {

      println("[TEST] Server stats")
      
      // Requests received
      val jServerRequests = jsonFromGet(s"${serverMetricsURL}/radius/metrics/radiusServerRequest")
      // 1 accept, 1 reject, 1 drop
      checkMetric(jServerRequests, 3, Map("rh" -> "127.0.0.1", "rq" -> "1"), "Access-Request received")
      // 1 acct ok, 1 acct drop
      checkMetric(jServerRequests, 2, Map("rh" -> "127.0.0.1", "rq" -> "4"), "Accounting-Request received")
 
      // Responses sent
      val jServerResponses = jsonFromGet(s"${serverMetricsURL}/radius/metrics/radiusServerResponse")
      // 1 access accept
      checkMetric(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "2"), "Access-Accept sent")
      // 1 access reject
      checkMetric(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "3"), "Access-Reject sent")
      // 1 accounting respone
      checkMetric(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "5"), "Accounting-Response sent")
      
      // Packets dropped by the server (not the handler)
      val jServerDrops = jsonFromGet(s"${serverMetricsURL}/radius/metrics/radiusServerDropped")
      // No packets dropped. Stat not shown 
      checkMetric(jServerDrops, -1, Map(), "Packets dropped")
      
      // Packets answered by handler
      val jHandlerResponses = jsonFromGet(s"${serverMetricsURL}/radius/metrics/radiusHandlerResponse?agg=rs")
      // 1 access accept
      checkMetric(jHandlerResponses, 1, Map("rs" -> "2"), "Access-Accept responses")
      // 1 access reject
      checkMetric(jHandlerResponses, 1, Map("rs" -> "3"), "Access-Reject responses")
      // 1 accounting response
      checkMetric(jHandlerResponses, 1, Map("rs" -> "5"), "Accounting responses")

      // Packets dropped by handler
      val jHandlerDrops = jsonFromGet(s"${serverMetricsURL}/radius/metrics/radiusHandlerDropped?agg=rq")
      // Server drops the packets for which it receives no response from non-existing-server
      checkMetric(jHandlerDrops, 1, Map("rq" -> "1"), "Access-Request dropped")
      checkMetric(jHandlerDrops, 1, Map("rq" -> "4"), "Accounting-Request dropped")
      
      nextTest
  }
  
  def checkClientRadiusStats(): Unit = {
      println("[TEST] Client stats")
      
      // 3 requests to the non-existing-server
      val jClientRequests1 = jsonFromGet(s"${clientMetricsURL}/radius/metrics/radiusClientRequest?agg=rh")
      checkMetric(jClientRequests1, 3, Map("rh" -> "1.1.1.1:1812"), "Requests sent to non existing server")
      
      // 3 access requests, 2 accounting requests to server
      val jClientRequests2 = jsonFromGet(s"${clientMetricsURL}/radius/metrics/radiusClientRequest?agg=rh,rq")
      checkMetric(jClientRequests2, 3, Map("rq" -> "1", "rh" -> "127.0.0.1:1812"), "Access-Requests sent to server")
      checkMetric(jClientRequests2, 2, Map("rq" -> "4", "rh" -> "127.0.0.1:1813"), "Acounting-Requests sent to server")
      
      // Responses received
      val jResponsesReceived = jsonFromGet(s"${clientMetricsURL}/radius/metrics/radiusClientResponse?agg=rs")
      checkMetric(jResponsesReceived, 1, Map("rs" -> "2"), "Access-Accept received from server")
      checkMetric(jResponsesReceived, 1, Map("rs" -> "3"), "Access-Reject received from server")
      checkMetric(jResponsesReceived, 1, Map("rs" -> "5"), "Accouning-Response received from server")
      
      // Timeouts
      val jTimeouts = jsonFromGet(s"${clientMetricsURL}/radius/metrics/radiusClientTimeout?agg=rh,rq")
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
      val jRequestsReceived = jsonFromGet(s"${superServerMetricsURL}/diameter/metrics/diameterRequestReceived?agg=peer,ap,cm")
      // 1 AA, 1AC, 1CCR
      checkMetric(jRequestsReceived, 1, Map("peer" -> "server.yaasserver", "ap" -> "1", "cm" -> "265"), "NASREQ AAR received")
      checkMetric(jRequestsReceived, 1, Map("peer" -> "server.yaasserver", "ap" -> "1", "cm" -> "271"), "NASREQ ACR received")
      checkMetric(jRequestsReceived, 1, Map("peer" -> "server.yaasserver", "ap" -> "16777238", "cm" -> "272"), "Gx CCR received")

      // Ansers sent
      val jAnswerSent = jsonFromGet(s"${superServerMetricsURL}/diameter/metrics/diameterAnswerSent?agg=peer,ap,cm,rc")
      // 1 AA, 1AC
      checkMetric(jAnswerSent, 1, Map("peer" -> "server.yaasserver", "ap" -> "1", "cm" -> "265", "rc" -> "2001"), "NASREQ AAA sent")
      checkMetric(jAnswerSent, 1, Map("peer" -> "server.yaasserver", "ap" -> "1", "cm" -> "271", "rc" -> "2001"), "NASREQ ACA sent")
      checkMetric(jAnswerSent, 1, Map("peer" -> "server.yaasserver", "ap" -> "16777238", "cm" -> "272", "rc" -> "2001"), "Gx CCA sent")
      
      // Handled requests
      val jHandlerServer = jsonFromGet(s"${superServerMetricsURL}/diameter/metrics/diameterHandlerServer?agg=oh,dr,rc")
      // 1 AA, 1AC
      checkMetric(jHandlerServer, 2, Map("oh" -> "server.yaasserver", "dr" -> "yaassuperserver", "rc" -> "2001"), "AA/C Handled")
      // 1 CCR
      checkMetric(jHandlerServer, 1, Map("oh" -> "client.yaasclient", "dr" -> "yaassuperserver", "rc" -> "2001"), "Gx CCR Handled")
      
      val jHandlerClient = jsonFromGet(s"${superServerMetricsURL}/diameter/metrics/diameterHandlerClient?agg=oh")
      // 1 AA, 1AC
      checkMetric(jHandlerClient, -1, Map("oh" -> "server.yaasserver"), "AA Handled")
      
      nextTest
  }
  
  def checkServerDiameterStats(): Unit = {
      println("[TEST] Server stats")
      
      val jRequestsReceived = jsonFromGet(s"${serverMetricsURL}/diameter/metrics/diameterRequestReceived?agg=peer,ap")
      // 1 AA, 1AC
      checkMetric(jRequestsReceived, 2, Map("peer" -> "client.yaasclient", "ap" -> "1"), "NASREQ requests received")
      // 1 Gx CCR
      checkMetric(jRequestsReceived, 1, Map("peer" -> "client.yaasclient", "ap" -> "16777238"), "Gx requests received")
      
      val jRequestsSent = jsonFromGet(s"${serverMetricsURL}/diameter/metrics/diameterRequestSent?agg=peer,ap")
      // 1 AA, 1AC
      checkMetric(jRequestsSent, 2, Map("peer" -> "superserver.yaassuperserver", "ap" -> "1"), "NASREQ requests sent")
      // 1 Gx CCR
      checkMetric(jRequestsSent, 1, Map("peer" -> "superserver.yaassuperserver", "ap" -> "16777238"), "Gx requests sent")
      
      val jAnswersReceived = jsonFromGet(s"${serverMetricsURL}/diameter/metrics/diameterAnswerReceived?agg=ap")
      // 1 AA, 1AC
      checkMetric(jAnswersReceived, 2, Map("ap" -> "1"), "NASREQ answers received")
      // Gx CCA
      checkMetric(jAnswersReceived, 1, Map("ap" -> "16777238"), "Gx answers received")

      val jAnswerSent = jsonFromGet(s"${serverMetricsURL}/diameter/metrics/diameterAnswerSent?agg=ap,rc")
      // 1 AA, 1AC
      checkMetric(jAnswerSent, 2, Map("ap" -> "1", "rc" -> "2001"), "NASREQ answers sent")
      // 1 CCR
      checkMetric(jAnswerSent, 1, Map("ap" -> "16777238", "rc" -> "2001"), "Gx answers sent")
      
      val jHandlerServer = jsonFromGet(s"${serverMetricsURL}/diameter/metrics/diameterHandlerServer?agg=oh,ap")
      // 1 AA, 1AC
      checkMetric(jHandlerServer, 2, Map("oh" -> "client.yaasclient", "ap" -> "1"), "AA Handled")
      // 0 Gx
      checkMetric(jHandlerServer, -1, Map("oh" -> "client.yaasclient", "ap" -> "16777238"), "AA Handled")
      
      val jHandlerClient = jsonFromGet(s"${serverMetricsURL}/diameter/metrics/diameterHandlerClient?agg=oh,ap,rc")
      // 1 AA, 1AC
      checkMetric(jHandlerClient, 2, Map("oh" -> "server.yaasserver", "ap" -> "1", "rc" -> "2001"), "AA Handled")
      
      nextTest
  }
  
  def checkHttpStats(method: String,  mustBe: Int)(): Unit = {
    println(s"[TEST] Http Stats")
    
    val jHttpRequests = jsonFromGet(s"${superServerMetricsURL}/http/metrics/httpOperation?agg=method")
    checkMetric(jHttpRequests, mustBe, Map("method" -> method), s"$method")
    
    nextTest
  }
  
  def checkQueueStats(): Unit = {
    println(s"[TEST] Queue Stats")
    
    val jRadiusQueueStats = jsonFromGet(s"${serverMetricsURL}/radius/metrics/radiusClientQueueSize")
    if(jRadiusQueueStats == JNothing) fail("Radius Queue Stats did not return a valid value") else ok("Radius Queue Stats -> " + compact(jRadiusQueueStats))
    
    val jDiameterQueueStats = jsonFromGet(s"${serverMetricsURL}/diameter/metrics/diameterPeerQueueSize")
    if(jDiameterQueueStats == JNothing) fail("Diameter Queue Stats did not return a valid value") else ok("Diameter Queue Stats -> " + compact(jDiameterQueueStats))
    
    nextTest
  }
  
  def checkSessionStats(): Unit = {
    println(s"[TEST] Session Stats")
    
    val jSessionGroups = jsonFromGet(s"${superServerMetricsURL}/session/metrics/sessionGroups")
    if(jSessionGroups == JNothing) fail("sessionGroups did not return a valid value") else ok("Session Stats  -> " + compact(jSessionGroups))
    
    nextTest
  }
  
  def checkRadiusPerformance(serverGroup: String, requestType: Int, acctStatusType: String, domain: String, nRequests: Int, nThreads: Int, testName: String)(): Unit = {
    
    println(s"[TEST] RADIUS Performance. $testName")
    
    val startTime = System.currentTimeMillis()
    
    // Number of requests being performed
    var i = new java.util.concurrent.atomic.AtomicInteger(0)
    
    // Each thread does this
    def requestLoop: Future[Unit] = {
      
      val promise = Promise[Unit]()
      
      def loop: Unit = {
        val reqIndex = i.getAndIncrement
        val index = reqIndex % 1000
        print(s"\r${reqIndex} ")
        if(reqIndex < nRequests){
          // The server will echo the User-Password. We'll test this
          val password = s"password!_$index"
          val radiusPacket = 
            RadiusPacket.request(requestType) <<
            ("NAS-IP-Address" -> "1.1.1.1") <<
            ("NAS-Port" -> index) <<
            ("User-Name" -> ("radius_user_"+ index + domain)) << 
            ("User-Password" -> password) <<
            ("Acct-Session-Id" -> ("radiusPerformance-" + reqIndex)) <<
            ("Framed-IP-Address" -> intToIPAddress(reqIndex))
            
          if(requestType == RadiusPacket.ACCOUNTING_REQUEST) radiusPacket << ("Acct-Status-Type" -> acctStatusType) 
            
          sendRadiusGroupRequest(serverGroup, radiusPacket, 3000, 1).onComplete {
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
  
  def checkDiameterPerformance(requestType: String, domain: String, acctStatusType: String, nRequests: Int, nThreads: Int, testName: String)(): Unit = {
    
    println(s"[TEST] Diameter Performance. $testName")
    
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
            val userName = "user_" + (reqIndex % 10000) + domain
            
            val request = DiameterMessage.request("NASREQ", "AC")
            request << 
              "Destination-Realm" -> "yaasserver" << 
              "Session-Id" -> ("diameterPerformance-" + reqIndex) << 
              "Framed-IP-Address" -> intToIPAddress(5000000 + reqIndex) <<
              "User-Name" -> ("diameter_user_"+ i + "_" + domain) <<
              "Accounting-Record-Type" -> acctStatusType <<
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
  
  
  ////////////////////////////////////////////////////////////////////////////////////
  // Helpers for IPAM
  ////////////////////////////////////////////////////////////////////////////////////
  def createPool(poolId: String) = {
    val jPool: JValue = ("poolId" -> poolId)
    val retCode = codeFromPostJson(iamBaseURL + "/pool", compact(jPool))
    if(retCode == 201) ok(s"$poolId created") else fail(s"Error creating Pool. Got $retCode")
  }
  
  def createPoolSelector(selectorId: String, poolId: String, priority: Int) = {
    val jPoolSelector: JValue = ("selectorId" -> selectorId) ~ ("poolId" -> poolId) ~ ("priority" -> priority) 
    val retCode = codeFromPostJson(iamBaseURL + "/poolSelector", compact(jPoolSelector))
    if(retCode == 201) ok(s"PoolSelector $selectorId,$poolId created") else fail(s"Error creating PoolSelector. Got $retCode")
  }
  
  def createRange(poolId: String, startIPAddress: Int, endIPAddress: Int, status: Int) = {
    val jRange: JValue = ("poolId" -> poolId) ~ ("startIPAddress" -> startIPAddress) ~ ("endIPAddress" -> endIPAddress) ~ ("status" -> status) 
    val retCode = codeFromPostJson(iamBaseURL + "/range", compact(jRange))
    if(retCode == 201) ok(s"Range $poolId,$startIPAddress created") else fail(s"Error creating Range. Got $retCode")
  }
  
  def deletePool(poolId: String) = {
    val retCode = codeFromDelete(iamBaseURL + "/pool/" + poolId)
    if(retCode == 202) ok(s"$poolId deleted") else fail(s"Error deleting Pool. Got $retCode")
  }
  
  def deletePoolSelector(selectorId: String, poolId: String) = {
    val retCode = codeFromDelete(iamBaseURL + "/poolSelector/" + selectorId + "," + poolId)
    if(retCode == 202) ok(s"$selectorId,$poolId deleted") else fail(s"Error deleting PoolSelector. Got $retCode")
  }
  
  def deleteRange(poolId: String, startIPAddress: Int) = {
    val retCode = codeFromDelete(iamBaseURL + "/range/" + poolId + "," + startIPAddress)
    if(retCode == 202) ok(s"$poolId,$startIPAddress deleted") else fail(s"Error deleting Range. Got $retCode")
  }
  
  ////////////////////////////////////////////////////////////////////////////////////
  
  def factorySettings () : Unit = {
    
    var retCode: Int = 0
    
    println("\n[RESET TO FACTORY SETTINGS]")
    
    retCode = codeFromPostJson(iamBaseURL + "/factorySettings", "{}")
    if(retCode == 201) ok("Reset to factory settings") else fail(s"Response code: $retCode")
    
    nextTest
  }
  
  /**
   * 
   */
  def createPools(): Unit = {
    
    println("\n[CREATE POOLS]")
    
    createPool("pool-1-republica")
    createPool("pool-2-republica")
    createPool("pool-1-cuyo")
    createPool("pool-2-cuyo")
    createPool("small-pool")
    
    nextTest
  }
  
  /**
   * 
   */
  def createPoolSelectors() : Unit = {
    
    println("\n[CREATE POOLSELECTORS]")
    
    createPoolSelector("Republica", "pool-1-republica", 1)
    createPoolSelector("Republica", "pool-2-republica", 2)
    createPoolSelector("Cuyo", "pool-1-cuyo", 1)
    createPoolSelector("Cuyo", "pool-2-cuyo", 2)
    createPoolSelector("Small", "small-pool", 1)
    
    nextTest
  }
  
  def createRanges() : Unit = {
    
    println("\n[CREATE RANGES]")
    
    createRange("pool-1-republica", 10000, 10999, 1)
    createRange("pool-1-republica", 11000, 11999, 1)
    createRange("pool-2-republica", 12000, 12999, 1)
    createRange("pool-2-republica", 13000, 13999, 1)
    createRange("pool-1-cuyo", 2000, 2099, 1)
    createRange("pool-1-cuyo", 2100, 2199, 1)
    createRange("pool-2-cuyo", 2200, 2299, 1)
    createRange("pool-2-cuyo", 2300, 2399, 1)
    createRange("small-pool", 9000, 9002, 1)
    
    nextTest

  }
  
  def deleteRanges(): Unit = {
    println("\n[DELETE RANGES]")
    
    createRange("pool-2-cuyo", 8000, 8100, 1)
    deleteRange("pool-2-cuyo", 8000)
    
    nextTest
  }
  
  def deletePoolSelectors(): Unit = {
    println("\n[DELETE POOLSELECTORS]")
    
    createPoolSelector("disposable", "pool-2-cuyo", 3)
    deletePoolSelector("disposable", "pool-2-cuyo")
    
    nextTest
  }
  
  def deletePools(): Unit = {
    println("\n[DELETE POOLS]")
    
    createPool("disposable")
    deletePool("disposable")
    
    nextTest
  }
  
  def errorConditions(): Unit = {
    println("\n[FAILURE CONDITIONS]")
    
    // Create range for undefined pools returns error 409
    var jRange: JValue = ("poolId" -> "undefined-pool") ~ ("startIPAddress" -> 1) ~ ("endIPAddress" -> 2) ~ ("status" -> 1) 
    var retCode = codeFromPostJson(iamBaseURL + "/range", compact(jRange))
    if(retCode == 409) ok(s"Range with undefined pool rejected") else fail(s"Range with undefined pool got $retCode")
    
    // Create pool selector for undefined pool returns error 409
    var jPoolSelector: JValue = ("selectorId" -> "myselector") ~ ("poolId" -> "undefined-pool") ~ ("priority" -> 1) 
    retCode = codeFromPostJson(iamBaseURL + "/poolSelector", compact(jPoolSelector))
    if(retCode == 409) ok(s"PoolSelector with undefined pool rejected") else fail(s"PoolSelector with undefined pool got $retCode")
    
    // Pool already existing returns error 409
    var jPool: JValue = ("poolId" -> "pool-1-republica")
    retCode = codeFromPostJson(iamBaseURL + "/pool", compact(jPool))
    if(retCode == 409) ok(s"Duplicated pool rejected") else fail(s"Duplicated pool got $retCode")
    
    // Try to remove pool in use returns error 409
    retCode = codeFromDelete(iamBaseURL + "/pool" + "/pool-1-republica")
    if(retCode == 409) ok(s"Deletion of pool in use is rejected") else fail(s"Deletion of pool in use got $retCode")
    
    // PoolSelector already existing returns error 409
    jPoolSelector = ("selectorId" -> "Republica") ~ ("poolId" -> "pool-1-republica") ~ ("priority" -> 1) 
    retCode = codeFromPostJson(iamBaseURL + "/poolSelector", compact(jPoolSelector))
    if(retCode == 409) ok(s"Duplicate PoolSelector rejected") else fail(s"Duplicate PoolSelector got $retCode")
    
    // Range already existing returns error 409
    jRange = ("poolId" -> "pool-1-cuyo") ~ ("startIPAddress" -> 2000) ~ ("endIPAddress" -> 2098) ~ ("status" -> 1) 
    retCode = codeFromPostJson(iamBaseURL + "/range", compact(jRange))
    if(retCode == 409) ok(s"Duplicate Range rejected") else fail(s"Duplicated Range got $retCode")
    
    // Conflicting returns error 409
    jRange = ("poolId" -> "pool-1-cuyo") ~ ("startIPAddress" -> 10100) ~ ("endIPAddress" -> 10200) ~ ("status" -> 1) 
    retCode = codeFromPostJson(iamBaseURL + "/range", compact(jRange))
    if(retCode == 409) ok(s"Overlapping Range rejected") else fail(s"Overlapping Range got $retCode")
    
    // Delete unexisting range returns 404
    retCode = codeFromDelete(iamBaseURL + "/range/nonpool,1")
    if(retCode == 404) ok(s"Delete non existing range returns not-found") else fail(s"Delete non existing ranges returns $retCode")
    
    nextTest
  }
  
  def fillPool(): Unit = {
    println("\n[INSERTING LEASES IN small-pool]")
    
    if(codeFromPostJson(iamBaseURL + "/fillPoolLeases/small-pool", "{}") == 200) ok(s"Pool filled") else fail(s"Error while filling pool") 
    
    nextTest
  }
  
  def reloadLookup(): Unit = {
     jsonFromPostJson(iamBaseURL + "/reloadLookup", "{}")
     jsonFromPostJson(iamSecondaryBaseURL + "/reloadLookup", "{}")
     
     nextTest
  }
  
  def testLeases(): Unit = {
    println("\n[LEASES]")
    
    // Getting addresses from createRange("small-pool", 9000, 9002, 1)
    
    // Ask for three IP addresses. Check that the results are 9000, 9001 and 9002
    val addresses = for {
      i <- 1 to 3
      JInt(addr) = jsonFromPostJson(iamBaseURL + "/lease?selectorId=Small&requester=req1", "{}") \ "ipAddress"
    } yield addr.toInt
    
    
    if(addresses.sorted.sameElements(List(9000, 9001, 9002))) ok("Got addresses 9000, 9001 and 9002") else fail("Got bad addresses")

    // Next request has to return 420
    var code = codeFromPostJson(iamBaseURL + "/lease?selectorId=Small", "{}")
    if(code == 420) ok("No more IP addresses available") else fail(s"Got $code when leasing address from pool that should be full")
    
    // Free one of them
    code = codeFromPostJson(iamBaseURL + "/release?ipAddress=9001", "{}")
    if(code == 200) ok("Address released") else fail(s"Address not released. Got $code")
    
    // Re-lease it again, after waiting ONE SECOND (grace time should be configured accordingly)
    Thread.sleep(1100)
    val JInt(newAddr) = jsonFromPostJson(iamBaseURL + "/lease?selectorId=Small", "{}") \ "ipAddress"
    if(newAddr == 9001) ok("Address leased again") else fail(s"Address NOT leased again. Got $code")
    
    // Renew
    code = codeFromPostJson(iamBaseURL + "/renew?ipAddress=9000&requester=req1", "{}")
    if(code == 200) ok("Address renewed") else fail(s"Address NOT renewed. Got $code")
    
    // Renew with failure
    code = codeFromPostJson(iamBaseURL + "/renew?ipAddress=9001&requester=fake", "{}")
    if(code == 404) ok("Renewal disallowed") else fail(s"Renewal with failure got $code")
    
    nextTest
  }
  
  def testBulkLease(): Unit = {
    
    println("\n[BULK LEASE]")
    
    val nAddresses = 4000
    val nThreads = 10
    
    val startTime = System.currentTimeMillis()
    
    // Number of requests being performed
    var i = new java.util.concurrent.atomic.AtomicInteger(0)
    
    // To be executed by one thread
    def requestLoop() = {
      var total = 0
      var currIndex = 0
      while({currIndex = i.getAndIncrement; if(currIndex < nAddresses) true else false})
      {
        print(s"\r${currIndex} ")
        val base = if(currIndex % 2 == 0) iamBaseURL else iamSecondaryBaseURL
        Try(jsonFromPostJsonWithErrorTrace(base + "/lease?selectorId=Republica", "{}") \ "ipAddress") match {
          
          case Success(JInt(ipAddr)) =>
            total = total + 1
            
          case Success(_) =>
            // Try again
            fail("Did not get an IP address. Try again")
            
          case Failure(e) =>
            // Try again
            fail(e.getMessage)
        }
      }
      total
    }
    
    // Accumulate the results of each requestLoop
    val requests = List.fill(nThreads)(Future {requestLoop})
    Future.fold(requests)(0)((acc, res) => (acc + res)).onComplete {
      case Success(total) => 
        val elapsedTime = System.currentTimeMillis() - startTime
        val rate = (nAddresses * 1000) / elapsedTime
        if(total == nAddresses) ok(s"Got $total leases. Rate: ${rate} leases per second") else fail(s"Got $total instead of $nAddresses")
        nextTest
        
      case Failure(e) => 
        fail(e.getMessage)
        nextTest
    }
  }
  
  def unavailableLease(): Unit = {
    
    // Ask for a lease after bulklease has exhausted all IP addresses
    // Must get 420 code
    val code = codeFromPostJson(iamBaseURL + "/lease?selectorId=Republica", "{}")
    if(code == 420) ok("Addreses for selector not available") else fail("Got an address from an exhausted pool")
        
    nextTest
  }
  
  
  ////////////////////////////////////////////////////////////////////////////////////
  // JScript testing
  ////////////////////////////////////////////////////////////////////////////////////
  /*
  // JS usage example

	// the baseURL object will contain the location of the base script 
  load(baseURL + "/tests.js");
  
  var acctSessionId = "acct-session-id-1";
  var ipAddress = "199.0.0.1";
  var request = {
  	"code": 4,
  	"avps": {
  	  "NAS-IP-Address": "1.1.1.1",
  	  "NAS-Port": 1,
  	  "User-Name": "test@database",
  	  "Acct-Session-Id": acctSessionId,
  	  "Framed-IP-Address": ipAddress,
  	  "Acct-Status-Type": "Start"
  	}
  }
  
  Yaas.radiusRequest(allServersRadiusGroup, JSON.stringify(request), 2000, 1, function(err, response){
  	if(err){
  		print("There was an error.\n" + err.message);
  	}
  	else {
  		print("Response received.\n");
  		print(response);
  	}
  	
  	Notifier.end();
  });
  
  // Print something, but the test will not be finished until Notifier.end() is called
  print("Radius request sent\n");
*/

  def runJS(scriptName: String)(): Unit = {
    
    class Notifier {
      def end = nextTest
    }
    
    import javax.script._
    
    // Instantiate
    val engine = new ScriptEngineManager().getEngineByName("nashorn");
    
    // Put objects in scope
    // Radius/Diameter/HTTP helper
  	engine.put("Yaas", YaasJS)
  	
  	// Base location of the script
  	val scriptURL = ConfigManager.getObjectURL(scriptName).getPath.toString
  	engine.put("baseURL", scriptURL.substring(0, scriptURL.indexOf(scriptName)))
  	
  	// To signal finalization
  	// JScript will invoke Notifier.end
  	engine.put("Notifier", new Notifier)
  	
  	// Excecute Javascript
  	engine.eval(ConfigManager.readObject(scriptName));
  	
  }
}