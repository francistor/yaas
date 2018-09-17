package yaas.stats

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props}

object StatsServer {
  
  class DiameterStatsKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String) {
    def getValue(key : String) = {
      key match {
        case "oh" => oh; case "or" => or; case "dh" => dh; case "dr" => dr; case "ap" => ap; case "cm" => cm;
      }
    }
    
    /**
     * Given a set of keys (e.g. List("ap", "cm")) returns the corresponding values of this DiameterStatItem, to be used as a key
     * for aggregation
     */
    def getAggrValue(keyList : List[String]) = {
      for {
        key <- keyList
      } yield getValue(key)
    }
  }
  
  class DiameterPeerStatsKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterStatsKey(oh, or, dh, dr, ap, cm) {
    override def getValue(key : String) = {
      key match {
        case "peer" => peer
        case k: String => super.getValue(k)
      }
    }
  }
  
  /*
   * oh = origin host / "<void>"
   * or = origin realm / "<void>"
   * dh = destination host / "<void>"
   * dr = destination realm / "<void>"
   * ap = application Id (note the id is stored as a string)
   * cm = command Code (note the code is stored as a string)
   * rc = diameter result code (note the code is stored as a string). 
   * rt = response time (ceil(l2(rt)) in milliseconds) 
   *  
   */
  
  // Peer Stats
  case class DiameterRequestReceivedKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterPeerStatsKey(peer, oh, or, dh, dr, ap, cm)
  case class DiameterAnswerReceivedKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String, rt: String) extends DiameterPeerStatsKey(peer, oh, or, dh, dr, ap, cm) {
    override def getValue(key : String) = {
      key match {
        case "rt" => rt
        case "rc" => rc
        case k: String => super.getValue(k)
      }
    }
  }
  case class DiameterRequestTimeoutKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterPeerStatsKey(peer, oh, or, dh, dr, ap, cm)
  case class DiameterAnswerSentKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String) extends DiameterPeerStatsKey(peer, oh, or, dh, dr, ap, cm){
    override def getValue(key : String) = {
      key match {
        case "rc" => rc
        case k: String => super.getValue(k)
      }
    }
  }
  
  case class DiameterRequestSentKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterPeerStatsKey(peer, oh, or, dh, dr, ap, cm)

  // Router stats
  case class DiameterRequestReceivedDroppedKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterStatsKey(oh, or, dh, dr, ap, cm)
  case class DiameterRequestSentDroppedKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterStatsKey(oh, or, dh, dr, ap, cm)
  case class DiameterRequestRetransmittedKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterStatsKey(oh, or, dh, dr, ap, cm)

  // Handler stats
  case class DiameterHandlerAnswerKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String, rt: String) extends DiameterStatsKey(oh, or, dh, dr, ap, cm){
    override def getValue(key : String) = {
      key match {
        case "rc" => rc
        case "rt" => rt
        case k: String => super.getValue(k)
      }
    }
  }
  case class DiameterHandlerRequestKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String, rt: String) extends DiameterStatsKey(oh, or, dh, dr, ap, cm){
    override def getValue(key : String) = {
      key match {
        case "rc" => rc
        case "rt" => rt
        case k: String => super.getValue(k)
      }
    }
  }
  case class DiameterHandlerRequestTimeoutKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterStatsKey(oh, or, dh, dr, ap, cm)
  
  /////////////////////////////////////////////////////////////////////////////////////////////
  // Radius
  ////////////////////////////////////////////////////////////////////////////////////////////
  
  trait RadiusStatsKey {
    def getValue(key: String) : String
    
    /**
     * Given a set of keys (e.g. List("io", "ra")) returns the corresponding values of this DiameterStatItem, to be used as a key
     * for aggregation
     */
    def getAggrValue(keyList : List[String]) = {
      for {
        key <- keyList
      } yield getValue(key)
    }
  }
  
  /*
   * group = radius group name
   * rh = remote host (ip:port)
   * rq = request code
   * rs = response code
   * rt = response time (ceil(l2(rt)) in milliseconds) <void> if not answered
   *  
   */
  
  // Server stats
  case class RadiusServerRequestKey(rh: String, rq: String) extends RadiusStatsKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh; case "rq" => rq
      }
    }
  }
  case class RadiusServerResponseKey(rh: String, rs: String) extends RadiusStatsKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh; case "rs" => rs
      }
    }
  }
  
  // Client stats
  case class RadiusClientRequestKey(rh: String, rq: String) extends RadiusStatsKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh; case "rq" => rq
      }
    }
  }
  case class RadiusClientResponseKey(rh: String, rq: String, rs: String, rt: String) extends RadiusStatsKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh; case "rq" => rq; case "rs" => rs; case "rt" => rt
      }
    }
  }
  case class RadiusClientTimeoutKey(rh: String, rq: String) extends RadiusStatsKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh; case "rq" => rq
      }
    }
  }
  
  // Handler stats
  case class RadiusHandlerResponseKey(rh: String, rq: String, rs: String, rt: String) extends RadiusStatsKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh; case "rq" => rq; case "rs" => rs; case "rt" => rt
      }
    }
  }
  case class RadiusHandlerDropKey(rh: String, rq: String) extends RadiusStatsKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh; case "rq" => rq
      }
    }
  }
  case class RadiusHandlerRequestKey(rh: String, rq: String, rs: String, rt: String) extends RadiusStatsKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh; case "rq" => rq; case "rs" => rs; case "rt" => rt
      }
    }
  }
  case class RadiusHandlerRetransmissionKey(group: String, rq: String) extends RadiusStatsKey {
    def getValue(key : String) = {
      key match {
        case "group" => group; case "rq" => rq
      }
    }
  }
  case class RadiusHandlerTimeoutKey(group: String, rq: String) extends RadiusStatsKey {
    def getValue(key : String) = {
      key match {
        case "group" => group; case "rq" => rq
      }
    }
  }
  
  def props() = Props(new StatsServer)
  
}

