package yaas.handlers.test

import akka.actor.{ActorRef}
import yaas.coding.RadiusPacket._

class TestDiameterClientKubernetes(statsServer: ActorRef, configObject: Option[String]) extends TestClientBase(statsServer, configObject) {
  // Not used
  val clientMetricsURL = "http://localhost:19001"
  val serverMetricsURL = "http://localhost:19002"
  val superServerMetricsURL = "http://localhost:19003"
  
  // Used
    val yaas_test_server = Option(System.getenv("YAAS_TEST_SERVER")).orElse(Option(System.getProperty("YAAS_TEST_SERVER"))).getOrElse("yaas-test-server")
  
  val superServerSessionsURL = s"http://${yaas_test_server}:30501"
  val iamBaseURL = s"http://${yaas_test_server}:30501/iam"
  val iamSecondaryBaseURL = s"http://${yaas_test_server}:30501/iam"

    // _ is needed to promote the method (no arguments) to a function
  val tests = IndexedSeq[() => Unit](
      testAA _,
      testAC _,
      testGxRouting _,   
      checkDiameterPerformance("AA", "@file", Math.min(5000, nRequests), 10, "AA Warmup") _,
      checkDiameterPerformance("AA", "@file", nRequests, 10, "AA Free Wheel") _,
      checkDiameterPerformance("AC", "@file", nRequests, 10, "AC Warmup") _,
      checkDiameterPerformance("AC", "@file", nRequests, 10, "AC Free Wheel") _
  )
}