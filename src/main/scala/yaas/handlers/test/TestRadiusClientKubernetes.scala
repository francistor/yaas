package yaas.handlers.test

import akka.actor.{ActorRef}
import yaas.coding.RadiusPacket._

class TestRadiusClientKubernetes(statsServer: ActorRef, configObject: Option[String]) extends TestClientBase(statsServer, configObject) {
  // Not used
  val clientMetricsURL = "http://localhost:19001"
  val serverMetricsURL = "http://localhost:19002"
  val superServerMetricsURL = "http://localhost:19003"
  
  // Used. yaas-localhost must be defined in hosts file and point to the local node
  val superServerSessionsURL = "http://yaas-localhost:30501"
  
  val iamBaseURL = "http://yaas-localhost:30501/iam"
  val iamSecondaryBaseURL = "http://yaas-localhost:30501/iam"
  
    // _ is needed to promote the method (no arguments) to a function
  val tests = IndexedSeq[() => Unit](
      testAccessRequestWithAccept _,
      testAccessRequestWithReject _, 
      testAccessRequestWithDrop _,
      testAccountingRequest _,
      testAccountingRequestWithDrop _,
      checkRadiusPerformance(ACCESS_REQUEST, "@file", Math.min(5000, nRequests), 10, "Radius Warmup") _,
      checkRadiusPerformance(ACCESS_REQUEST, "@file", nRequests, 10, "Free Wheel") _,
      checkRadiusPerformance(ACCESS_REQUEST, "@database", nRequests, 10, "Database Lookup") _,
      checkRadiusPerformance(ACCOUNTING_REQUEST, "@file", nRequests, 10, "Session storage") _,
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