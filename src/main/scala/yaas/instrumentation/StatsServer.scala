package yaas.instrumentation

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
  
  case class DiameterAnswerDiscardedKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String) extends DiameterPeerStatsKey(peer, oh, or, dh, dr, ap, cm){
    override def getValue(key : String) = {
      key match {
        case "rc" => rc
        case k: String => super.getValue(k)
      }
    }
  }

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

case class RadiusStatsItem(keyMap: Map[String, String], counter: Long)
case class DiameterStatsItem(keyMap: Map[String, String], counter: Long)

class StatsServer extends Actor with ActorLogging {
  
  import StatsServer._

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Diameter
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Peer stats
  
  // Contain the counter value for each received combination of keys
  private val diameterRequestReceivedStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  private val diameterAnswerReceivedStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  private val diameterRequestTimeoutStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  private val diameterAnswerSentStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  private val diameterRequestSentStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  private val diameterAnswerDiscardedStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  
  // Router stats
  private val diameterRequestDroppedStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  
  // Handler stats
  private val diameterHandlerServerStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  private val diameterHandlerClientStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  private val diameterHandlerClientTimeoutStats = scala.collection.mutable.Map[DiameterStatsKey, Long]().withDefaultValue(0)
  
  /**
   * Get stats items aggregated by the specified keys
   * Returns a List of DiameterStatsItem, which contains the map from key->value and the associated counter value
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
  private val radiusServerDroppedStats = scala.collection.mutable.Map[RadiusStatsKey, Long]().withDefaultValue(0)
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
      // The key for the aggregation is the list of values for the specified keys
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
    case s: DiameterAnswerDiscardedKey => diameterAnswerDiscardedStats(s) = diameterAnswerDiscardedStats(s) + 1
    
    case s: DiameterRequestDroppedKey => diameterRequestDroppedStats(s) = diameterRequestDroppedStats(s) + 1

    case s: DiameterHandlerServerKey => diameterHandlerServerStats(s) = diameterHandlerServerStats(s) + 1
    case s: DiameterHandlerClientKey => diameterHandlerClientStats(s) = diameterHandlerClientStats(s) + 1
    case s: DiameterHandlerClientTimeoutKey => diameterHandlerClientTimeoutStats(s) = diameterHandlerClientTimeoutStats(s) + 1
    
    // Radius
    case s: RadiusServerRequestKey => radiusServerRequestStats(s) = radiusServerRequestStats(s) + 1
    case s: RadiusServerDropKey => radiusServerDroppedStats(s) = radiusServerDroppedStats(s) + 1
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
      try{
        statName match {
          case "diameterRequestReceived" => sender ! getDiameterStats(diameterRequestReceivedStats, paramList)
          case "diameterAnswerReceived" => sender ! getDiameterStats(diameterAnswerReceivedStats, paramList)
          case "diameterRequestTimeout" => sender ! getDiameterStats(diameterRequestTimeoutStats, paramList)
          case "diameterAnswerSent" => sender ! getDiameterStats(diameterAnswerSentStats, paramList)
          case "diameterRequestSent" => sender ! getDiameterStats(diameterRequestSentStats, paramList)
          case "diameterAnswerDiscarded" => sender ! getDiameterStats(diameterAnswerDiscardedStats, paramList)
          
          case "diameterRequestDropped" => sender ! getDiameterStats(diameterRequestDroppedStats, paramList)
          
          case "diameterHandlerServer" => sender ! getDiameterStats(diameterHandlerServerStats, paramList)
          case "diameterHandlerClient" => sender ! getDiameterStats(diameterHandlerClientStats, paramList)
          case "diameterHandlerClientTimeout" => sender ! getDiameterStats(diameterHandlerClientTimeoutStats, paramList)
        }
      } 
      catch {
        case e: Throwable => sender ! akka.actor.Status.Failure(e)
      }
      
    case GetRadiusStats(statName, paramList) =>
      try{
        statName match {
          case "radiusServerRequest" => sender ! getRadiusStats(radiusServerRequestStats, paramList)
          case "radiusServerDropped" => sender ! getRadiusStats(radiusServerDroppedStats, paramList)
          case "radiusServerResponse" => sender ! getRadiusStats(radiusServerResponseStats, paramList)
          
          case "radiusClientRequest" => sender ! getRadiusStats(radiusClientRequestStats, paramList)
          case "radiusClientResponse" => sender ! getRadiusStats(radiusClientResponseStats, paramList)
          case "radiusClientTimeout" => sender ! getRadiusStats(radiusClientTimeoutStats, paramList)
          case "radiusClientDropped" => sender ! getRadiusStats(radiusClientDroppedStats, paramList)
          
          case "radiusHandlerResponse" => sender ! getRadiusStats(radiusHandlerResponseStats, paramList)
          case "radiusHandlerDropped" => sender ! getRadiusStats(radiusHandlerDroppedStats, paramList)
          case "radiusHandlerRequest" => sender ! getRadiusStats(radiusHandlerRequestStats, paramList)
          case "radiusHandlerRetransmission" => sender ! getRadiusStats(radiusHandlerRetransmissionStats, paramList)
          case "radiusHandlerTimeout" => sender ! getRadiusStats(radiusHandlerTimeoutStats, paramList)
        }
      } 
      catch {
        case e: Throwable => sender ! akka.actor.Status.Failure(e)
      }
  }
}