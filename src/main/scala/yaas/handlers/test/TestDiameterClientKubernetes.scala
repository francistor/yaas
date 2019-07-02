package yaas.handlers.test

import akka.actor.{ActorRef}
import yaas.coding.RadiusPacket._

class TestDiameterClientKubernetes(statsServer: ActorRef) extends TestClientBase(statsServer) {
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
      testAA _,
      testAC _,
      testGxRouting _,   
      checkDiameterPerformance("AA", "@accept", Math.min(5000, nRequests), 10, "AA Warmup") _,
      checkDiameterPerformance("AA", "@accept", nRequests, 10, "AA Free Wheel") _,
      checkDiameterPerformance("AC", "@accept", nRequests, 10, "AC Warmup") _,
      checkDiameterPerformance("AC", "@accept", nRequests, 10, "AC Free Wheel") _
  )
}