class StatsServer extends Actor with ActorLogging {
  
  import StatsServer._
  
  case class RadiusStatsItem(keyMap: Map[String, String], counter: Long)
  case class DiameterStatsItem(keyMap: Map[String, String], counter: Long)

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Diameter
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Peer stats
  private val diameterRequestReceivedStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  private val diameterAnswerReceivedStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  private val diameterRequestTimeoutStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  private val diameterAnswerSentStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  private val diameterRequestSentStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  
  private val diameterRequestReceivedDroppedStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  private val diameterRequestSentDroppedStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  private val diameterRequestRetransmittedStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  
  private val diameterHandlerAnswerStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  private val diameterHandlerRequestStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  private val diameterHandlerRequestTimeoutStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  
  /**
   * Get stats items aggregated by the specified keys
   * Returns a List of DiameterStatsItem[List[String], Long], where the List[String] is a list of the different (discrete) values for the
   * keys List
   */
  private def getDiameterStats(statsMap: scala.collection.mutable.Map[DiameterStatsKey, Long], keys : List[String]) = {
    statsMap.groupBy{ case (statsKey, value) => statsKey.getAggrValue(keys)}
    .map{case (k, v) => DiameterStatsItem(keys.zip(k).toMap, v.values.reduce(_+_))}
    .toList
  }
  
  def getDiameterRequestReceivedStats(keys: List[String]) = getDiameterStats(diameterRequestReceivedStats, keys)
  def getDiameterAnswerReceivedStats(keys: List[String]) = getDiameterStats(diameterAnswerReceivedStats, keys)
  def getDiameterRequestTimeoutStats(keys: List[String]) = getDiameterStats(diameterRequestTimeoutStats, keys)
  def getDiameterAnswerSentStats(keys: List[String]) = getDiameterStats(diameterAnswerSentStats, keys)
  def getDiameterRequestSentStats(keys: List[String]) = getDiameterStats(diameterRequestSentStats, keys)
  
  def getDiameterRequestReceivedDroppedStats(keys: List[String]) = getDiameterStats(diameterRequestReceivedDroppedStats, keys)
  def getDiameterRequestSentDroppedStats(keys: List[String]) = getDiameterStats(diameterRequestSentDroppedStats, keys)
  def getDiamterRequestRetransmittedStats(keys: List[String]) = getDiameterStats(diameterRequestRetransmittedStats, keys)
  
