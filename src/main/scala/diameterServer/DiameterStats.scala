package diameterServer

import diameterServer.coding._
import diameterServer.coding.DiameterConversions._

object DiameterStats {
  /*
   * io = 0 -> input (received), 1 -> output (sent)
   * qr = 0 -> request, 1 -> response
   * pr = diameterPeer name
   * oh = origin host / ""
   * or = origin realm / ""
   * dh = destination host / ""
   * hr = destination realm / ""
   * ap = application name
   * cm = command name
   * rc = diameter result code
   */
  
  case class DiameterStatItem(io: Int, qr: Int, pr: String, oh: String, or: String, dh: String, dr: String, ap: Long, cm: Long, rc: String)
  
  val diameterStats = scala.collection.mutable.Map[DiameterStatItem, Long]()
  
  def stat(peerName: String, isIncoming: Boolean, diameterMessage: DiameterMessage) = {
    val originHost : String = (diameterMessage >> "Origin-Host")
    val originRealm : String = (diameterMessage >> "Origin-Realm")
    val destinationHost : String = (diameterMessage >> "Destination-Host")
    val destinationRealm : String = (diameterMessage >> "Destination-Realm")
    val diameterResultCode : String = (diameterMessage >> "Result-Code")
    val incoming = if(isIncoming) 0 else 1
    val request = if(diameterMessage.isRequest) 1 else 0
    
    val key =DiameterStatItem(incoming, request, peerName, originHost, originRealm, destinationHost, destinationRealm, 
        diameterMessage.applicationId, diameterMessage.commandCode, diameterResultCode)
    
    diameterStats.get(key) match {
      case Some(value) => diameterStats(key) = value + 1
      case None => diameterStats(key) = 1
    }
  }
}