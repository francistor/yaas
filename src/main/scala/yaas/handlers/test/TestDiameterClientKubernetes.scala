package yaas.handlers.test

import akka.actor.{ActorRef}
import yaas.coding.RadiusPacket._

class TestDiameterClientKubernetes(statsServer: ActorRef) extends TestClientBase(statsServer) {
  // Not used
  val clientStatsURL = "http://localhost:19001"
  val serverStatsURL = "http://localhost:19002"
  val superServerStatsURL = "http://localhost:19003"
  
  // Used. yaas-localhost must be defined in hosts file and point to the local node
  val superServerSessionsURL = "http://yaas-localhost:30501"
  
    // _ is needed to promote the method (no arguments) to a function
  val tests = IndexedSeq[() => Unit](
      testAA _,
      testAC _,
      testGxRouting _,   
      checkDiameterPerformance("AA", "@accept", 20000, 10, "AA Warmup") _,
      checkDiameterPerformance("AA", "@accept", 10000, 10, "AA Free Wheel") _,
      checkDiameterPerformance("AC", "@accept", 20000, 10, "AC Warmup") _,
      checkDiameterPerformance("AC", "@accept", 10000, 10, "AC Free Wheel") _
  )
}