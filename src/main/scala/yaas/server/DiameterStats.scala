package yaas.server

import yaas.coding._
import yaas.coding.DiameterConversions._

/**
 * This object has to be encapsulated in an Actor, since it is not thread safe
 */
object DiameterStats {
  
  trait DiameterStatItem {
    def getKeyValue(key: String) : String
    
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
  
  /*
   * oh = origin host / "<void>"
   * or = origin realm / "<void>"
   * dh = destination host / "<void>"
   * dr = destination realm / "<void>"
   * ap = application Id (note the id is stored as a string)
   * cm = command Code (note the code is stored as a string)
   * rc = diameter result code (note the code is stored as a string). <void> if not answered
   * rt = response time (ceil(l2(rt)) in milliseconds) <void> if not answered
   *  
   */
  class DiameterHandlerStatItem(oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String, rt: String) extends DiameterStatItem {
    def getKeyValue(key : String) = {
      key match {
        case "oh" => oh
        case "or" => or
        case "dh" => dh
        case "dr" => dr
        case "ap" => ap
        case "cm" => cm
        case "rc" => rc
        case "rt" => rt
      }
    }
  }
  
  case class DiameterServerStatItem(oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String, rt: String) extends DiameterHandlerStatItem(oh, or, dh, dr, ap, cm, rc, rt)
  case class DiameterClientStatItem(oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String, rt: String) extends DiameterHandlerStatItem(oh, or, dh, dr, ap, cm, rc, rt)

  /*
   * DiameterPeer stats report isolated packets only (not matching requests with answers)
   * 
   * io = "in" -> input (received), "out" -> output (sent)
   * ra = "req" -> request, "ans" -> response
   * pr = diameterPeer name
   * oh = origin host / "<void>"
   * or = origin realm / "<void>"
   * dh = destination host / "<void>"
   * dr = destination realm / "<void>"
   * ap = application Id (note the id is stored as a string)
   * cm = command Code (note the code is stored as a string)
   * rc = diameter result code (note the code is stored as a string)
   */
  
  /*
   * One instance of this class for each set of keys to be kept counters
   */
  case class DiameterPeerStatItem(io: String, ra: String, pr: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String) extends DiameterStatItem {
    def getKeyValue(key : String) = {
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
  }
  
  /////////////////////////////////////////////////////////////////////////////
  
  /*
   * Stats are kept here
   */
  val diameterPeerStats = scala.collection.mutable.Map[DiameterPeerStatItem, Long]().withDefaultValue(0)
  
  /*
   * Constructor of DiameterStatItem from message
   */
  def pushDiameterPeerStatItem(peerName: String, isIncoming: Boolean, diameterMessage: DiameterMessage) = {
    val originHost : String = (diameterMessage >> "Origin-Host")
    val originRealm : String = (diameterMessage >> "Origin-Realm")
    val destinationHost : String = (diameterMessage >> "Destination-Host")
    val destinationRealm : String = (diameterMessage >> "Destination-Realm")
    val diameterResultCode : String = (diameterMessage >> "Result-Code")
    val incoming : String = if(isIncoming) "in" else "out"
    val request : String = if(diameterMessage.isRequest) "req" else "ans"
    
    val key = DiameterPeerStatItem(incoming, request, peerName, originHost, originRealm, destinationHost, destinationRealm, 
        diameterMessage.applicationId.toString, diameterMessage.commandCode.toString, diameterResultCode)
    
    diameterPeerStats.get(key) match {
      case Some(value) => diameterPeerStats(key) = value + 1
      case None => diameterPeerStats(key) = 1
    }
  }
  
  /**
   * Get stats items aggregated by the specified keys
   */
  def getDiameterPeerStats(keys : List[String]) = {
    diameterPeerStats.groupBy{ case (statItem, value) => statItem.getAggrKeyValue(keys)}.
      map{case (k, v) => (k, v.values.reduce(_+_))}
  }
  
  
}