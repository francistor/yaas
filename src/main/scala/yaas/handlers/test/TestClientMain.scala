package yaas.handlers.test

import akka.actor.{ActorRef}
import yaas.coding.RadiusPacket._

class TestClientMain(statsServer: ActorRef, configObject: Option[String]) extends TestClientBase(statsServer, configObject) {
  val clientMetricsURL = "http://localhost:19001"
  val serverMetricsURL = "http://localhost:19002"
  val superServerMetricsURL = "http://localhost:19003"
  val superServerSessionsURL = "http://localhost:19503"
  
  val includingNeRadiusGroup = "yaas-server-ne-group"
  val allServersRadiusGroup = "yaas-server-group"
  
  // Use different URL for full test
  val iamBaseURL = "http://localhost:19503/iam"
  val iamSecondaryBaseURL = "http://localhost:19504/iam"
  
  // nRequests is 1000 or the value in YAAS_TEST_REQUESTS
  
  // _ is needed to promote the method (no arguments) to a function
  
  val tests = IndexedSeq[() => Unit](
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
      sleep _,
      checkSuperserverRadiusStats _,
      checkServerRadiusStats _,
      checkClientRadiusStats _,
      testAA _,
      testAC _,
      testGxRouting _,
      sleep _,
      checkSuperserverDiameterStats _,
      checkServerDiameterStats _,
      runJS(configObject.get) _,
      checkRadiusPerformance(allServersRadiusGroup, ACCESS_REQUEST, "@none", Math.min(5000, nRequests), 10, "Radius Warmup") _,
      checkRadiusPerformance(allServersRadiusGroup, ACCESS_REQUEST, "@none", nRequests, 10, "Free Wheel") _,
      checkRadiusPerformance(allServersRadiusGroup, ACCESS_REQUEST, "@database", nRequests, 10, "Database Lookup") _,
      checkRadiusPerformance(allServersRadiusGroup, ACCOUNTING_REQUEST, "@none", nRequests, 10, "Session storage") _,
      checkDiameterPerformance("AA", "@file", Math.min(5000, nRequests), 10, "AA Warmup") _,
      checkDiameterPerformance("AA", "@file", nRequests, 10, "AA Free Wheel") _,
      checkDiameterPerformance("AC", "@file", nRequests, 10, "AC Warmup") _,
      checkDiameterPerformance("AC", "@file", nRequests, 10, "AC Free Wheel") _,
      factorySettings _,
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