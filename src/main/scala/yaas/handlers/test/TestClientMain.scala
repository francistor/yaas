package yaas.handlers.test

import akka.actor.{ActorRef}
import yaas.coding.RadiusPacket._

class TestClientMain(statsServer: ActorRef) extends TestClientBase(statsServer) {
  val clientMetricsURL = "http://localhost:19001"
  val serverMetricsURL = "http://localhost:19002"
  val superServerMetricsURL = "http://localhost:19003"
  val superServerSessionsURL = "http://localhost:19503"
  
  val iamBaseURL = "http://localhost:19503/iam"
  val iamSecondaryBaseURL = "http://localhost:19503/iam"
  
  // _ is needed to promote the method (no arguments) to a function
  val tests = IndexedSeq[() => Unit](
      checkConnectedPeer(s"${clientMetricsURL}", "server.yaasserver") _,
      checkNotConnectedPeer(s"${clientMetricsURL}", "non-existing-server.yaasserver") _,
      checkConnectedPeer(s"${serverMetricsURL}", "superserver.yaassuperserver") _,
      checkConnectedPeer(s"${serverMetricsURL}", "client.yaasclient") _,
      checkConnectedPeer(s"${superServerMetricsURL}", "server.yaasserver") _,
      testAccessRequestWithAccept _,
      stop _,
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
      checkRadiusPerformance(ACCESS_REQUEST, "@accept", Math.min(5000, nRequests), 10, "Radius Warmup") _,
      checkRadiusPerformance(ACCESS_REQUEST, "@accept", nRequests, 10, "Free Wheel") _,
      checkRadiusPerformance(ACCESS_REQUEST, "@clientdb", nRequests, 10, "Database Lookup") _,
      checkRadiusPerformance(ACCOUNTING_REQUEST, "@sessiondb", nRequests, 10, "Session storage") _,
      checkDiameterPerformance("AA", "@accept", Math.min(5000, nRequests), 10, "AA Warmup") _,
      checkDiameterPerformance("AA", "@accept", nRequests, 10, "AA Free Wheel") _,
      checkDiameterPerformance("AC", "@accept", nRequests, 10, "AC Warmup") _,
      checkDiameterPerformance("AC", "@accept", nRequests, 10, "AC Free Wheel") _,
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