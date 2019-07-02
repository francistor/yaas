package yaas.handlers.test

import akka.actor.{ActorRef}
import yaas.coding.RadiusPacket._

class TestRadiusClientKubernetes(statsServer: ActorRef) extends TestClientBase(statsServer) {
  // Not used
  val clientMetricsURL = "http://localhost:19001"
  val serverMetricsURL = "http://localhost:19002"
  val superServerMetricsURL = "http://localhost:19003"
  
  val iamBaseURL = "http://yaas-localhost:30501"
  val iamSecondaryBaseURL = "http://yaas-localhost:30501"
  
  // Used. yaas-localhost must be defined in hosts file and point to the local node
  val superServerSessionsURL = "http://yaas-localhost:30501"
  
    // _ is needed to promote the method (no arguments) to a function
  val tests = IndexedSeq[() => Unit](
      testAccessRequestWithAccept _,
      testAccessRequestWithReject _, 
      testAccessRequestWithDrop _,
      testAccountingRequest _,
      testAccountingRequestWithDrop _,
      checkRadiusPerformance(ACCESS_REQUEST, "@accept", Math.min(5000, nRequests), 10, "Radius Warmup") _,
      checkRadiusPerformance(ACCESS_REQUEST, "@accept", nRequests, 10, "Free Wheel") _,
      checkRadiusPerformance(ACCESS_REQUEST, "@clientdb", nRequests, 10, "Database Lookup") _,
      checkRadiusPerformance(ACCOUNTING_REQUEST, "@sessiondb", nRequests, 10, "Session storage") _
  )
}