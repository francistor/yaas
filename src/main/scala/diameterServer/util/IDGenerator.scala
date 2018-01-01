package diameterServer.util

class IDGenerator {
  var hopByHopId = 0
  var endToEndId = 0
  
  def nextHopByHopId : Int = {
    hopByHopId += 1
    hopByHopId
  }
  
  def nextEndToEndId : Int = {
    endToEndId += 1
    endToEndId
  }
}