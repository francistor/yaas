package yaas.diameterServer

import yaas.diameterServer.coding._
import yaas.diameterServer.coding.DiameterConversions._

/**
 * This object has to be encapsulated in an Actor, since it is not thread safe
 */
object DiameterStats {
  /*
   * io = "in" -> input (received), "out" -> output (sent)
   * ra = "req" -> request, "ans" -> response
   * pr = diameterPeer name
   * oh = origin host / ""
   * or = origin realm / ""
   * dh = destination host / ""
   * hr = destination realm / ""
   * ap = application Id (note the id is stored as a string)
   * cm = command Code (note the code is stored as a string)
   * rc = diameter result code (note the code is stored as a string)
   */
  
  /*
   * One instace of this class for each set of keys to be kept counters
   */
  case class DiameterStatItem(io: String, ra: String, pr: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String) {
    private def getKeyValue(key : String) = {
      key match {
        case "io" => io
        case "ra" => ra
        case "pr" => pr
        case "oh" => oh
        case "or" => or
        case "dh" => dh
        case "dr" => dr
        case "ap" => ap
        case "cm" => cm
        case "rc" => rc
      }
    }
    
    /**
     * Given a set of keys (e.g. List("io", "ra")) returns the corresponding values of this DiameterStatItem, to be used as a key
     * for aggregation
     */
    def getAggrKeyValue(keyList : List[String]) = {
      for {
        key <- keyList
      } yield getKeyValue(key)
    }
  }
  
  /////////////////////////////////////////////////////////////////////////////
  
  /*
   * Stats are kept here
   */
  val diameterStats = scala.collection.mutable.Map[DiameterStatItem, Long]()
  
  /*
   * Constructor of DiameterStatItem from message
   */
  def pushStatItem(peerName: String, isIncoming: Boolean, diameterMessage: DiameterMessage) = {
    val originHost : String = (diameterMessage >> "Origin-Host")
    val originRealm : String = (diameterMessage >> "Origin-Realm")
    val destinationHost : String = (diameterMessage >> "Destination-Host")
    val destinationRealm : String = (diameterMessage >> "Destination-Realm")
    val diameterResultCode : String = (diameterMessage >> "Result-Code")
    val incoming : String = if(isIncoming) "in" else "out"
    val request : String = if(diameterMessage.isRequest) "req" else "ans"
    
    val key = DiameterStatItem(incoming, request, peerName, originHost, originRealm, destinationHost, destinationRealm, 
        diameterMessage.applicationId.toString, diameterMessage.commandCode.toString, diameterResultCode)
    
    diameterStats.get(key) match {
      case Some(value) => diameterStats(key) = value + 1
      case None => diameterStats(key) = 1
    }
  }
  
  /**
   * Get stats items aggregated by the specified keys
   */
  def getStats(keys : List[String]) = {
    diameterStats.map{ case (statItem, value) => (statItem.getAggrKeyValue(keys), value)}
  }
  
  
}