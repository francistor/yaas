package yaas.handlers.test

import akka.actor.{ActorRef}
import yaas.coding.RadiusPacket._

class TestRadiusClientKubernetes(statsServer: ActorRef, configObject: Option[String]) extends TestClientBase(statsServer, configObject) {
  // Not used
  val clientMetricsURL = "http://localhost:19001"
  val serverMetricsURL = "http://localhost:19002"
  val superServerMetricsURL = "http://localhost:19003"
  
  val includingNeRadiusGroup = "yaas-server-ne-group"
  val allServersRadiusGroup = "yaas-server-group"
  
  // Used
  val yaas_test_server = Option(System.getenv("YAAS_TEST_SERVER")).orElse(Option(System.getProperty("YAAS_TEST_SERVER"))).getOrElse("yaas-test-server")
  
  val superServerSessionsURL = s"http://${yaas_test_server}:30501"
  val iamBaseURL = s"http://${yaas_test_server}:30501/iam"
  val iamSecondaryBaseURL = s"http://${yaas_test_server}:30501/iam"
  
    // _ is needed to promote the method (no arguments) to a function
  val tests = IndexedSeq[() => Unit](
      testAccessRequestWithAccept _,
      testAccessRequestWithReject _, 
      testAccessRequestWithDrop _,
      testAccountingRequest _,
      testAccountingRequestWithDrop _,
      factorySettings _,
      createPools _,
      createPoolSelectors _,
      createRanges _,
      deleteRanges _,
      deletePoolSelectors _,
      deletePools _, 
      errorConditions _,
      fillPool _,
      reloadLookup _, // The performance testing will will take some time, required to reload the lookup table in the other server (access is balanced)
      checkRadiusPerformance(allServersRadiusGroup, ACCESS_REQUEST, "<VOID>", "@none", 2000, 10, "Radius Warmup") _,
      checkRadiusPerformance(allServersRadiusGroup, ACCESS_REQUEST, "<VOID>", "@none", nRequests, 10, "Free Wheel") _,
      checkRadiusPerformance(allServersRadiusGroup, ACCESS_REQUEST, "<VOID>", "@database", nRequests, 10, "Database Lookup") _,
      checkRadiusPerformance(allServersRadiusGroup, ACCOUNTING_REQUEST, "Start", "@none", nRequests * 2, 10, "Session storage") _,
      checkRadiusPerformance(allServersRadiusGroup, ACCOUNTING_REQUEST, "Stop", "@none", nRequests * 2, 10, "Session storage") _,
      testLeases _,
      // testBulkLease _,
      unavailableLease _
  )
}