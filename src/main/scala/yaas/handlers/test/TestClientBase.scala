package yaas.handlers.test

import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch.MessageDispatcher
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import org.json4s.JsonDSL._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization
import yaas.coding.DiameterConversions._
import yaas.coding.RadiusConversions._
import yaas.coding.RadiusPacket._
import yaas.coding._
import yaas.config.{ConfigManager, _}
import yaas.server.MessageHandler
import yaas.util.OctetOps

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

// TODO: Test group metrics

trait JsonSupport extends Json4sSupport {
  implicit val serialization: Serialization.type = org.json4s.jackson.Serialization
  implicit val json4sFormats: DefaultFormats.type = org.json4s.DefaultFormats
}
/* Library for creating tests scenarios.
 * Derives from MessageHandler
 * @param metricsServer. In order to run it, simply declare it as a hahdler in handlers.json
 * <code>{"name": "Main1", "clazz": "yaas.handlers.test.TestClientMain", "config":"deep.js"}</code>
 *
 * @param metricsServer
 * @param configObject typically used to pass the name of a .js file with radius/diameter policies
 */
abstract class TestClientBase(metricsServer: ActorRef, configObject: Option[String]) extends MessageHandler(metricsServer, configObject) with JsonSupport {

  implicit val actorSystem: ActorSystem = context.system
  private val http = Http(context.system)

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // To be overridden in implementing classes
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  val tests : IndexedSeq[() => Unit]

  /**
   * Base URL
   */
  val clientMetricsURL : String
  val serverMetricsURL : String
  val superServerMetricsURL : String
  val sessionsURL : String
  val iamBaseURL : String
  val iamSecondaryBaseURL : String

  val includingNeRadiusGroup : String
  val allServersRadiusGroup : String

  /**
   * Number of requests on each iteration
   */
  val nRequests: Int

  /**
   * Whether to run in a loop
   */
  val doLoop: Boolean

  /**
   * Do not stop if there was a timeout when executing performance testing
   */
  val continueOnPerfError: Boolean

  /**
   * Whether to exit the application after finishing the tests
   */
  val exitOnTermination: Boolean

  /**
   * Number of threads sending parallel requests
   */
  val nThreads: Int

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Helpers
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Prints an OK message
   * @param msg message to print
   */
  private def ok(msg: String = ""): Unit = println(s"\t[OK] $msg")

  /**
   * Prints a failure message
   * @param msg message to print
   */
  private def fail(msg: String = ""): Unit = println(s"\t[FAIL] $msg")

  /**
   * Helper to get property from java properties or environment variables
   * @param property name of the property
   * @param defaultValue if not found
   * @return found or default
   */
  def getIntFromEnv(property: String, defaultValue: Int): Int = {
    Option(System.getenv(property)).orElse(Option(System.getProperty(property))).map(v => Integer.parseInt(v)).getOrElse(defaultValue)
  }

  /**
   * Helper to get property from java properties or environment variables
   * @param property name of the property
   * @param defaultValue if not found
   * @return found or default
   */
  def getBooleanFromEnv(property: String, defaultValue: Boolean): Boolean = {
    Option(System.getenv(property)).orElse(Option(System.getProperty(property))).map(_.toBoolean).getOrElse(defaultValue)
  }

  /**
   *
   * @param stats The json object received from the instrumentation server
   * @param keyMap The full key whose value is to be retrieved, as a map of keys to values
   * @return -1 in case of key not found
   */
  private def getCounterForKey(stats: JValue, keyMap: Map[String, String]) = {
    val out = for {
      JArray(statValues) <- stats
      statValue <- statValues
      JInt(counterValue) <- statValue \ "value" if (statValue \ "keyMap" diff keyMap) == Diff(JNothing, JNothing, JNothing)
    } yield counterValue

    if(out.isEmpty) -1 else out.head.toInt
  }

  /**
   * Checks a JSON with metrics against a target
   * @param jMetric the value to check
   * @param targetValue the value of the metric that would be OK
   * @param key a map of label names to values, specifying the key to check in jMetric
   * @param legend to print in the output message
   */
  private def checkMetric(jMetric: JValue, targetValue: Long, key: Map[String, String], legend: String): Unit = {
    val counter = getCounterForKey(jMetric, key)
    if(targetValue == counter) ok(s"$legend : $counter") else fail(s"$legend : $counter expected $targetValue")
  }
  
  private def waitJValue(r: Future[JValue]) = Await.result(r,  5.second)
  private def waitInt(r: Future[Int]) = Await.result(r, 5.second)

  // To simplify the management of IP addresses, we treat them sometimes as simple integers
  private def intToIPAddress(i: Int) = {
    val bytes = Array[Byte]((i >> 24).toByte, (i >> 16).toByte, (i >> 8).toByte, (i % 8).toByte)
    java.net.InetAddress.getByAddress(bytes).getHostAddress
  }

  /**
   * Wrappers of http requests
   */
  private def jsonFromGet(url: String) = {
    waitJValue(for {
      r <- http.singleRequest(HttpRequest(uri = url))
      j <- Unmarshal(r.entity).to[JValue].recover{case _ => JNothing}
    } yield j
    )
  }

  private def codeFromGet(url: String): Int = {
    waitInt(for {
      r <- http.singleRequest(HttpRequest(uri = url))
    } yield r.status.intValue
    )
  }
  
  private def jsonFromPostJson(url: String, json: String) = {
    waitJValue(for {
      r <- http.singleRequest(HttpRequest(POST, uri = url, entity = HttpEntity(ContentTypes.`application/json`, json)))
      j <- Unmarshal(r.entity).to[JValue].recover{case _ => JNothing}
    } yield j)
  }
  
  private def jsonFromPostJsonWithErrorTrace(url: String, json: String) = {
    waitJValue(for {
      r <- http.singleRequest(HttpRequest(POST, uri = url, entity = HttpEntity(ContentTypes.`application/json`, json)))
      _ = if(r.status.intValue() != 200) println(s"$url got ${r.status}")
      j <- Unmarshal(r.entity).to[JValue].recover{case _ => JNothing}
    } yield j)
  }
  
  private def codeFromPostJson(url: String, json: String) = {
    waitInt(for {
      r <- http.singleRequest(HttpRequest(POST, uri = url, entity = HttpEntity(ContentTypes.`application/json`, json)))
    } yield r.status.intValue())
  }
  
  private def codeFromDelete(url: String) = {
     waitInt(for {
      r <- http.singleRequest(HttpRequest(DELETE, uri = url))
    } yield r.status.intValue())   
  }

  private def codeAndMessageFromDelete(url: String) = {
    val fut = for {
      r <- http.singleRequest(HttpRequest(DELETE, uri = url))
      msg <- Unmarshal(r.entity).to[String].recover{case _ => "ERROR"}
    } yield (r.status.intValue(), msg)

    Await.result(fut, 5.seconds)
  }

