package yaas.util

import java.util.concurrent.atomic.AtomicInteger

object IDGenerator {
  var hopByHopId = new AtomicInteger((Math.random() * 2147483647).toInt)
  var endToEndId = new AtomicInteger((Math.random() * 2147483647).toInt)
  
  def nextHopByHopId : Int = {
    hopByHopId.incrementAndGet
  }
  
  def nextEndToEndId : Int = {
    endToEndId.incrementAndGet
  }
  
  def nextRadiusId : Long = {
    (System.currentTimeMillis() / 1000) * 4294967296L + nextEndToEndId
  }
}