  def getDiameterHandlerAnswerStats(keys: List[String]) = getDiameterStats(diameterHandlerAnswerStats, keys)
  def getDiamterHandlerRequestStats(keys: List[String]) = getDiameterStats(diameterHandlerRequestStats, keys)
  def getDiamterHandlerTimeout(keys: List[String]) = getDiameterStats(diameterHandlerRequestTimeoutStats, keys)
  
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Radius
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  private val radiusServerRequestStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  private val radiusServerResponseStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  private val radiusClientRequestStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  private val radiusClientResponseStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  private val radiusClientTimeoutStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  private val radiusHandlerResponseStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  private val radiusHandlerDropStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  private val radiusHandlerRequestStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  private val radiusHandlerRetransmissionStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  private val radiusHandlerRequestTimeoutStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)

  
  /**
   * Get stats items aggregated by the specified keys
   */
  def getRadiusStats(statsMap: scala.collection.mutable.Map[RadiusStatsKey, Long], keys: List[String]) = {
     statsMap.groupBy{case (statsKey, value) => statsKey.getAggrValue(keys)}
     .map{case (k, v) => RadiusStatsItem(keys.zip(k).toMap, v.values.reduce(_+_))}
     .toList
  }
  
  def getRadiusServerRequestStats(keys : List[String]) = getRadiusStats(radiusServerRequestStats, keys)
  def getRadiusServerResponseStats(keys : List[String]) = getRadiusStats(radiusServerResponseStats, keys)
  def getRadiusClientRequestStats(keys : List[String]) = getRadiusStats(radiusClientRequestStats, keys)
  def getRadiusClientResponseStats(keys : List[String]) = getRadiusStats(radiusClientResponseStats, keys)
  def getRadiusClientTimeoutStats(keys : List[String]) = getRadiusStats(radiusClientTimeoutStats, keys)
  def getRadiusHandlerResponseStats(keys : List[String]) = getRadiusStats(radiusHandlerResponseStats, keys)
  def getRadiusHandlerDropStats(keys : List[String]) = getRadiusStats(radiusHandlerDropStats, keys)
  def getRadiusHandlerRequestStats(keys : List[String]) = getRadiusStats(radiusHandlerRequestStats, keys)
  def getRadiusHandlerRetransmissionStats(keys : List[String]) = getRadiusStats(radiusHandlerRetransmissionStats, keys)
  def getRadiusHandlerRequestTimeoutStats(keys : List[String]) = getRadiusStats(radiusHandlerRequestTimeoutStats, keys)
  
  
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Receive
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////// 
  
  def receive = {
    case s: DiameterRequestReceivedKey => diameterRequestReceivedStats(s) = diameterRequestReceivedStats(s) + 1
    case s: DiameterAnswerReceivedKey => diameterAnswerReceivedStats(s) = diameterAnswerReceivedStats(s) + 1
    case s: DiameterRequestTimeoutKey => diameterRequestTimeoutStats(s) = diameterRequestTimeoutStats(s) + 1
    case s: DiameterAnswerSentKey => diameterAnswerSentStats(s) = diameterAnswerSentStats(s) + 1
    case s: DiameterRequestSentKey => diameterRequestSentStats(s) = diameterRequestSentStats(s) + 1
    
    case s: DiameterRequestReceivedDroppedKey => diameterRequestReceivedDroppedStats(s) = diameterRequestReceivedDroppedStats(s) + 1
    case s: DiameterRequestRetransmittedKey => diameterRequestRetransmittedStats(s) = diameterRequestRetransmittedStats(s) + 1

    case s: DiameterHandlerAnswerKey => diameterHandlerAnswerStats(s) = diameterHandlerAnswerStats(s) + 1
    case s: DiameterHandlerRequestKey => diameterHandlerRequestStats(s) = diameterHandlerRequestStats(s) + 1
    case s: DiameterHandlerRequestTimeoutKey => diameterHandlerRequestTimeoutStats(s) = diameterHandlerRequestTimeoutStats(s) + 1
  }
}