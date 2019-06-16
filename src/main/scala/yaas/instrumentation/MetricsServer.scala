package yaas.instrumentation

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props}
import yaas.server.RadiusActorMessages.RadiusEndpoint

object MetricsServer {
  
  def props() = Props(new MetricsServer)
  
  case object DiameterMetricsReset
  case object RadiusMetricsReset
  
  class DiameterMetricsKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String) {
    def getValue(key : String) = {
      key match {
        case "oh" => oh; case "or" => or; case "dh" => dh; case "dr" => dr; case "ap" => ap; case "cm" => cm;
      }
    }
    
    /**
     * Given a set of keys (e.g. List("ap", "cm")) returns the corresponding values of this DiameterMetricsItem, to be used as a key
     * for aggregation
     */
    def getAggrValue(keyList : List[String]) = {
      for {
        key <- keyList
      } yield getValue(key)
    }
  }
  
  class DiameterPeerMetricsKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterMetricsKey(oh, or, dh, dr, ap, cm) {
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
  
  // Peer Metrics
  case class DiameterRequestReceivedKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterPeerMetricsKey(peer, oh, or, dh, dr, ap, cm)
  case class DiameterAnswerReceivedKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String, rt: String) extends DiameterPeerMetricsKey(peer, oh, or, dh, dr, ap, cm) {
    override def getValue(key : String) = {
      key match {
        case "rt" => rt
        case "rc" => rc
        case k: String => super.getValue(k)
      }
    }
  }
  case class DiameterRequestTimeoutKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterPeerMetricsKey(peer, oh, or, dh, dr, ap, cm)
  case class DiameterAnswerSentKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String) extends DiameterPeerMetricsKey(peer, oh, or, dh, dr, ap, cm){
    override def getValue(key : String) = {
      key match {
        case "rc" => rc
        case k: String => super.getValue(k)
      }
    }
  }
  
  case class DiameterRequestSentKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterPeerMetricsKey(peer, oh, or, dh, dr, ap, cm)
  
  case class DiameterAnswerDiscardedKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String) extends DiameterPeerMetricsKey(peer, oh, or, dh, dr, ap, cm){
    override def getValue(key : String) = {
      key match {
        case "rc" => rc
        case k: String => super.getValue(k)
      }
    }
  }

  // Router Metrics
  case class DiameterRequestDroppedKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterMetricsKey(oh, or, dh, dr, ap, cm)

  // Handler Metrics
  case class DiameterHandlerServerKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String, rt: String) extends DiameterMetricsKey(oh, or, dh, dr, ap, cm){
    override def getValue(key : String) = {
      key match {
        case "rc" => rc
        case "rt" => rt
        case k: String => super.getValue(k)
      }
    }
  }
  case class DiameterHandlerClientKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String, rt: String) extends DiameterMetricsKey(oh, or, dh, dr, ap, cm){
    override def getValue(key : String) = {
      key match {
        case "rc" => rc
        case "rt" => rt
        case k: String => super.getValue(k)
      }
    }
  }
  case class DiameterHandlerClientTimeoutKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterMetricsKey(oh, or, dh, dr, ap, cm)
  
  case class DiameterPeerRequestQueueSize(dp: String, size: Int)
  
  /////////////////////////////////////////////////////////////////////////////////////////////
  // Radius
  ////////////////////////////////////////////////////////////////////////////////////////////
  
  trait RadiusMetricKey {
    def getValue(key: String) : String
    
    /**
     * Given a set of keys (e.g. List("io", "ra")) returns the corresponding values of this DiameterMetricsItem, to be used as a key
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
  
  // Server Metrics
  case class RadiusServerRequestKey(rh: String, rq: String) extends RadiusMetricKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh; case "rq" => rq
      }
    }
  }
  case class RadiusServerDropKey(rh: String) extends RadiusMetricKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh;
      }
    }
  }
  case class RadiusServerResponseKey(rh: String, rs: String) extends RadiusMetricKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh; case "rs" => rs
      }
    }
  }
  
  // Client Metrics
  case class RadiusClientRequestKey(rh: String, rq: String) extends RadiusMetricKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh; case "rq" => rq
      }
    }
  }
  case class RadiusClientResponseKey(rh: String, rq: String, rs: String, rt: String) extends RadiusMetricKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh; case "rq" => rq; case "rs" => rs; case "rt" => rt
      }
    }
  }
  case class RadiusClientTimeoutKey(rh: String, rq: String) extends RadiusMetricKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh; case "rq" => rq
      }
    }
  }
  case class RadiusClientDroppedKey(rh: String) extends RadiusMetricKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh;
      }
    }
  }
  
  // Handler Metrics
  case class RadiusHandlerResponseKey(rh: String, rq: String, rs: String, rt: String) extends RadiusMetricKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh; case "rq" => rq; case "rs" => rs; case "rt" => rt
      }
    }
  }
  case class RadiusHandlerDroppedKey(rh: String, rq: String) extends RadiusMetricKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh; case "rq" => rq
      }
    }
  }
  case class RadiusHandlerRequestKey(rh: String, rq: String, rs: String, rt: String) extends RadiusMetricKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh; case "rq" => rq; case "rs" => rs; case "rt" => rt
      }
    }
  }
  case class RadiusHandlerRetransmissionKey(group: String, rq: String) extends RadiusMetricKey {
    def getValue(key : String) = {
      key match {
        case "group" => group; case "rq" => rq
      }
    }
  }
  case class RadiusHandlerTimeoutKey(group: String, rq: String) extends RadiusMetricKey {
    def getValue(key : String) = {
      key match {
        case "group" => group; case "rq" => rq
      }
    }
  }
  
  case class RadiusServerRequestQueueSizes(sizes: Map[RadiusEndpoint, Int])
  
  // Get metrics messages
  case class GetDiameterMetrics(metricName: String, params: List[String])
  case class GetRadiusMetrics(metricName: String, params: List[String])
  case class GetDiameterPeerRequestQueueGauges()
  case class GetRadiusServerRequestQueueGauges()
}

case class RadiusMetricsItem(keyMap: Map[String, String], counter: Long)
case class DiameterMetricsItem(keyMap: Map[String, String], counter: Long)

class MetricsServer extends Actor with ActorLogging {
  
  import MetricsServer._

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Diameter
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Peer Counters
  
  // Contain the counter value for each received combination of keys
  private var diameterRequestReceivedCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
  private var diameterAnswerReceivedCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
  private var diameterRequestTimeoutCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
  private var diameterAnswerSentCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
  private var diameterRequestSentCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
  private var diameterAnswerDiscardedCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
  
  // Router Metrics
  private var diameterRequestDroppedCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
  
  // Handler Metrics
  private var diameterHandlerServerCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
  private var diameterHandlerClientCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
  private var diameterHandlerClientTimeoutCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
  
  // Queue Metrics
  private var diameterPeerRequestQueueSizeGauge = scala.collection.mutable.Map[String, Long]()
  
  /**
   * Get Metrics items aggregated by the specified keys
   * Returns a List of DiameterMetricsItem, which contains the map from key->value and the associated counter value
   */
  private def getDiameterMetrics(MetricsMap: scala.collection.mutable.Map[DiameterMetricsKey, Long], keys : List[String]) = {
    MetricsMap.groupBy{ case (metricsKey, value) => metricsKey.getAggrValue(keys)}
    .map{case (k, v) => DiameterMetricsItem(keys.zip(k).toMap, v.values.reduce(_+_))}
    .toList
  }
  
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Radius
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  private var radiusServerRequestCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  private var radiusServerDroppedCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  private var radiusServerResponseCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  
  private var radiusClientRequestCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  private var radiusClientResponseCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  private var radiusClientTimeoutCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  private var radiusClientDroppedCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  
  private var radiusHandlerResponseCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  private var radiusHandlerDroppedCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  private var radiusHandlerRequestCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  private var radiusHandlerRetransmissionCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  private var radiusHandlerTimeoutCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  
  private var radiusServerRequestQueueSizeGauge = Map[String, Int]()

  
  /**
   * Get Metrics items aggregated by the specified keys
   */
  def getRadiusMetrics(MetricsMap: scala.collection.mutable.Map[RadiusMetricKey, Long], keys: List[String]) = {
      // The key for the aggregation is the list of values for the specified keys
      MetricsMap.groupBy{case (metricsKey, value) => metricsKey.getAggrValue(keys)} 
      .map{case (k, v) => RadiusMetricsItem(keys.zip(k).toMap, v.values.reduce(_+_))}
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
    case s: DiameterRequestReceivedKey => diameterRequestReceivedCounter(s) = diameterRequestReceivedCounter(s) + 1
    case s: DiameterAnswerReceivedKey => diameterAnswerReceivedCounter(s) = diameterAnswerReceivedCounter(s) + 1
    case s: DiameterRequestTimeoutKey => diameterRequestTimeoutCounter(s) = diameterRequestTimeoutCounter(s) + 1
    case s: DiameterAnswerSentKey => diameterAnswerSentCounter(s) = diameterAnswerSentCounter(s) + 1
    case s: DiameterRequestSentKey => diameterRequestSentCounter(s) = diameterRequestSentCounter(s) + 1
    case s: DiameterAnswerDiscardedKey => diameterAnswerDiscardedCounter(s) = diameterAnswerDiscardedCounter(s) + 1
    
    case s: DiameterRequestDroppedKey => diameterRequestDroppedCounter(s) = diameterRequestDroppedCounter(s) + 1

    case s: DiameterHandlerServerKey => diameterHandlerServerCounter(s) = diameterHandlerServerCounter(s) + 1
    case s: DiameterHandlerClientKey => diameterHandlerClientCounter(s) = diameterHandlerClientCounter(s) + 1
    case s: DiameterHandlerClientTimeoutKey => diameterHandlerClientTimeoutCounter(s) = diameterHandlerClientTimeoutCounter(s) + 1
    
    case DiameterPeerRequestQueueSize(pn, size) => 
      if(size == -1) diameterPeerRequestQueueSizeGauge.remove(pn) else diameterPeerRequestQueueSizeGauge(pn) = size
    
    // Radius
    case s: RadiusServerRequestKey => radiusServerRequestCounter(s) = radiusServerRequestCounter(s) + 1
    case s: RadiusServerDropKey => radiusServerDroppedCounter(s) = radiusServerDroppedCounter(s) + 1
    case s: RadiusServerResponseKey => radiusServerResponseCounter(s) = radiusServerResponseCounter(s) + 1
    
    case s: RadiusClientRequestKey => radiusClientRequestCounter(s) = radiusClientRequestCounter(s) + 1
    case s: RadiusClientResponseKey => radiusClientResponseCounter(s) = radiusClientResponseCounter(s) + 1
    case s: RadiusClientTimeoutKey => radiusClientTimeoutCounter(s) = radiusClientTimeoutCounter(s) + 1
    case s: RadiusClientDroppedKey => radiusClientDroppedCounter(s) = radiusClientDroppedCounter(s) + 1
    
    case s: RadiusHandlerResponseKey => radiusHandlerResponseCounter(s) = radiusHandlerResponseCounter(s) + 1
    case s: RadiusHandlerDroppedKey => radiusHandlerDroppedCounter(s) = radiusHandlerDroppedCounter(s) + 1
    case s: RadiusHandlerTimeoutKey => radiusHandlerTimeoutCounter(s) = radiusHandlerTimeoutCounter(s) + 1
    
    case RadiusServerRequestQueueSizes(sizes) => radiusServerRequestQueueSizeGauge = for{(endPoint, size) <- sizes} yield (s"${endPoint.ipAddress}:${endPoint.port}", size)
    
    case DiameterMetricsReset =>      
      diameterRequestReceivedCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
      diameterAnswerReceivedCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
      diameterRequestTimeoutCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
      diameterAnswerSentCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
      diameterRequestSentCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
      diameterAnswerDiscardedCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)

      diameterRequestDroppedCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
  
      diameterHandlerServerCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
      diameterHandlerClientCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
      diameterHandlerClientTimeoutCounter = scala.collection.mutable.Map[DiameterMetricsKey, Long]().withDefaultValue(0)
  
      diameterPeerRequestQueueSizeGauge = scala.collection.mutable.Map[String, Long]()
      
    case RadiusMetricsReset =>
      radiusServerRequestCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      radiusServerDroppedCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      radiusServerResponseCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  
      radiusClientRequestCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      radiusClientResponseCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      radiusClientTimeoutCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      radiusClientDroppedCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  
      radiusHandlerResponseCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      radiusHandlerDroppedCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      radiusHandlerRequestCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      radiusHandlerRetransmissionCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      radiusHandlerTimeoutCounter = scala.collection.mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)

    /*
     * Statistics query
     */
    case GetDiameterMetrics(metricName, paramList) =>
      try{
        metricName match {
          case "diameterRequestReceived" => sender ! getDiameterMetrics(diameterRequestReceivedCounter, paramList)
          case "diameterAnswerReceived" => sender ! getDiameterMetrics(diameterAnswerReceivedCounter, paramList)
          case "diameterRequestTimeout" => sender ! getDiameterMetrics(diameterRequestTimeoutCounter, paramList)
          case "diameterAnswerSent" => sender ! getDiameterMetrics(diameterAnswerSentCounter, paramList)
          case "diameterRequestSent" => sender ! getDiameterMetrics(diameterRequestSentCounter, paramList)
          case "diameterAnswerDiscarded" => sender ! getDiameterMetrics(diameterAnswerDiscardedCounter, paramList)
          
          case "diameterRequestDropped" => sender ! getDiameterMetrics(diameterRequestDroppedCounter, paramList)
          
          case "diameterHandlerServer" => sender ! getDiameterMetrics(diameterHandlerServerCounter, paramList)
          case "diameterHandlerClient" => sender ! getDiameterMetrics(diameterHandlerClientCounter, paramList)
          case "diameterHandlerClientTimeout" => sender ! getDiameterMetrics(diameterHandlerClientTimeoutCounter, paramList)
        }
      } 
      catch {
        case e: Throwable => sender ! akka.actor.Status.Failure(e)
      }
      
    case GetDiameterPeerRequestQueueGauges =>
      sender ! diameterPeerRequestQueueSizeGauge.toMap
      
    case GetRadiusMetrics(metricName, paramList) =>
      try{
        metricName match {
          case "radiusServerRequest" => sender ! getRadiusMetrics(radiusServerRequestCounter, paramList)
          case "radiusServerDropped" => sender ! getRadiusMetrics(radiusServerDroppedCounter, paramList)
          case "radiusServerResponse" => sender ! getRadiusMetrics(radiusServerResponseCounter, paramList)
          
          case "radiusClientRequest" => sender ! getRadiusMetrics(radiusClientRequestCounter, paramList)
          case "radiusClientResponse" => sender ! getRadiusMetrics(radiusClientResponseCounter, paramList)
          case "radiusClientTimeout" => sender ! getRadiusMetrics(radiusClientTimeoutCounter, paramList)
          case "radiusClientDropped" => sender ! getRadiusMetrics(radiusClientDroppedCounter, paramList)
          
          case "radiusHandlerResponse" => sender ! getRadiusMetrics(radiusHandlerResponseCounter, paramList)
          case "radiusHandlerDropped" => sender ! getRadiusMetrics(radiusHandlerDroppedCounter, paramList)
          case "radiusHandlerRequest" => sender ! getRadiusMetrics(radiusHandlerRequestCounter, paramList)
          case "radiusHandlerRetransmission" => sender ! getRadiusMetrics(radiusHandlerRetransmissionCounter, paramList)
          case "radiusHandlerTimeout" => sender ! getRadiusMetrics(radiusHandlerTimeoutCounter, paramList)
        }
      } 
      catch {
        case e: Throwable => sender ! akka.actor.Status.Failure(e)
      }
      
    case GetRadiusServerRequestQueueGauges =>
      sender ! radiusServerRequestQueueSizeGauge
  }
}