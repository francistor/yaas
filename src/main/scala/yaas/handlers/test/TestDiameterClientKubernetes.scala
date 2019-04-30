package yaas.handlers.test

import akka.actor.{ActorRef}
import yaas.coding.RadiusPacket._

class TestDiameterClientKubernetes(statsServer: ActorRef) extends TestClientBase(statsServer) {
  val clientStatsURL = "http://localhost:19001"
  val serverStatsURL = "http://localhost:19002"
  val superServerStatsURL = "http://localhost:19003"
  val superServerSessionsURL = "http://localhost:19503"
  
    // _ is needed to promote the method (no arguments) to a function
  val tests = IndexedSeq[() => Unit](
      clientPeerConnections _, 
      testAA _,
      testAC _,
      testGxRouting _,
      stop _,      checkDiameterPerformance("AA", "@accept", 20000, 10, "AA Warmup") _,
      checkDiameterPerformance("AA", "@accept", 10000, 10, "AA Free Wheel") _,
      checkDiameterPerformance("AC", "@accept", 20000, 10, "AC Warmup") _,
      checkDiameterPerformance("AC", "@accept", 10000, 10, "AC Free Wheel") _
  )
}