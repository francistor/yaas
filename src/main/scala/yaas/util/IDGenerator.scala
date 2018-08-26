package yaas.util

class IDGenerator {
  var hopByHopId = (Math.random() * 2147483647).toInt
  var endToEndId = hopByHopId
  
  def nextHopByHopId : Int = {
    hopByHopId += 1
    hopByHopId
  }
  
  def nextEndToEndId : Int = {
    endToEndId += 1
    endToEndId
  }
  
  def nextRadiusId : Long = {
    (System.currentTimeMillis() / 1000) * 4294967296L + nextEndToEndId
  }
}