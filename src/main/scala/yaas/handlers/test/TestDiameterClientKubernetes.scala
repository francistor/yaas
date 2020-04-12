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
  val yaas_test_type = Integer.parseInt(Option(System.getenv("YAAS_TEST_TYPE")).orElse(Option(System.getProperty("YAAS_TEST_TYPE"))).getOrElse("2"))

  val sessionsURL = s"http://${yaas_test_server}:30501"
  val iamBaseURL = s"http://${yaas_test_server}:30501/iam"
  val iamSecondaryBaseURL = s"http://${yaas_test_server}:30501/iam"

  /**
   * Number of requests on each iteration
   */
  val nRequests: Int = getIntFromEnv("YAAS_TEST_REQUESTS", 10000)

  /**
   * Whether to run in a loop
   */
  val doLoop: Boolean = getBooleanFromEnv("YAAS_TEST_LOOP", defaultValue = false)

  /**
   * Do not stop if there was a timeout when executing performance testing
   */
  val continueOnPerfError: Boolean = getBooleanFromEnv("YAAS_CONTINUE_ON_PERF_ERROR", defaultValue = false)

  /**
   * Whether to exit the application after finishing the tests
   */
  val exitOnTermination: Boolean = getBooleanFromEnv("YAAS_EXIT_ON_TERMINATION", defaultValue = false)

  /**
   * Number of threads sending parallel requests
   */
  val nThreads: Int = getIntFromEnv("YAAS_TEST_THREADS", 10)

  val includingNeRadiusGroup = "yaas-server-ne-group"
  val allServersRadiusGroup = "yaas-server-group"

    // _ is needed to promote the method (no arguments) to a function
  val tests = IndexedSeq[() => Unit](
      testAA _,
      testAC _,
      testGxRouting _,   
      checkDiameterPerformance("AA", "@file", "<VOID>", 2000, nThreads, "AA Warmup") _,
      checkDiameterPerformance("AA", "@file", "<VOID>", nRequests, nThreads, "AA Free Wheel") _,
      if(yaas_test_type > 1) checkDiameterPerformance("AC", "@file", "START_RECORD", nRequests, nThreads, "AC Start") _ else () => {nextTest},
      if(yaas_test_type > 1)  checkDiameterPerformance("AC", "@file", "STOP_RECORD", nRequests, nThreads, "AC Stop") _ else () => {nextTest}
  )
}