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
    // First int is the number of seconds since the epoch, second int is a sequential number
    // The last 3 bytes are forced to zero, since they will be used to code the retransmission number
    ((System.currentTimeMillis() / 1000) << 16) + (nextEndToEndId << 3)
  }
}