  private def codeFromPatchJson(url: String, json: String) = {
    waitInt(for {
      r <- http.singleRequest(HttpRequest(PATCH, uri = url, entity = HttpEntity(ContentTypes.`application/json`, json)))
    } yield r.status.intValue())
  }

  
  //////////////////////////////////////////////////////////////////////////////

  
  // Wait some time before starting the tests.
  // peerCheckTimeSeconds should be configured with about 10 seconds. Starting the tests after
  // 15 seconds will give some time to retry connections that will have initially failed due 
  // to all servers starting at almost the same time
  override def preStart: Unit = {
    context.system.scheduler.scheduleOnce(3.seconds, self, "Start")
  }
  
  // To receive the start message
  override def receive: Receive = {
    case "Start" =>
      // Start testing
      nextTest()
      
    case message: Any => 
      super.receive(message)
  }
  
  // _ is needed to promote the method (no arguments) to a function
  println("STARTING TESTS")
  private var lastTestIdx = -1

  /**
   * Executes the current test item
   */
  def nextTest(): Unit = {
    lastTestIdx = lastTestIdx + 1
    if(tests.length > lastTestIdx)
      try{
        tests(lastTestIdx)()
      }
      catch{
        case t: Throwable => println(s">> ERROR ${t.getMessage}")
      }
    else {
      if(doLoop){
        println("LOOP")
        // Assuming at least one test is defined
        lastTestIdx = 0
        tests(0)()
      } 
      else {
        if(exitOnTermination) System.exit(0) else println("FINISHED")
      }
    }
  }
  
  
  /////////////////////////////////////////////////////////////////////////
  // Test functions
  /////////////////////////////////////////////////////////////////////////

  /**
   * Snooze for some milliseconds
   * @param millis sleep time
   */
  def sleep(millis: Int)(): Unit = {
    Thread.sleep(millis)
    nextTest()
  }

  /**
   * Finishes the tests because does not call nextText
   */
  def stop()(): Unit = {
    println("Tests stopped")
  }

  private def checkPeerConnectionStatus(url: String, peer: String, shouldBeConnected: Boolean, testHint: String)(): Unit = {
    println(s"[TEST] $testHint connection status with $peer")
    val peerStatus = jsonFromGet(s"$url/diameter/peers")
    val isReady = (peerStatus \ peer \ "status").extract[Int] == PeerStatus.STATUS_READY
    val msg = if(isReady) "Connected" else "Not Connected"
    if(shouldBeConnected == isReady) ok(msg) else fail(msg)
    nextTest()
  }

  def checkClientConnectedPeer(peer: String)(): Unit = checkPeerConnectionStatus(clientMetricsURL, peer, shouldBeConnected = true, "Client")
  def checkClientNotConnectedPeer(peer: String)(): Unit = checkPeerConnectionStatus(clientMetricsURL, peer, shouldBeConnected = false, "Client")
  def checkServerConnectedPeer(peer: String)(): Unit = checkPeerConnectionStatus(serverMetricsURL, peer, shouldBeConnected = true, "Server")
  def checkServerNotConnectedPeer(peer: String)(): Unit = checkPeerConnectionStatus(serverMetricsURL, peer, shouldBeConnected = false, "Server")
  def checkSuperServerConnectedPeer(peer: String)(): Unit = checkPeerConnectionStatus(superServerMetricsURL, peer, shouldBeConnected = true, "SuperServer")
  def checkSuperServerNotConnectedPeer(peer: String)(): Unit = checkPeerConnectionStatus(superServerMetricsURL, peer, shouldBeConnected = false, "SuperServer")

  /*
   * User-Name coding will determine the actions taken by the upstream servers
   *  login-name
   *  	may contain "accept", "reject" or "drop" to force the corresponding action on the superserver
   *    may contain "nosession" to avoid storing the session in Ignite
   *    
   *  realm
   *    "database" to do lookup in database
   *    "file" to do lookup in file
   *
   *  Notice the <code>conf/test-server/handlerConf</code> configuration files, where the realm "database" does
   *  check the password and the realm "file" does not.
   */
  
  /*
   * The following set of methods are meant to be executed in order in the test class, since
   * nes = non existing server
   * 1 Access-Request with Accept. Retried by the client (timeout from nes)
   * 1 Access-Request with Reject. Retried by the client (timeout from nes)
   * 1 Access-Request dropped. Retried by the client and the server (timeout from nes, timeout from server)
   * 
   * 1 Accounting with Response. Retried by the client (timeout from nes)
   * 1 Accounting to be dropped. Retried by the client and the server (timeout server)
   */
  
  /**
   *  Sends Access-Request to "yaas-server-ne-group", the first one of which does not respond, and expects to receive
   * 	User-Password = password!_0
   *  Class = legacy_0
   *
   *  The users are provisioned in <code>InitClientDatabase</code> handler
   *  The implementing server looks-up in the database using nasport:nasipaddress
   */
  def testAccessRequestWithAccept(): Unit = {
    println("[TEST] Access Request --> With accept")
    val userName = "user_0@database"
    val userPassword = "password!_0"
    val lcId = "legacy_0"
    val serviceName = "service_0"
    val session_id = "radius-session-0"
    val nasIPAddress = "1.1.1.1"
    
    val accessRequest = 
      RadiusPacket.request(ACCESS_REQUEST) << 
      ("User-Name" -> userName) <<
      ("User-Password" -> userPassword) << 
      ("NAS-IP-Address" -> nasIPAddress) <<
      ("NAS-Port" -> 0) <<
      ("Acct-Session-Id" -> session_id)
      
    // Will generate an unsuccessful request to "non-existing-server" and a successful request to yaasserver
    // Server echoes password
    sendRadiusGroupRequest(includingNeRadiusGroup, accessRequest, 1000, 1).onComplete {
      case Success(response) =>
        // Verify class attribute
        val classAttributes = (response >>+ "Class").map(avp => avp.stringValue)
        if(classAttributes.contains(s"C=$lcId")) ok(s"C=$lcId found") else fail(s"Class C=$lcId not found")
        if(classAttributes.contains(s"S=$serviceName")) ok(s"S=$serviceName found") else fail(s"Class S=$serviceName not found")
        // Verify Echoed password
        if(OctetOps.fromHexToUTF8(response >> "User-Password") != userPassword) 
          fail("Password attribute is " + OctetOps.fromHexToUTF8(response >> "User-Password") + "!= " + userPassword)
        else {
          ok("Password attribute received correctly")
        }

        nextTest()
      case Failure(_) =>
        fail("Response not received")
        nextTest()
    }
  }

  /**
   * Sends an AccessRequest to be rejected by superserver. The first try is directed to the non existing server.
   *
   * The Reply-Message is verified
   */
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

