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
  case class DiameterRequestDroppedKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterStatsKey(oh, or, dh, dr, ap, cm)

  // Handler stats
  case class DiameterHandlerServerKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String, rt: String) extends DiameterStatsKey(oh, or, dh, dr, ap, cm){
    override def getValue(key : String) = {
      key match {
        case "rc" => rc
        case "rt" => rt
        case k: String => super.getValue(k)
      }
    }
  }
  case class DiameterHandlerClientKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String, rt: String) extends DiameterStatsKey(oh, or, dh, dr, ap, cm){
    override def getValue(key : String) = {
      key match {
        case "rc" => rc
        case "rt" => rt
        case k: String => super.getValue(k)
      }
    }
  }
  case class DiameterHandlerClientTimeoutKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterStatsKey(oh, or, dh, dr, ap, cm)
  
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
  case class RadiusServerDropKey(rh: String) extends RadiusStatsKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh;
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
  case class RadiusClientDroppedKey(rh: String) extends RadiusStatsKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh;
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
  case class RadiusHandlerDroppedKey(rh: String, rq: String) extends RadiusStatsKey {
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
  
  // Get stat messages
  case class GetDiameterStats(statName: String, params: List[String])
  case class GetRadiusStats(statName: String, params: List[String])
  
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
  
  private val diameterRequestDroppedStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  
  private val diameterHandlerServerStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  private val diameterHandlerClientStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  private val diameterHandlerClientTimeoutStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  
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
  
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Radius
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  private val radiusServerRequestStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  private val radiusServerDropStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  private val radiusServerResponseStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  
  private val radiusClientRequestStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  private val radiusClientResponseStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  private val radiusClientTimeoutStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  private val radiusClientDroppedStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  
  private val radiusHandlerResponseStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  private val radiusHandlerDroppedStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  private val radiusHandlerRequestStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  private val radiusHandlerRetransmissionStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
  private val radiusHandlerTimeoutStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)

  
  /**
   * Get stats items aggregated by the specified keys
   */
  def getRadiusStats(statsMap: scala.collection.mutable.Map[RadiusStatsKey, Long], keys: List[String]) = {
     statsMap.groupBy{case (statsKey, value) => statsKey.getAggrValue(keys)}
     .map{case (k, v) => RadiusStatsItem(keys.zip(k).toMap, v.values.reduce(_+_))}
     .toList
  }
  
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Receive
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////// 
  
  def receive = {
    /*
     * Statistics update
     */
    
    // Diameter
    case s: DiameterRequestReceivedKey => diameterRequestReceivedStats(s) = diameterRequestReceivedStats(s) + 1
    case s: DiameterAnswerReceivedKey => diameterAnswerReceivedStats(s) = diameterAnswerReceivedStats(s) + 1
    case s: DiameterRequestTimeoutKey => diameterRequestTimeoutStats(s) = diameterRequestTimeoutStats(s) + 1
    case s: DiameterAnswerSentKey => diameterAnswerSentStats(s) = diameterAnswerSentStats(s) + 1
    case s: DiameterRequestSentKey => diameterRequestSentStats(s) = diameterRequestSentStats(s) + 1
    
    case s: DiameterRequestDroppedKey => diameterRequestDroppedStats(s) = diameterRequestDroppedStats(s) + 1

    case s: DiameterHandlerServerKey => diameterHandlerServerStats(s) = diameterHandlerServerStats(s) + 1
    case s: DiameterHandlerClientKey => diameterHandlerClientStats(s) = diameterHandlerClientStats(s) + 1
    case s: DiameterHandlerClientTimeoutKey => diameterHandlerClientTimeoutStats(s) = diameterHandlerClientTimeoutStats(s) + 1
    
    // Radius
    case s: RadiusServerRequestKey => radiusServerRequestStats(s) = radiusServerRequestStats(s) + 1
    case s: RadiusServerDropKey => radiusServerDropStats(s) = radiusServerDropStats(s) + 1
    case s: RadiusServerResponseKey => radiusServerResponseStats(s) = radiusServerResponseStats(s) + 1
    
    case s: RadiusClientRequestKey => radiusClientRequestStats(s) = radiusClientRequestStats(s) + 1
    case s: RadiusClientResponseKey => radiusClientResponseStats(s) = radiusClientResponseStats(s) + 1
    case s: RadiusClientTimeoutKey => radiusClientTimeoutStats(s) = radiusClientTimeoutStats(s) + 1
    case s: RadiusClientDroppedKey => radiusClientDroppedStats(s) = radiusClientDroppedStats(s) + 1
    
    case s: RadiusHandlerResponseKey => radiusHandlerResponseStats(s) = radiusHandlerResponseStats(s) + 1
    case s: RadiusHandlerDroppedKey => radiusHandlerDroppedStats(s) = radiusHandlerDroppedStats(s) + 1
    case s: RadiusHandlerTimeoutKey => radiusHandlerTimeoutStats(s) = radiusHandlerTimeoutStats(s) + 1
    
    /*
     * Statistics query
     */
    case GetDiameterStats(statName, paramList) =>
      statName match {
        case "diameterRequestReceived" => getDiameterStats(diameterRequestReceivedStats, paramList)
        case "diameterAnswerReceived" => getDiameterStats(diameterAnswerReceivedStats, paramList)
        case "diameterRequestTimeout" => getDiameterStats(diameterRequestTimeoutStats, paramList)
        case "diameterAnswerSent" => getDiameterStats(diameterAnswerSentStats, paramList)
        case "diameterRequestSent" => getDiameterStats(diameterRequestSentStats, paramList)
        
        case "diameterRequestDropped" => getDiameterStats(diameterRequestDroppedStats, paramList)
        
        case "diameterHandlerServer" => getDiameterStats(diameterHandlerServerStats, paramList)
        case "diameterHandlerClient" => getDiameterStats(diameterHandlerClientStats, paramList)
        case "diameterHandlerClientTimeout" => getDiameterStats(diameterHandlerClientTimeoutStats, paramList)
      }
      
    case GetRadiusStats(statName, paramList) =>
      statName match {
        case "radiusServerRequest" => getRadiusStats(radiusServerRequestStats, paramList)
        case "radiusServerDrop" => getRadiusStats(radiusServerDropStats, paramList)
        case "radiusServerResponse" => getRadiusStats(radiusServerResponseStats, paramList)
        
        case "radiusClientRequest" => getRadiusStats(radiusClientRequestStats, paramList)
        case "radiusClientResponse" => getRadiusStats(radiusClientResponseStats, paramList)
        case "radiusClientTimeout" => getRadiusStats(radiusClientTimeoutStats, paramList)
        case "radiusClientDropped" => getRadiusStats(radiusClientDroppedStats, paramList)
        
        case "radiusHandlerResponse" => getRadiusStats(radiusHandlerResponseStats, paramList)
        case "radiusHandlerDropped" => getRadiusStats(radiusHandlerDroppedStats, paramList)
        case "radiusHandlerTimeout" => getRadiusStats(radiusHandlerTimeoutStats, paramList)
      }
  }
}