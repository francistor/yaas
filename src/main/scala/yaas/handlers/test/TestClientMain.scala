package yaas.handlers.test

import akka.actor.{ActorRef}
import yaas.coding.RadiusPacket._

class TestClientMain(statsServer: ActorRef, configObject: Option[String]) extends TestClientBase(statsServer, configObject) {
  val clientMetricsURL = "http://localhost:19001"
  val serverMetricsURL = "http://localhost:19002"
  val superServerMetricsURL = "http://localhost:19003"
  val sessionsURL = "http://localhost:19503"
  
  val includingNeRadiusGroup = "yaas-server-ne-group"
  val allServersRadiusGroup = "yaas-server-group"
  
  // Use different URL for full test
  val iamBaseURL = "http://localhost:19503/iam"
  val iamSecondaryBaseURL = "http://localhost:19504/iam"
  
  // nRequests is 10000 or the value in YAAS_TEST_REQUESTS
  
  // _ is needed to promote the method (no arguments) to a function
  
  val tests = IndexedSeq[() => Unit](
      runJS(configObject.get) _,
      checkConnectedPeer(s"${clientMetricsURL}", "server.yaasserver") _,
      checkNotConnectedPeer(s"${clientMetricsURL}", "non-existing-server.yaasserver") _,
      checkConnectedPeer(s"${serverMetricsURL}", "superserver.yaassuperserver") _,
      checkConnectedPeer(s"${serverMetricsURL}", "client.yaasclient") _,
      checkConnectedPeer(s"${superServerMetricsURL}", "server.yaasserver") _,
      testAccessRequestWithAccept _,
      testAccessRequestWithReject _, 
      testAccessRequestWithDrop _,
      testAccountingRequest _,
      testAccountingRequestWithDrop _,
      sleep(6000) _,
      checkSuperserverRadiusStats _,
      checkServerRadiusStats _,
      checkClientRadiusStats _,
      testAccountingInterim _,
      testAA _,
      testAC _,
      testGxRouting _,
      sleep(2000) _,
      checkSuperserverDiameterStats _,
      checkServerDiameterStats _,
      checkHttpStats("GET", 3) _,
      runJS(configObject.get) _,
      checkRadiusPerformance(allServersRadiusGroup, ACCESS_REQUEST, "<VOID>", "@none", 5000, nThreads, "Radius Warmup") _,
      checkRadiusPerformance(allServersRadiusGroup, ACCESS_REQUEST, "<VOID>", "@none", nRequests, nThreads, "Free Wheel") _,
      checkRadiusPerformance(allServersRadiusGroup, ACCESS_REQUEST, "<VOID>", "@database", nRequests, nThreads, "Database Lookup") _,
      checkRadiusPerformance(allServersRadiusGroup, ACCOUNTING_REQUEST, "Start", "@none", nRequests, nThreads, "Session storage (Start)") _,
      checkSessionStats _,
      checkRadiusPerformance(allServersRadiusGroup, ACCOUNTING_REQUEST, "Stop", "@none", nRequests, nThreads, "Session storage (Stop)") _,
      checkDiameterPerformance("AA", "@file", "<VOID>", Math.min(5000, nRequests), nThreads, "AA Warmup") _,
      checkDiameterPerformance("AA", "@file", "<VOID>", nRequests, nThreads, "AA Free Wheel") _,
      checkDiameterPerformance("AC", "@file", "START_RECORD", nRequests, nThreads, "AC Start") _,
      checkSessionStats _,
      checkDiameterPerformance("AC", "@file", "STOP_RECORD", nRequests, nThreads, "AC Stop") _,
      checkQueueStats _,
      factorySettings _,
      sleep(1000) _,
      checkHttpStats("POST", 1) _,
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
      testBulkLease _,
      unavailableLease _
  )
}