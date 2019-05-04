package yaas.handlers.test

import akka.actor.{ActorRef}
import yaas.coding.RadiusPacket._

class TestRadiusClientKubernetes(statsServer: ActorRef) extends TestClientBase(statsServer) {
  // Not used
  val clientStatsURL = "http://localhost:19001"
  val serverStatsURL = "http://localhost:19002"
  val superServerStatsURL = "http://localhost:19003"
  
  // Used. yaas-server must be defined in hosts file and point to the local node
  val superServerSessionsURL = "http://yaas-localhost:30501"
  
    // _ is needed to promote the method (no arguments) to a function
  val tests = IndexedSeq[() => Unit](
      testAccessRequestWithAccept _,
      testAccessRequestWithReject _, 
      testAccessRequestWithDrop _,
      testAccountingRequest _,
      testAccountingRequestWithDrop _,
      stop _,
      checkRadiusPerformance(ACCESS_REQUEST, "@accept", 20000, 10, "Radius Warmup") _,
      checkRadiusPerformance(ACCESS_REQUEST, "@accept", 20000, 10, "Free Wheel") _,
      checkRadiusPerformance(ACCESS_REQUEST, "@clientdb", 10000, 10, "Database Lookup") _,
      checkRadiusPerformance(ACCOUNTING_REQUEST, "@sessiondb", 10000, 10, "Session storage") _
  )
}