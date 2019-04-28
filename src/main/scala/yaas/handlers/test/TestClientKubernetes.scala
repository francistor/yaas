package yaas.handlers.test

import akka.actor.{ActorRef}
import yaas.coding.RadiusPacket._

class TestClientKubernetes(statsServer: ActorRef) extends TestClientBase(statsServer) {
  val clientStatsURL = "http://localhost:19001"
  val serverStatsURL = "http://localhost:19002"
  val superServerStatsURL = "http://localhost:19003"
  val superServerSessionsURL = "http://localhost:19503"
  
    // _ is needed to promote the method (no arguments) to a function
  val tests = IndexedSeq[() => Unit](
      clientPeerConnections _, 
      testAccessRequestWithAccept _,
      testAccessRequestWithReject _, 
      testAccessRequestWithDrop _,
      testAccountingRequest _,
      testAccountingRequestWithDrop _,
      sleep _,
      checkClientRadiusStats _,
      testAA _,
      testAC _,
      testGxRouting _,
      stop _,
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