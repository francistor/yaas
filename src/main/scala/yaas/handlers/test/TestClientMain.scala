package yaas.handlers.test

import akka.actor.{ActorRef}
import yaas.coding.RadiusPacket._

class TestClientMain(statsServer: ActorRef) extends TestClientBase(statsServer) {
  val clientMetricsURL = "http://localhost:19001"
  val serverMetricsURL = "http://localhost:19002"
  val superServerMetricsURL = "http://localhost:19003"
  val superServerSessionsURL = "http://localhost:19503"
  
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
      checkRadiusPerformance(ACCESS_REQUEST, "@accept", 20000, 10, "Radius Warmup") _,
      checkRadiusPerformance(ACCESS_REQUEST, "@accept", 20000, 10, "Free Wheel") _,
      checkRadiusPerformance(ACCESS_REQUEST, "@clientdb", 10000, 10, "Database Lookup") _,
      checkRadiusPerformance(ACCOUNTING_REQUEST, "@sessiondb", 10000, 10, "Session storage") _,
      checkDiameterPerformance("AA", "@accept", 20000, 10, "AA Warmup") _,
      checkDiameterPerformance("AA", "@accept", 10000, 10, "AA Free Wheel") _,
      checkDiameterPerformance("AC", "@accept", 20000, 10, "AC Warmup") _,
      checkDiameterPerformance("AC", "@accept", 10000, 10, "AC Free Wheel") _
  )
}