        if((response >>* "Reply-Message") == "Proxy: Rejected by superserver!"){
          ok("Reply message is correct")
        } else fail("Response message is incorrect")
        nextTest()
        
      case Failure(_) =>
        fail("Response not received")
        nextTest()
    }
  }

  /**
   * Generates an AccessRequest that will be dropped by superserver
   */
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
        nextTest()
        
      case Failure(_) =>
        ok("Response not received")
        nextTest()
    }
  }

  /**
   * Generates an accounting request using JSON coding
   */
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
          "Acct-Session-Id": ["$acctSessionId"],
          "Framed-IP-Address": ["$ipAddress"],
          "Acct-Status-Type": ["Start"]
        }
      }
      """

    // Convert JSON to RadiusPacket
    val accountingRequest: RadiusPacket = parse(sAccountingRequest)

    // Will generate an unsuccessful request to "non-existing-server" and a successful request to yaasserver
    sendRadiusGroupRequest(includingNeRadiusGroup, accountingRequest, 2000, 1).onComplete {
      case Success(_) =>
        ok("Received response")
        
        // Find session
        val session = jsonFromGet(sessionsURL + s"/sessions/find?ipAddress=$ipAddress")
        if((session \ "acctSessionId")(0).extract[String] == acctSessionId){
          ok("Session found")
        }
        else fail("Session not found")
        nextTest()

      case Failure(_) =>
        fail("Response not received")
        nextTest()
    }
  }

  /**
   * Use the same values for ipAddress and acctSessionId as in the previous packet
   */
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
          "Acct-Session-Id": ["$acctSessionId"],
          "Framed-IP-Address": ["$ipAddress"],
          "Acct-Status-Type": ["Interim-Update"]
        }
      }
      """
          
    val accountingRequest: RadiusPacket = parse(sAccountingRequest)

    // Will go directly to the server. Non existing server not used here
    sendRadiusGroupRequest(allServersRadiusGroup, accountingRequest, 2000, 1).onComplete {
      case Success(response) => 
        ok("Received response")
        
        // Find session. Wait a little if the update is async
        Thread.sleep(500)
        val session = jsonFromGet(sessionsURL + "/sessions/find?ipAddress=" + ipAddress)
        if((session(0) \ "acctSessionId").extract[String] == acctSessionId) ok("Session found") else fail("Session not found")

        if((session(0) \ "data" \ "interim").extract[Boolean]) ok("Updated data found")  else fail("Updated data not found")
        if((session(0) \ "data" \ "b").extract[Int] == 2) ok("Old data found")  else fail("Old data not found")
        nextTest()

      case Failure(ex) => 
        fail("Response not received")
        nextTest()
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
      
    // Generate another request to be discarded by the superserver.
    // The servers re-sends the request to superserver
    sendRadiusGroupRequest(allServersRadiusGroup, accountingRequest, 500, 0).onComplete(_ => nextTest())
  }

  /**
   * Diameter NASREQ application, AA request
   */
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
        val classAttrs = (answer >>+ "Class").map {avp => OctetOps.fromHexToUTF8(avp.toString)}
        // TODO: The ordering could be different
        if (classAttrs == Seq(sentFramedInterfaceId, sentCHAPIdent)) ok("Received correct Class attributes") else fail(s"Incorrect Class Attributes: $classAttrs")
        nextTest()
        
      case Failure(e) =>
        fail(e.getMessage)
        nextTest()
    }
  }

  /**
   * Diameter NASREQ application, AC request
   */
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
      "Accounting-Record-Type" -> "START_RECORD" <<
      ("Tunneling" -> Seq("Tunnel-Type" -> "L2TP", "Tunnel-Client-Endpoint" -> "my-tunnel-endpoint"))
    
    sendDiameterRequest(request, 3000).onComplete{
      case Success(answer) =>
        // Check answer
        if(answer.L("Result-Code") == DiameterMessage.DIAMETER_SUCCESS) ok("Received Success Result-Code") else fail("Not received success code")
        
        // Find session
        val session = jsonFromGet(sessionsURL + s"/sessions/find?acctSessionId=$acctSessionId")
        if((session \ "ipAddress")(0).extract[String] == ipAddress){
          ok("Session found")
        }
        else fail("Session not found")
        nextTest()

      case Failure(e) =>
        fail(e.getMessage)
        nextTest()
    }
  }

  /**
   * Diameter Gx application
   * JSON generated
   * Routed from server to superserver (not proxied)
   * The super-server will reply with a Charging-Rule-Install -> Charging-Rule-Name containing the Subscription-Id-Data
   */

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
        if(answer.L("Result-Code") == DiameterMessage.DIAMETER_SUCCESS) ok("Received Success Result-Code") else fail("Not received success code")
        
        // Check answer in JSON format
        val gxResponse: JValue = answer
        val receivedSubscriptionId = OctetOps.fromHexToUTF8((gxResponse \ "avps" \ "3GPP-Charging-Rule-Install" \ "3GPP-Charging-Rule-Name").extract[String])
        if(receivedSubscriptionId == subscriptionId) ok("Received subscriptionId") else fail(s"Bad subscriptionId $receivedSubscriptionId")
        nextTest()
        
      case Failure(e) =>
        fail(e.getMessage)
        nextTest()
    }
  }
   
  def checkSuperserverRadiusStats(): Unit = {

      println("[TEST] Superserver stats")

      // Requests received
      val jServerRequests = jsonFromGet(s"$superServerMetricsURL/radius/metrics/radiusServerRequest")
      // 1 accept, 1 reject, 2 drop
      checkMetric(jServerRequests, 4, Map("rh" -> "127.0.0.1", "rq" -> "1"), "Access-Request received")
      // 1 acct ok, 2 acct drop
      checkMetric(jServerRequests, 3, Map("rh" -> "127.0.0.1", "rq" -> "4"), "Accounting-Request received")
 
      // Responses sent
      val jServerResponses = jsonFromGet(s"$superServerMetricsURL/radius/metrics/radiusServerResponse")
      // 1 access accept
      checkMetric(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "2"), "Access-Accept sent")
      // 1 access reject
      checkMetric(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "3"), "Access-Reject sent")
      // 1 accounting response
      checkMetric(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "5"), "Accounting-Response sent")
      
      // Packets dropped by the server (not the handler)
      val jServerDrops = jsonFromGet(s"$superServerMetricsURL/radius/metrics/radiusServerDropped")
      // No packets dropped. Stat not shown 
      checkMetric(jServerDrops, -1, Map(), "Packets dropped")
      
      // Packets answered by handler
      val jHandlerResponses = jsonFromGet(s"$superServerMetricsURL/radius/metrics/radiusHandlerResponse?agg=rs")
      // 1 access accept
      checkMetric(jHandlerResponses, 1, Map("rs" -> "2"), "Access-Accept responses")
      // 1 access reject
      checkMetric(jHandlerResponses, 1, Map("rs" -> "3"), "Access-Reject responses")

      // Packets dropped by handler
      val jHandlerDrops = jsonFromGet(s"$superServerMetricsURL/radius/metrics/radiusHandlerDropped?agg=rq")
      // 2 packet dropped each, since the server will retry to superserver
      checkMetric(jHandlerDrops, 2, Map("rq" -> "1"), "Access-Request dropped")
      checkMetric(jHandlerDrops, 2, Map("rq" -> "4"), "Accounting-Request dropped")
      
      nextTest()
  }
  
  def checkServerRadiusStats(): Unit = {

      println("[TEST] Server stats")
      
      // Requests received
      val jServerRequests = jsonFromGet(s"$serverMetricsURL/radius/metrics/radiusServerRequest")
      // 1 accept, 1 reject, 1 drop
      checkMetric(jServerRequests, 3, Map("rh" -> "127.0.0.1", "rq" -> "1"), "Access-Request received")
      // 1 acct ok, 1 acct drop
      checkMetric(jServerRequests, 2, Map("rh" -> "127.0.0.1", "rq" -> "4"), "Accounting-Request received")
 
      // Responses sent
      val jServerResponses = jsonFromGet(s"$serverMetricsURL/radius/metrics/radiusServerResponse")
      // 1 access accept
      checkMetric(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "2"), "Access-Accept sent")
      // 1 access reject
      checkMetric(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "3"), "Access-Reject sent")
      // 1 accounting respone
      checkMetric(jServerResponses, 1, Map("rh" -> "127.0.0.1", "rs" -> "5"), "Accounting-Response sent")
      
      // Packets dropped by the server (not the handler)
      val jServerDrops = jsonFromGet(s"$serverMetricsURL/radius/metrics/radiusServerDropped")
      // No packets dropped. Stat not shown 
      checkMetric(jServerDrops, -1, Map(), "Packets dropped")
      
      // Packets answered by handler
      val jHandlerResponses = jsonFromGet(s"$serverMetricsURL/radius/metrics/radiusHandlerResponse?agg=rs")
      // 1 access accept
      checkMetric(jHandlerResponses, 1, Map("rs" -> "2"), "Access-Accept responses")
      // 1 access reject
      checkMetric(jHandlerResponses, 1, Map("rs" -> "3"), "Access-Reject responses")
      // 1 accounting response
      checkMetric(jHandlerResponses, 1, Map("rs" -> "5"), "Accounting responses")

      // Packets dropped by handler
      val jHandlerDrops = jsonFromGet(s"$serverMetricsURL/radius/metrics/radiusHandlerDropped?agg=rq")
      // Server drops the packets for which it receives no response from non-existing-server
      checkMetric(jHandlerDrops, 1, Map("rq" -> "1"), "Access-Request dropped")
      checkMetric(jHandlerDrops, 1, Map("rq" -> "4"), "Accounting-Request dropped")
      
      nextTest()
  }
  
  def checkClientRadiusStats(): Unit = {
      println("[TEST] Client stats")
      
      // 3 requests to the non-existing-server
      val jClientRequests1 = jsonFromGet(s"$clientMetricsURL/radius/metrics/radiusClientRequest?agg=rh")
      checkMetric(jClientRequests1, 3, Map("rh" -> "1.1.1.1:1812"), "Requests sent to non existing server")
      
      // 3 access requests, 2 accounting requests to server
      val jClientRequests2 = jsonFromGet(s"$clientMetricsURL/radius/metrics/radiusClientRequest?agg=rh,rq")
      checkMetric(jClientRequests2, 3, Map("rq" -> "1", "rh" -> "127.0.0.1:1812"), "Access-Requests sent to server")
      checkMetric(jClientRequests2, 2, Map("rq" -> "4", "rh" -> "127.0.0.1:1813"), "Acounting-Requests sent to server")
      
      // Responses received
      val jResponsesReceived = jsonFromGet(s"$clientMetricsURL/radius/metrics/radiusClientResponse?agg=rs")
      checkMetric(jResponsesReceived, 1, Map("rs" -> "2"), "Access-Accept received from server")
      checkMetric(jResponsesReceived, 1, Map("rs" -> "3"), "Access-Reject received from server")
      checkMetric(jResponsesReceived, 1, Map("rs" -> "5"), "Accounting-Response received from server")
      
      // Timeouts
      val jTimeouts = jsonFromGet(s"$clientMetricsURL/radius/metrics/radiusClientTimeout?agg=rh,rq")
      // One per each to non-existing-server
      checkMetric(jTimeouts, 3, Map("rq" -> "1", "rh" -> "1.1.1.1:1812"), "Access-Request timeouts from non existing server")
      // The one explicitly dropped
      checkMetric(jTimeouts, 1, Map("rq" -> "1", "rh" -> "127.0.0.1:1812"), "Access-Request timeouts from server")
      // Just one, in the first try
      checkMetric(jTimeouts, 1, Map("rq" -> "4", "rh" -> "1.1.1.1:1813"), "Accounting-Request timeouts from non existing server")
      // The one explicitly dropped
      checkMetric(jTimeouts, 1, Map("rq" -> "4", "rh" -> "127.0.0.1:1813"), "Accounting-Request timeouts from server")
      
      nextTest()
  }
  
  def checkSuperserverDiameterStats(): Unit = {
      println("[TEST] Superserver stats")
      
      // Requests received
      val jRequestsReceived = jsonFromGet(s"$superServerMetricsURL/diameter/metrics/diameterRequestReceived?agg=peer,ap,cm")
      // 1 AA, 1AC, 1CCR
      checkMetric(jRequestsReceived, 1, Map("peer" -> "server.yaasserver", "ap" -> "1", "cm" -> "265"), "NASREQ AAR received")
      checkMetric(jRequestsReceived, 1, Map("peer" -> "server.yaasserver", "ap" -> "1", "cm" -> "271"), "NASREQ ACR received")
      checkMetric(jRequestsReceived, 1, Map("peer" -> "server.yaasserver", "ap" -> "16777238", "cm" -> "272"), "Gx CCR received")

      // Answers sent
      val jAnswerSent = jsonFromGet(s"$superServerMetricsURL/diameter/metrics/diameterAnswerSent?agg=peer,ap,cm,rc")
      // 1 AA, 1AC
      checkMetric(jAnswerSent, 1, Map("peer" -> "server.yaasserver", "ap" -> "1", "cm" -> "265", "rc" -> "2001"), "NASREQ AAA sent")
      checkMetric(jAnswerSent, 1, Map("peer" -> "server.yaasserver", "ap" -> "1", "cm" -> "271", "rc" -> "2001"), "NASREQ ACA sent")
      checkMetric(jAnswerSent, 1, Map("peer" -> "server.yaasserver", "ap" -> "16777238", "cm" -> "272", "rc" -> "2001"), "Gx CCA sent")
      
      // Handled requests
      val jHandlerServer = jsonFromGet(s"$superServerMetricsURL/diameter/metrics/diameterHandlerServer?agg=oh,dr,rc")
      // 1 AA, 1AC
      checkMetric(jHandlerServer, 2, Map("oh" -> "server.yaasserver", "dr" -> "yaassuperserver", "rc" -> "2001"), "AA/C Handled")
      // 1 CCR
      checkMetric(jHandlerServer, 1, Map("oh" -> "client.yaasclient", "dr" -> "yaassuperserver", "rc" -> "2001"), "Gx CCR Handled")
      
      val jHandlerClient = jsonFromGet(s"$superServerMetricsURL/diameter/metrics/diameterHandlerClient?agg=oh")
      // 1 AA, 1AC
      checkMetric(jHandlerClient, -1, Map("oh" -> "server.yaasserver"), "AA Handled")
      
      nextTest()
  }
  
  def checkServerDiameterStats(): Unit = {
      println("[TEST] Server stats")
      
      val jRequestsReceived = jsonFromGet(s"$serverMetricsURL/diameter/metrics/diameterRequestReceived?agg=peer,ap")
      // 1 AA, 1AC
      checkMetric(jRequestsReceived, 2, Map("peer" -> "client.yaasclient", "ap" -> "1"), "NASREQ requests received")
      // 1 Gx CCR
      checkMetric(jRequestsReceived, 1, Map("peer" -> "client.yaasclient", "ap" -> "16777238"), "Gx requests received")
      
      val jRequestsSent = jsonFromGet(s"$serverMetricsURL/diameter/metrics/diameterRequestSent?agg=peer,ap")
      // 1 AA, 1AC
      checkMetric(jRequestsSent, 2, Map("peer" -> "superserver.yaassuperserver", "ap" -> "1"), "NASREQ requests sent")
      // 1 Gx CCR
      checkMetric(jRequestsSent, 1, Map("peer" -> "superserver.yaassuperserver", "ap" -> "16777238"), "Gx requests sent")
      
      val jAnswersReceived = jsonFromGet(s"$serverMetricsURL/diameter/metrics/diameterAnswerReceived?agg=ap")
      // 1 AA, 1AC
      checkMetric(jAnswersReceived, 2, Map("ap" -> "1"), "NASREQ answers received")
      // Gx CCA
      checkMetric(jAnswersReceived, 1, Map("ap" -> "16777238"), "Gx answers received")

      val jAnswerSent = jsonFromGet(s"$serverMetricsURL/diameter/metrics/diameterAnswerSent?agg=ap,rc")
      // 1 AA, 1AC
      checkMetric(jAnswerSent, 2, Map("ap" -> "1", "rc" -> "2001"), "NASREQ answers sent")
      // 1 CCR
      checkMetric(jAnswerSent, 1, Map("ap" -> "16777238", "rc" -> "2001"), "Gx answers sent")
      
      val jHandlerServer = jsonFromGet(s"$serverMetricsURL/diameter/metrics/diameterHandlerServer?agg=oh,ap")
      // 1 AA, 1AC
      checkMetric(jHandlerServer, 2, Map("oh" -> "client.yaasclient", "ap" -> "1"), "AA Handled")
      // 0 Gx
      checkMetric(jHandlerServer, -1, Map("oh" -> "client.yaasclient", "ap" -> "16777238"), "AA Handled")
      
      val jHandlerClient = jsonFromGet(s"$serverMetricsURL/diameter/metrics/diameterHandlerClient?agg=oh,ap,rc")
      // 1 AA, 1AC
      checkMetric(jHandlerClient, 2, Map("oh" -> "server.yaasserver", "ap" -> "1", "rc" -> "2001"), "AA Handled")
      
      nextTest()
  }
  
  def checkHttpStats(method: String,  mustBe: Int)(): Unit = {
    println(s"[TEST] Http Stats")
    
    val jHttpRequests = jsonFromGet(s"$superServerMetricsURL/http/metrics/httpOperation?agg=method")
    checkMetric(jHttpRequests, mustBe, Map("method" -> method), s"$method")
    
    nextTest()
  }
  
  def checkQueueStats(): Unit = {
    println(s"[TEST] Queue Stats")

    // TODO: Check a value here

    val jRadiusQueueStats = jsonFromGet(s"$serverMetricsURL/radius/metrics/radiusClientQueueSize")
    if(jRadiusQueueStats == JNothing) fail("Radius Queue Stats did not return a valid value") else ok("Radius Queue Stats -> " + compact(jRadiusQueueStats))
    
    val jDiameterQueueStats = jsonFromGet(s"$serverMetricsURL/diameter/metrics/diameterPeerQueueSize")
    if(jDiameterQueueStats == JNothing) fail("Diameter Queue Stats did not return a valid value") else ok("Diameter Queue Stats -> " + compact(jDiameterQueueStats))
    
    nextTest()
  }
  
  def checkSessionStats(): Unit = {
    println(s"[TEST] Session Stats")
    
    val jSessions = jsonFromGet(s"$superServerMetricsURL/session/metrics/sessions")
    if(jSessions == JNothing) fail("sessions did not return a valid value") else ok("Session Stats  -> " + compact(jSessions))
    
    nextTest()
  }

  /**
   * Sends multiple Radius requests in an async fashion, waits for the answers and prints the results
   * @param serverGroup where to send the resquest
   * @param requestType 1 or 4
   * @param acctStatusType in case of requestType 4, "Start", "Stop" or "Interim-Update"
   * @param domain realm in the username of the request
   * @param nRequests total number of requests to be sent
   * @param nThreads this is really the maximum number of in-flight requests
   * @param testName to be printed in stdout
   */
  def checkRadiusPerformance(serverGroup: String, requestType: Int, acctStatusType: String, domain: String, nRequests: Int, nThreads: Int, testName: String)(): Unit = {
    
    println(s"[TEST] RADIUS Performance. $testName")
    
    val startTime = System.currentTimeMillis()
    
    // Number of requests performed up to now
    var i = new java.util.concurrent.atomic.AtomicInteger(0)
    
    // Each simulated thread does this
    def requestLoop: Future[Unit] = {
      
      val promise = Promise[Unit]()
      
      def loop(): Unit = {
        val reqIndex = i.getAndIncrement
        val index = reqIndex % 1000
        print(s"\r$reqIndex ")
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
              if(response.code == ACCOUNTING_RESPONSE || (response.code == RadiusPacket.ACCESS_ACCEPT && OctetOps.fromHexToUTF8(response >>* "User-Password") == password)) loop()
              else promise.failure(new Exception("Bad Radius Response"))
              
            case Failure(e) =>
              if(continueOnPerfError){
                print(s"\r         --> At least one timeout")
                loop()
              }
              else promise.failure(e)
          }
        } else promise.success(Unit)
      }
      
      loop()
      promise.future
    }
    
    // Launch the threads
    val loops = List.fill(nThreads)(requestLoop)

    // Combine the results
    Future.reduceLeft(loops)((_, _) => Unit).onComplete {
      case Success(_) =>
        val total = i.get
        if(total < nRequests) fail(s"Not completed. Got $total requests") 
        else{
          // Print results
          print("\r")
          val totalTime = System.currentTimeMillis() - startTime
          ok(s"$nRequests in $totalTime milliseconds, ${1000 * nRequests / totalTime} per second")
        }
        nextTest()
        
      case Failure(e) =>
        fail(e.getMessage)
        nextTest()
    }
  }

  /**
   * Sends multiple NASREQ diameter requests and waits for the results
   * @param requestType may be "AA" and "AC"
   * @param domain the realm in the user name
   * @param acctStatusType may be START_RECORD, STOP_RECORD or INTERIM_RECORD
   * @param nRequests the total number of requests to be sent
   * @param nThreads this is really the maximum number of in-flight requests
   * @param testName to be printed in stdout
   */
  def checkDiameterPerformance(requestType: String, domain: String, acctStatusType: String, nRequests: Int, nThreads: Int, testName: String)(): Unit = {
    
    println(s"[TEST] Diameter Performance. $testName")
    
    val startTime = System.currentTimeMillis()
    
    // Number of requests performed up to now
    var i = new java.util.concurrent.atomic.AtomicInteger(0)
    
    // Each thread does this
    def requestLoop: Future[Unit] = {
      
      val promise = Promise[Unit]()
      
      def loop(): Unit = {
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
                if (classAttrs == Seq(sentFramedInterfaceId, sentCHAPIdent)) loop()
                else promise.failure(new Exception("Bad answer"))
                
              case Failure(e) =>
                if(continueOnPerfError){
                  print(s"\r         --> At least one timeout")
                  loop()
                }
                else promise.failure(e)
            }
          } 
          else {
            val request = DiameterMessage.request("NASREQ", "AC")
            request << 
              "Destination-Realm" -> "yaasserver" << 
              "Session-Id" -> ("diameterPerformance-" + reqIndex) << 
              "Framed-IP-Address" -> intToIPAddress(5000000 + reqIndex) <<
              "User-Name" -> ("diameter_user_"+ i + "_" + domain) <<
              "Accounting-Record-Type" -> acctStatusType <<
              ("Tunneling" -> Seq("Tunnel-Type" -> "L2TP", "Tunnel-Client-Endpoint" -> "my-tunnel-endpoint"))
            
            sendDiameterRequest(request, 5000).onComplete{
              case Success(answer) =>
                // Check answer
                if(answer.L("Result-Code") == DiameterMessage.DIAMETER_SUCCESS) loop()
                else promise.failure(new Exception("Bad answer"))
        
              case Failure(e) =>
                promise.failure(e)
            }
          }
        } else promise.success(Unit)
      }
      
      loop()
      promise.future
    }
    
    // Launch the threads
    val loops = List.fill(nThreads)(requestLoop)
    Future.reduceLeft(loops)((_, _) => Unit).onComplete {
      case Success(v) =>
        val total = i.get
        if(total < nRequests) fail(s"Not completed. Got $total requests") 
        else{
          // Print results
          print("\r")
          val totalTime = System.currentTimeMillis() - startTime
          ok(s"$nRequests in $totalTime milliseconds, ${1000 * nRequests / totalTime} per second")
        }
        nextTest()
        
      case Failure(e) =>
        fail(e.getMessage)
        nextTest()
    }
  }
  
  
  ////////////////////////////////////////////////////////////////////////////////////
  // Helpers for IPAM
  ////////////////////////////////////////////////////////////////////////////////////
  def createPool(poolId: String): Unit = {
    val jPool: JValue = "poolId" -> poolId
    val retCode = codeFromPostJson(iamBaseURL + "/pool", compact(jPool))
    if(retCode == 201) ok(s"$poolId created") else fail(s"Error creating Pool. Got $retCode")
  }
  
  def createPoolSelector(selectorId: String, poolId: String, priority: Int): Unit = {
    val jPoolSelector: JValue = ("selectorId" -> selectorId) ~ ("poolId" -> poolId) ~ ("priority" -> priority) 
    val retCode = codeFromPostJson(iamBaseURL + "/poolSelector", compact(jPoolSelector))
    if(retCode == 201) ok(s"PoolSelector $selectorId,$poolId created") else fail(s"Error creating PoolSelector. Got $retCode")
  }
  
  def createRange(poolId: String, startIPAddress: Int, endIPAddress: Int, status: Int): Unit = {
    val jRange: JValue = ("poolId" -> poolId) ~ ("startIPAddress" -> startIPAddress) ~ ("endIPAddress" -> endIPAddress) ~ ("status" -> status) 
    val retCode = codeFromPostJson(iamBaseURL + "/range", compact(jRange))
    if(retCode == 201) ok(s"Range $poolId,$startIPAddress created") else fail(s"Error creating Range. Got $retCode")
  }
  
  def deletePool(poolId: String): Unit = {
    val retCode = codeFromDelete(iamBaseURL + "/pool/" + poolId)
    if(retCode == 202) ok(s"$poolId deleted") else fail(s"Error deleting Pool. Got $retCode")
  }
  
  def deletePoolSelector(selectorId: String, poolId: String): Unit = {
    val retCode = codeFromDelete(iamBaseURL + "/poolSelector/" + selectorId + "," + poolId)
    if(retCode == 202) ok(s"$selectorId,$poolId deleted") else fail(s"Error deleting PoolSelector. Got $retCode")
  }
  
  def deleteRange(poolId: String, startIPAddress: Int): Unit = {
    val retCode = codeFromDelete(iamBaseURL + "/range/" + poolId + "," + startIPAddress)
    if(retCode == 202) ok(s"$poolId,$startIPAddress deleted") else fail(s"Error deleting Range. Got $retCode")
  }
  
  ////////////////////////////////////////////////////////////////////////////////////
  
  def factorySettings () : Unit = {
    
    println("\n[RESET TO FACTORY SETTINGS]")
    
    val retCode = codeFromPostJson(iamBaseURL + "/factorySettings", "{}")
    if(retCode == 201) ok("Reset to factory settings") else fail(s"Response code: $retCode")
    
    nextTest()
  }

  def createPools(): Unit = {
    
    println("\n[CREATE POOLS]")
    
    createPool("pool-1-republica")
    createPool("pool-2-republica")
    createPool("pool-1-cuyo")
    createPool("pool-2-cuyo")
    createPool("small-pool")
    
    nextTest()
  }

  def createPoolSelectors() : Unit = {
    
    println("\n[CREATE POOLSELECTORS]")
    
    createPoolSelector("Republica", "pool-1-republica", 1)
    createPoolSelector("Republica", "pool-2-republica", 2)
    createPoolSelector("Cuyo", "pool-1-cuyo", 1)
    createPoolSelector("Cuyo", "pool-2-cuyo", 2)
    createPoolSelector("Small", "small-pool", 1)
    
    nextTest()
  }
  
  def createRanges() : Unit = {
    
    println("\n[CREATE RANGES]")

    // Selector "Republica" has two pools, with two ranges of 1000 addesses each
    createRange("pool-1-republica", 10000, 10999, 1)
    createRange("pool-1-republica", 11000, 11999, 1)
    createRange("pool-2-republica", 12000, 12999, 1)
    createRange("pool-2-republica", 13000, 13999, 1)
    createRange("pool-1-cuyo", 2000, 2099, 1)
    createRange("pool-1-cuyo", 2100, 2199, 1)
    createRange("pool-2-cuyo", 2200, 2299, 1)
    createRange("pool-2-cuyo", 2300, 2399, 1)
    createRange("small-pool", 9000, 9002, 1)
    
    nextTest()

  }
  
  def deleteRanges(): Unit = {
    println("\n[DELETE RANGES]")
    
    createRange("pool-2-cuyo", 8000, 8100, 1)

    // Delete range not allowed
    var retCode = codeFromDelete(iamBaseURL + "/range/pool-2-cuyo,8000")
    if(retCode == 409) ok("Deletion of Range with status 1 not allowed") else fail(s"Deleted Range with status 1")

    // Change range status
    retCode = codeFromPatchJson(iamBaseURL + "/range/pool-2-cuyo,8000?status=0", "{}")
    if(retCode == 202) ok("Range status modified") else fail(s"Error modifying Range status")

    deleteRange("pool-2-cuyo", 8000)
    
    nextTest()
  }
  
  def deletePoolSelectors(): Unit = {
    println("\n[DELETE POOLSELECTORS]")
    
    createPoolSelector("disposable", "pool-2-cuyo", 3)
    deletePoolSelector("disposable", "pool-2-cuyo")
    
    nextTest()
  }
  
  def deletePools(): Unit = {
    println("\n[DELETE POOLS]")
    
    createPool("disposable")
    deletePool("disposable")
    
    nextTest()
  }
  
  def errorConditions(): Unit = {
    println("\n[FAILURE CONDITIONS]")

    // Create range for undefined pools returns error 409
    var jRange: JValue = ("poolId" -> "undefined-pool") ~ ("startIPAddress" -> 1) ~ ("endIPAddress" -> 2) ~ ("status" -> 1)
    var retCode = codeFromPostJson(iamBaseURL + "/range", compact(jRange))
    if(codeFromPostJson(iamBaseURL + "/range", compact(jRange)) == 409) ok(s"Range with undefined pool rejected") else fail(s"Range with undefined pool got $retCode")
    
    // Create pool selector for undefined pool returns error 409
    var jPoolSelector: JValue = ("selectorId" -> "myselector") ~ ("poolId" -> "undefined-pool") ~ ("priority" -> 1)
    retCode = codeFromPostJson(iamBaseURL + "/poolSelector", compact(jPoolSelector))
    if(retCode == 409) ok(s"PoolSelector with undefined pool rejected") else fail(s"PoolSelector with undefined pool got $retCode")
    
    // Pool already existing returns error 409
    val jPool: JValue = "poolId" -> "pool-1-republica"
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
    
    // Delete un-existing range returns 404
    retCode = codeFromDelete(iamBaseURL + "/range/nonpool,1")
    if(retCode == 404) ok(s"Delete non existing range returns not-found") else fail(s"Delete non existing ranges returns $retCode")
    
    nextTest()
  }
  
  def fillPool(): Unit = {
    println("\n[INSERTING LEASES IN small-pool]")
    
    if(codeFromPostJson(iamBaseURL + "/fillPoolLeases/small-pool", "{}") == 200) ok(s"Pool filled") else fail(s"Error while filling pool") 
    
    nextTest()
  }
  
  def reloadLookup(): Unit = {
     jsonFromPostJson(iamBaseURL + "/reloadLookup", "{}")
     jsonFromPostJson(iamSecondaryBaseURL + "/reloadLookup", "{}")
     
     nextTest()
  }
  
  def testLeases(): Unit = {
    println("\n[LEASES]")

    var retCode = 0
    
    // Getting addresses from createRange("small-pool", 9000, 9002, 1)
    
    // Ask for three IP addresses. Check that the results are 9000, 9001 and 9002
    val addresses = (1 to 3).map(i => (jsonFromPostJson(iamBaseURL + s"/lease?selectorId=Small&requester=req$i", "{}") \ "ipAddress").extract[Int])
    if(addresses.sorted == List(9000, 9001, 9002)) ok("Got addresses 9000, 9001 and 9002") else fail("Got bad addresses")

    // Next request has to return 420
    retCode = codeFromPostJson(iamBaseURL + "/lease?selectorId=Small", "{}")
    if(retCode == 420) ok("No more IP addresses available") else fail(s"Got $retCode when leasing address from pool that should be full")
    
    // Free one of them
    retCode = codeFromPostJson(iamBaseURL + "/release?ipAddress=9001", "{}")
    if(retCode == 200) ok("Address released") else fail(s"Address not released. Got $retCode")

    // Re-lease it again, after waiting ONE SECOND (grace time should be configured accordingly)
    Thread.sleep(1100)
    val jLease = jsonFromPostJson(iamBaseURL + "/lease?selectorId=Small", "{}")
    val JInt(newAddr) = jLease \ "ipAddress"
    if(newAddr == 9001) ok("Address leased again") else fail(s"Address NOT leased again")
    
    // Renew
    val requester = (jLease \ "assignedTo").extract[String]
    retCode = codeFromPostJson(iamBaseURL + s"/renew?ipAddress=9001&requester=$requester", "{}")
    if(retCode == 200) ok("Address renewed") else fail(s"Address NOT renewed. Got $retCode")
    
    // Renew with failure
    retCode = codeFromPostJson(iamBaseURL + "/renew?ipAddress=9001&requester=fake", "{}")
    if(retCode == 404) ok("Renewal disallowed") else fail(s"Renewal with failure got $retCode")
    
    nextTest()
  }

  def deletionsWithLeases(): Unit = {
    println("\n[DELETIONS]")

    var retCode = 0
    var retValues = (0, "")

    // Not deletion, but need to have it somewhere

    // Change selector priority returns not found
    retCode = codeFromPatchJson(iamBaseURL + "/poolSelector/Small,non-existing?priority=1", "{}")
    if(retCode == 404) ok("PoolSelector not found") else fail(s"Found fake PoolSelector")

    // Change selector priority
    retCode = codeFromPatchJson(iamBaseURL + "/poolSelector/Small,small-pool?priority=5", "{}")
    if(retCode == 202) ok("Changed PoolSelector priority") else fail(s"Trying to change Selector priority got $retCode")

    // Check priority changed
    if((jsonFromGet(iamBaseURL + "/poolSelectors?selectorId=Small")(0) \ "priority").extract[Int] == 5) ok("Selector priority changed") else fail("Priority not changed")

    // Try to delete Pool with Selector and Ranges fails
    retValues = codeAndMessageFromDelete(iamBaseURL + "/pool/small-pool")
    if(retValues._1 == 409 && retValues._2.endsWith("Assigned Selector")) ok(s"Not deleted because assigned selector") else fail(s"Deletion of pool in use got ${retValues._1} ${retValues._2}")

    // Now, start deleting

    // Delete Pool selector
    retValues = codeAndMessageFromDelete(iamBaseURL + "/poolSelector/Small,small-pool")
    if(retValues._1 == 202) ok("Small PoolSelector deleted") else fail(s"Deletion of PoolSelector got ${retValues._1} ${retValues._2}")

    // Try to delete Pool. Cannot be done because there are assigned ranges
    retValues = codeAndMessageFromDelete(iamBaseURL + "/pool/small-pool")
    if(retValues._1 == 409 && retValues._2.endsWith("Assigned Range")) ok(s"Not deleted because assigned range") else fail(s"Deletion of pool with assigned range got ${retValues._1} ${retValues._2}")

    // Delete Range, but active status
    retValues = codeAndMessageFromDelete(iamBaseURL + "/range/small-pool,9000")
    if(retValues._1 == 409 && retValues._2.endsWith("Active Range")) ok("Range not delete due to Active status") else fail(s"Deletion of Range with Active status got ${retValues._1} ${retValues._2}")

    // Update status
    retCode = codeFromPatchJson(iamBaseURL + "/range/small-pool,9000?status=0", "{}")
    if(retCode == 202) ok("Range status updated") else fail(s"Range status update got $retCode")

    // Delete Pool with cascade
    retValues = codeAndMessageFromDelete(iamBaseURL + "/pool/small-pool?deleteRanges=true")
    if(retValues._1 == 409 && retValues._2.endsWith("Active Leases")) ok("Pool not delete due to Active Leases") else fail(s"Deletion of Range with Active leases got ${retValues._1} ${retValues._2}")

    // Lease found
    retCode = codeFromGet(iamBaseURL + "/leases?ipAddress=9000")
    if(retCode == 200) ok("Lease for 9000 found") else fail(s"Lease for 9000 got $retCode")

    // Delete Pool with cascade and withActiveLeases
    retValues = codeAndMessageFromDelete(iamBaseURL + "/pool/small-pool?deleteRanges=true&withActiveLeases=true")
    if(retValues._1 == 202) ok(s"Pool deleted") else fail(s"Deletion of Pool with cascade and activeLeases ${retValues._1} ${retValues._2}")

    // Lease not found
    retCode = codeFromGet(iamBaseURL + "/leases?ipAddress=9000")
    if(retCode == 404) ok("Lease for 9000 not found") else fail(s"Lease for 9000 got $retCode")

    nextTest()
  }
  
  def testBulkLease(): Unit = {
    
    println("\n[BULK LEASE]")

    implicit val executionContext: MessageDispatcher = ActorSystem().dispatchers.lookup("yaas-client-test-dispatcher")
    
    val nAddresses = 4000
    val nThreads = 10
    
    val startTime = System.currentTimeMillis()
    
    // Number of IP addresses got up tu now
    val addressCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    
    // To be executed by one thread
    def requestLoop() = {
      var myAddresses = 0
      while(addressCounter.get < nAddresses)
      {
        print(s"\r${addressCounter.get + 1} ")
        val base = if(addressCounter.get % 2 == 0) iamBaseURL else iamBaseURL //iamSecondaryBaseURL
        Try(jsonFromPostJson(base + "/lease?selectorId=Republica", "{}") \ "ipAddress") match {
          
          case Success(JInt(_)) =>
            myAddresses = myAddresses + 1
            addressCounter.incrementAndGet()
            
          case Success(_) =>
            // Try again
            print(s"\rDid not get an IP address. Try again")
            
          case Failure(e) =>
            // Try again
            fail(e.getMessage)
        }
      }
      myAddresses
    }
    
    // Accumulate the results of each requestLoop
    val requests = List.fill(nThreads)(Future {requestLoop()})
    Future.foldLeft(requests)(0)((acc, res) => acc + res).onComplete {
      case Success(total) =>
        println("\r                                       ")
        val elapsedTime = System.currentTimeMillis() - startTime
        val rate = (nAddresses * 1000) / elapsedTime
        if(total == nAddresses) ok(s"Got $total leases. Rate: $rate leases per second") else fail(s"Got $total instead of $nAddresses")
        nextTest()
        
      case Failure(e) =>
        println("\r                                       ")
        fail(e.getMessage)
        nextTest()
    }
  }
  
  def unavailableLease(): Unit = {
    
    // Ask for a lease after bulklease has exhausted all IP addresses
    // Must get 420 code
    val code = codeFromPostJson(iamBaseURL + "/lease?selectorId=Republica", "{}")
    if(code == 420) ok("Addreses for selector not available") else fail("Got an address from an exhausted pool")

    // Check poolStats
    val poolStats = jsonFromGet(iamBaseURL + "/poolStats?poolId=pool-2-republica&enabledOnly=false")
    val totalAddresses = (poolStats \ "totalAddresses").extract[Long]
    val leasedAddresses = (poolStats \ "leasedAddresses").extract[Long]
    if(totalAddresses == 2000 && leasedAddresses == 2000) ok("Got Pool stats") else fail("Got $totalAddresses totalAddresses and $leasedAddresses leasedAddresses")

    nextTest()
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
      def end(): Unit = nextTest()
    }
    
    import javax.script._
    
    // Instantiate
    val engine = new ScriptEngineManager().getEngineByName("nashorn")
    
    // Put objects in scope
    // Radius/Diameter/HTTP helper
  	engine.put("Yaas", YaasJS)
  	
  	// Base location of the script
  	val scriptURL = ConfigManager.getConfigObjectURL(scriptName).getPath
  	engine.put("baseURL", scriptURL.substring(0, scriptURL.indexOf(scriptName)))
  	
  	// To signal finalization
  	// JScript will invoke Notifier.end
  	engine.put("Notifier", new Notifier)
  	
  	// Execute Javascript
    engine.eval(s"load(baseURL + '$scriptName');")
  }
}