package yaas.handlers.test

import akka.actor.ActorRef
import yaas.coding.RadiusPacket._

class TestClientMain(statsServer: ActorRef, configObject: Option[String]) extends TestClientBase(statsServer, configObject) {

    /**
     * Base URLs
     */
    val clientMetricsURL = "http://localhost:19001"
    val serverMetricsURL = "http://localhost:19002"
    val superServerMetricsURL = "http://localhost:19003"
    val sessionsURL = "http://localhost:19503"

    // Use different URL for full test
    val iamBaseURL = "http://localhost:19503/iam"
    val iamSecondaryBaseURL = "http://localhost:19504/iam"

    /**
     * Number of requests on each iteration
     */
    val nRequests: Int = getIntFromEnv("YAAS_TEST_REQUESTS", 10000)

    /**
     * Whether to run in a loop
     */
    val doLoop: Boolean = getBooleanFromEnv("YAAS_TEST_LOOP", defaultValue = false)

    /**
     * Do not stop if there was a timeout when executing performance testing
     */
    val continueOnPerfError: Boolean = getBooleanFromEnv("YAAS_CONTINUE_ON_PERF_ERROR", defaultValue = false)

    /**
     * Whether to exit the application after finishing the tests
     */
    val exitOnTermination: Boolean = getBooleanFromEnv("YAAS_EXIT_ON_TERMINATION", defaultValue = false)

    /**
     * Number of threads sending parallel requests
     */
    val nThreads: Int = getIntFromEnv("YAAS_TEST_THREADS", 10)

    val includingNeRadiusGroup = "yaas-server-ne-group"
    val allServersRadiusGroup = "yaas-server-group"
  
  // _ is needed to promote the method (no arguments) to a function
  
  val tests1: IndexedSeq[() => Unit] = IndexedSeq[() => Unit](
      // Diameter Peer connections
      checkClientConnectedPeer("server.yaasserver"),
      checkClientNotConnectedPeer("non-existing-server.yaasserver"),
      checkServerConnectedPeer("superserver.yaassuperserver"),
      checkServerConnectedPeer("client.yaasclient"),
      checkSuperServerConnectedPeer("server.yaasserver"),

      // RADIUS testing
      // The following tests must be executed as-is, since the following check* methods rely on those packets
      // being sent to succeed
      testAccessRequestWithAccept _,
      testAccessRequestWithReject _, 
      testAccessRequestWithDrop _,
      testAccountingRequest _,
      testAccountingRequestWithDrop _,
      sleep(6000),
      testCoA _,
      checkSuperserverRadiusStats _,
      checkServerRadiusStats _,
      checkClientRadiusStats _,
      testAccountingInterim _,

      // DIAMETER Testing
      // The following tests must be executed as-is, since the following check* methods rely on those packets
      // being sent to succeed
      testAA _,
      testAC _,
      testGxRouting _,
      sleep(2000),
      checkSuperserverDiameterStats _,
      checkServerDiameterStats _,
      checkHttpStats("GET", 3),

      // Javascript engine testing
      js(configObject.get),

      // RADIUS Performance testing
      checkRadiusPerformance(allServersRadiusGroup, ACCESS_REQUEST, "<VOID>", "@none", 20000, nThreads, "Radius Warmup"),
      checkRadiusPerformance(allServersRadiusGroup, ACCESS_REQUEST, "<VOID>", "@none", nRequests, nThreads, "Free Wheel"),
      checkRadiusPerformance(allServersRadiusGroup, ACCESS_REQUEST, "<VOID>", "@none", nRequests, nThreads, "Database Lookup"),
      checkRadiusPerformance(allServersRadiusGroup, ACCOUNTING_REQUEST, "Start", "@none", nRequests, nThreads, "Session storage (Start)"),
      checkSessionStats _,
      checkRadiusPerformance(allServersRadiusGroup, ACCOUNTING_REQUEST, "Stop", "@none", nRequests, nThreads, "Session storage (Stop)"),

      // DIAMETER Performance testing
      checkDiameterPerformance("AA", "@file", "<VOID>", 20000, nThreads, "AA Warmup"),
      checkDiameterPerformance("AA", "@file", "<VOID>", nRequests, nThreads, "AA Free Wheel"),
      checkDiameterPerformance("AC", "@file", "START_RECORD", nRequests, nThreads, "AC Start"),
      checkSessionStats _,
      checkDiameterPerformance("AC", "@file", "STOP_RECORD", nRequests, nThreads, "AC Stop"),
      checkQueueStats _,

      // IPAM testing
      factorySettings _,
      sleep(1000) ,
      checkHttpStats("POST", 1),
      createPools _,
      createPoolSelectors _,
      createRanges _,
      deleteRanges _,
      deletePoolSelectors _,
      deletePools _, 
      errorConditions _,
      fillPool _,
      reloadLookup _,
      testLeases _,
      deletionsWithLeases _,
      testBulkLease _,
      unavailableLease _
  )

    val tests: IndexedSeq[() => Unit] = IndexedSeq[() => Unit](
        // RADIUS testing
        // The following tests must be executed as-is, since the following check* methods rely on those packets
        // being sent to succeed

        testAccessRequestWithAccept _,
        testAccessRequestWithReject _,
        testAccessRequestWithDrop _,
        testAccountingRequest _,
        testAccountingRequestWithDrop _,
        sleep(6000),
        testCoA _,
        checkSuperserverRadiusStats _,
        checkServerRadiusStats _,
        checkClientRadiusStats _,
        testAccountingInterim _,



        //  js(configObject.get)

    )

}