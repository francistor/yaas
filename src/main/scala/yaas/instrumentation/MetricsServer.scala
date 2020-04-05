package yaas.instrumentation

import akka.actor.{Actor, ActorLogging, Props}
import yaas.server.RadiusActorMessages.RadiusEndpoint

/**
 * Companion object of the MetricsServer Actor, including the object definitions used there
 */
object MetricsServer {
  
  def props(): Props = Props(new MetricsServer)

  class InvalidLabelException(val msg: String) extends Exception(msg: String)
  class InvalidMetricException(val msg: String) extends Exception(msg: String)

  /*
  Definitions of messages to MetricsServer
   */

  // Re-setter messages
  case object DiameterMetricsReset
  case object RadiusMetricsReset
  case object HttpMetricsReset
  
  // Queue sizes messages
  case class DiameterPeerQueueSize(dp: String, size: Int)
  case class RadiusClientQueueSizes(sizes: Map[RadiusEndpoint, Int])
  
  // Get metrics message
  class Metrics
  object DiameterMetrics extends Metrics
  object RadiusMetrics extends Metrics
  object HttpMetrics extends Metrics
  object SessionMetrics extends Metrics
  case class GetMetrics(metricType: Metrics, metricName: String, params: List[String])

  /**
   * Defines an entry holding a metric item
   * @param keyMap Definition of the metric stored, as a set of keys->values
   * @param value the value of the metric stored
   */
  case class MetricsItem(keyMap: Map[String, String], value: Long)
  
  trait MetricKey {
    def getLabelValue(key: String): String

    def getAllLabels: List[String]

    /**
     * Given a set of labels  (e.g. List("ap", "cm")) returns the corresponding values of this MetricKey, to be used as a key
     * for aggregation. If "all", all the labels are used
     */
    def getLabelValues(labelList: List[String]): Seq[String] = labelList.map(getLabelValue)

    def getKeyMap: Map[String, String] = getAllLabels.map(l => (l, getLabelValue(l))).toMap
  }

  trait DiameterMetricKey extends MetricKey
  
  class DiameterPeerMetricsKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterMetricKey {
    override def getLabelValue(key : String): String = {
      key match {
        case "oh" => oh; case "or" => or; case "dh" => dh; case "dr" => dr; case "ap" => ap; case "cm" => cm;
        case "peer" => peer
        case l => throw new InvalidLabelException(l)
      }
    }
    override def getAllLabels = List("peer", "oh", "or", "dh", "dr", "ap", "cm")
  }
  
  /*
   * oh = origin host / "<void>"
   * or = origin realm / "<void>"
   * dh = destination host / "<void>"
   * dr = destination realm / "<void>"
   * ap = application Id (notice the id is stored as a string)
   * cm = command Code (notice the code is stored as a string)
   * rc = diameter result code (notice the code is stored as a string).
   * rt = response time (ceil(l2(rt)) in milliseconds) 
   *  
   */
  
  // Peer Metrics
  case class DiameterRequestReceivedKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterPeerMetricsKey(peer, oh, or, dh, dr, ap, cm) {
    override def getAllLabels = List("peer", "oh", "or", "dh", "dr", "ap", "cm")
  }

  case class DiameterAnswerReceivedKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String, rt: String) extends DiameterPeerMetricsKey(peer, oh, or, dh, dr, ap, cm) {
    override def getLabelValue(key : String): String = {
      key match {
        case "rt" => rt
        case "rc" => rc
        case k: String => super.getLabelValue(k)
      }
    }
    override def getAllLabels = List("peer", "oh", "or", "dh", "dr", "ap", "cm", "rc", "rt")
  }

  case class DiameterRequestTimeoutKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterPeerMetricsKey(peer, oh, or, dh, dr, ap, cm) {
    override def getAllLabels = List("peer", "oh", "or", "dh", "dr", "ap", "cm")
  }

  case class DiameterAnswerSentKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String) extends DiameterPeerMetricsKey(peer, oh, or, dh, dr, ap, cm){
    override def getLabelValue(key : String): String = {
      key match {
        case "rc" => rc
        case k: String => super.getLabelValue(k)
      }
    }
    override def getAllLabels = List("peer", "oh", "or", "dh", "dr", "ap", "cm", "rc")
  }
  
  case class DiameterRequestSentKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterPeerMetricsKey(peer, oh, or, dh, dr, ap, cm){
    override def getAllLabels = List("peer", "oh", "or", "dh", "dr", "ap", "cm")
  }

  case class DiameterAnswerDiscardedKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String) extends DiameterPeerMetricsKey(peer, oh, or, dh, dr, ap, cm){
    override def getLabelValue(key : String): String = {
      key match {
        case "rc" => rc
        case k: String => super.getLabelValue(k)
      }
    }
    override def getAllLabels = List("peer", "oh", "or", "dh", "dr", "ap", "cm", "rc")
  }

  // Router Metrics
  case class DiameterRequestDroppedKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterMetricKey {
    override def getLabelValue(key : String): String = {
        key match {
          case "oh" => oh; case "or" => or; case "dh" => dh; case "dr" => dr; case "ap" => ap; case "cm" => cm;
          case l => throw new InvalidLabelException(l)
        }
      }
    override def getAllLabels = List("oh", "or", "dh", "dr", "ap", "cm", "rc")
  }

  // Handler Metrics
  class DiameterHandlerMetricsKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterMetricKey {
    override def getLabelValue(key : String): String = {
      key match {
        case "oh" => oh; case "or" => or; case "dh" => dh; case "dr" => dr; case "ap" => ap; case "cm" => cm;
        case l => throw new InvalidLabelException(l)
      }
    }
    override def getAllLabels = List("oh", "or", "dh", "dr", "ap", "cm")
  }
  
  case class DiameterHandlerServerKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String, rt: String) extends DiameterHandlerMetricsKey(oh, or, dh, dr, ap, cm){
    override def getLabelValue(key : String): String = {
      key match {
        case "rc" => rc
        case "rt" => rt
        case k: String => super.getLabelValue(k)
      }
    }
    override def getAllLabels = List("oh", "or", "dh", "dr", "ap", "cm", "rc", "rt")
  }

  case class DiameterHandlerClientKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String, rt: String) extends DiameterHandlerMetricsKey(oh, or, dh, dr, ap, cm){
    override def getLabelValue(key : String): String = {
      key match {
        case "rc" => rc
        case "rt" => rt
        case k: String => super.getLabelValue(k)
      }
    }
    override def getAllLabels = List("oh", "or", "dh", "dr", "ap", "cm", "rc", "rt")
  }

  case class DiameterHandlerClientTimeoutKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterHandlerMetricsKey(oh, or, dh, dr, ap, cm)
  
  case class DiameterPeerQueueSizeKey(peer: String) extends DiameterMetricKey {
    override def getLabelValue(key : String): String = {
      key match {
        case "peer" => peer
        case l => throw new InvalidLabelException(l)
      }
    }
    override def getAllLabels = List("peer")
  }
  
  /////////////////////////////////////////////////////////////////////////////////////////////
  // Radius
  ////////////////////////////////////////////////////////////////////////////////////////////
  
  trait RadiusMetricKey extends MetricKey
  
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
    def getLabelValue(key : String): String = {
      key match {
        case "rh" => rh; case "rq" => rq
        case l => throw new InvalidLabelException(l)
      }
    }
    override def getAllLabels = List("rh", "rq")
  }
  case class RadiusServerDropKey(rh: String) extends RadiusMetricKey {
    def getLabelValue(key : String): String = {
      key match {
        case "rh" => rh;
        case l => throw new InvalidLabelException(l)
      }
    }
    override def getAllLabels = List("rh")
  }

  case class RadiusServerResponseKey(rh: String, rs: String) extends RadiusMetricKey {
    def getLabelValue(key : String): String = {
      key match {
        case "rh" => rh; case "rs" => rs
        case l => throw new InvalidLabelException(l)
      }
    }
    override def getAllLabels = List("rh", "rs")
  }
  
  // Client Metrics
  case class RadiusClientRequestKey(rh: String, rq: String) extends RadiusMetricKey {
    def getLabelValue(key : String): String = {
      key match {
        case "rh" => rh; case "rq" => rq
        case l => throw new InvalidLabelException(l)
      }
    }
    override def getAllLabels = List("rh", "rq")
  }

  case class RadiusClientResponseKey(rh: String, rq: String, rs: String, rt: String) extends RadiusMetricKey {
    def getLabelValue(key : String): String = {
      key match {
        case "rh" => rh; case "rq" => rq; case "rs" => rs; case "rt" => rt
        case l => throw new InvalidLabelException(l)
      }
    }
    override def getAllLabels = List("rh", "rq", "rs", "rt")
  }

  case class RadiusClientTimeoutKey(rh: String, rq: String) extends RadiusMetricKey {
    def getLabelValue(key : String): String = {
      key match {
        case "rh" => rh; case "rq" => rq
        case l => throw new InvalidLabelException(l)
      }
    }
    override def getAllLabels = List("rh", "rq")
  }

  case class RadiusClientDroppedKey(rh: String) extends RadiusMetricKey {
    def getLabelValue(key : String): String = {
      key match {
        case "rh" => rh;
        case l => throw new InvalidLabelException(l)
      }
    }
    override def getAllLabels = List("rh")
  }
  
  // Handler Metrics
  case class RadiusHandlerResponseKey(rh: String, rq: String, rs: String, rt: String) extends RadiusMetricKey {
    def getLabelValue(key : String): String = {
      key match {
        case "rh" => rh; case "rq" => rq; case "rs" => rs; case "rt" => rt
        case l => throw new InvalidLabelException(l)
      }
    }
    override def getAllLabels = List("rh", "rq", "rs", "rt")
  }

  case class RadiusHandlerDroppedKey(rh: String, rq: String) extends RadiusMetricKey {
    def getLabelValue(key : String): String = {
      key match {
        case "rh" => rh; case "rq" => rq
        case l => throw new InvalidLabelException(l)
      }
    }

    override def getAllLabels = List("rh", "rq")
  }

  case class RadiusHandlerRequestKey(rh: String, rq: String, rs: String, rt: String) extends RadiusMetricKey {
    def getLabelValue(key : String): String = {
      key match {
        case "rh" => rh; case "rq" => rq; case "rs" => rs; case "rt" => rt
        case l => throw new InvalidLabelException(l)
      }
    }
    override def getAllLabels = List("rh", "rq", "rs", "rt")
  }
  case class RadiusHandlerRetransmissionKey(group: String, rq: String) extends RadiusMetricKey {
    def getLabelValue(key : String): String = {
      key match {
        case "group" => group; case "rq" => rq
        case l => throw new InvalidLabelException(l)
      }
    }
    override def getAllLabels = List("group", "rq")
  }
  case class RadiusHandlerTimeoutKey(group: String, rq: String) extends RadiusMetricKey {
    def getLabelValue(key : String): String = {
      key match {
        case "group" => group; case "rq" => rq
        case l => throw new InvalidLabelException(l)
      }
    }
    override def getAllLabels = List("group", "rq")
  }
  
  case class RadiusClientQueueSizeKey(rh: String) extends RadiusMetricKey {
    def getLabelValue(key : String): String = {
      key match {
        case "rh" => rh
        case l => throw new InvalidLabelException(l)
      }
    }
    override def getAllLabels = List("rh")
  }

  //////////////////////////////////////////
  // Http
  //////////////////////////////////////////
  
  class HttpMetricKey(oh: String, method: String, path: String, rc: String) extends MetricKey {
    def getLabelValue(key : String): String = {
      key match {
        case "oh" => oh; case "method" => method; case "path" => path; case "rc" => rc;
        case l => throw new InvalidLabelException(l)
      }
    }
    override def getAllLabels = List("oh", "method", "path", "rc")
  }
  
  case class HttpOperationKey(oh: String, method: String, path: String, rc: String) extends HttpMetricKey(oh, method, path, rc)

  //////////////////////////////////////////
  // SessionGroups
  //////////////////////////////////////////
  case class SessionGroupMetricKey(labelValues: Map[String, String]) extends MetricKey {
    override def getLabelValue(key: String): String = labelValues.getOrElse(key, "")

    override def getAllLabels = List("g0", "g1", "g2", "g3", "g4")
  }
}

/**
 * Holds all the Metrics, which are updated by the reception of Actor messages and can be retrieved also as repsonse
 * to some messages
 */
class MetricsServer extends Actor with ActorLogging {
  
  import MetricsServer._
  import scala.collection.mutable

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Diameter
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Peer Counters
  
  // Contains the counter value for each received combination of keys
  private var diameterRequestReceivedCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  private var diameterAnswerReceivedCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  private var diameterRequestTimeoutCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  private var diameterAnswerSentCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  private var diameterRequestSentCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  private var diameterAnswerDiscardedCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  
  // Router Metrics
  private var diameterRequestDroppedCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  
  // Handler Metrics
  private var diameterHandlerServerCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  private var diameterHandlerClientCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  private var diameterHandlerClientTimeoutCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  
  // Queue Metrics
  private var diameterPeerQueueSizeGauge = mutable.Map[DiameterPeerQueueSizeKey, Long]()
  
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Radius
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  private var radiusServerRequestCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  private var radiusServerDroppedCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  private var radiusServerResponseCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  
  private var radiusClientRequestCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  private var radiusClientResponseCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  private var radiusClientTimeoutCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  private var radiusClientDroppedCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  
  private var radiusHandlerResponseCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  private var radiusHandlerDroppedCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  private var radiusHandlerRequestCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  private var radiusHandlerRetransmissionCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  private var radiusHandlerTimeoutCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  
  private var radiusClientQueueSizeGauge = mutable.Map[RadiusClientQueueSizeKey, Long]()
  
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Http
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  private var httpOperationsCounter = mutable.Map[HttpMetricKey, Long]().withDefaultValue(0)
  
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


  /**
   * Given a set of labels, aggregate the metrics on those labels
   * @param metricsMap
   * @param labels
   * @tparam A
   * @return
   */
  def getMetrics[A <: MetricKey](metricsMap: mutable.Map[A, Long], labels: List[String]): List[MetricsItem] = {
    if(metricsMap.isEmpty) List()
    else {
      // If labels is empty then use all labels. If a single elemement "all", do aggregate all entries.
      // The list of all labels may be taken from any metric in the list
      val myLabels =
        if (labels.isEmpty) metricsMap.keys.head.getAllLabels
        else if(labels.equals(List("all"))) List[String]()
        else labels

      metricsMap.groupBy { case (metricsKey, _) => metricsKey.getLabelValues(myLabels) } // Map of label values to a metrics submap with those values for the labels
        .map { case (k, v) => MetricsItem(myLabels.zip(k).toMap, v.values.sum) } // Generate a MetricsItem with keyMap label=labelValue and the sum of values
        .toList
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Receive
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////// 
  
  def receive: Receive = {
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

    case DiameterPeerQueueSize(pn, size) => 
      if(size == -1) diameterPeerQueueSizeGauge.remove(DiameterPeerQueueSizeKey(pn)) else diameterPeerQueueSizeGauge(DiameterPeerQueueSizeKey(pn)) = size

    // Radius
    case s: RadiusServerRequestKey => radiusServerRequestCounter(s) = radiusServerRequestCounter(s) + 1
    case s: RadiusServerDropKey => radiusServerDroppedCounter(s) = radiusServerDroppedCounter(s) + 1
    case s: RadiusServerResponseKey => radiusServerResponseCounter(s) = radiusServerResponseCounter(s) + 1
    
    case s: RadiusClientRequestKey => radiusClientRequestCounter(s) = radiusClientRequestCounter(s) + 1
    case s: RadiusClientResponseKey => radiusClientResponseCounter(s) = radiusClientResponseCounter(s) + 1
    case s: RadiusClientTimeoutKey => radiusClientTimeoutCounter(s) = radiusClientTimeoutCounter(s) + 1
    case s: RadiusClientDroppedKey => radiusClientDroppedCounter(s) = radiusClientDroppedCounter(s) + 1
    
    case s: RadiusHandlerRequestKey => radiusHandlerRequestCounter(s) = radiusHandlerRequestCounter(s) + 1
    case s: RadiusHandlerResponseKey => radiusHandlerResponseCounter(s) = radiusHandlerResponseCounter(s) + 1
    case s: RadiusHandlerDroppedKey => radiusHandlerDroppedCounter(s) = radiusHandlerDroppedCounter(s) + 1
    case s: RadiusHandlerTimeoutKey => radiusHandlerTimeoutCounter(s) = radiusHandlerTimeoutCounter(s) + 1
    case s: RadiusHandlerRetransmissionKey => radiusHandlerRetransmissionCounter(s) = radiusHandlerRetransmissionCounter(s) + 1

    case RadiusClientQueueSizes(sizes) =>
      radiusClientQueueSizeGauge = mutable.Map[RadiusClientQueueSizeKey, Long]() ++ sizes.map { case (endPoint, qSize) => (RadiusClientQueueSizeKey(s"${endPoint.ipAddress}:${endPoint.port}"), qSize.toLong)}

    // Http
    case s: HttpOperationKey => httpOperationsCounter(s) = httpOperationsCounter(s) + 1
    
    // Reset
    case DiameterMetricsReset =>      
      diameterRequestReceivedCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
      diameterAnswerReceivedCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
      diameterRequestTimeoutCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
      diameterAnswerSentCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
      diameterRequestSentCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
      diameterAnswerDiscardedCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)

      diameterRequestDroppedCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  
      diameterHandlerServerCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
      diameterHandlerClientCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
      diameterHandlerClientTimeoutCounter = mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
      
    case RadiusMetricsReset =>
      radiusServerRequestCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      radiusServerDroppedCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      radiusServerResponseCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  
      radiusClientRequestCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      radiusClientResponseCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      radiusClientTimeoutCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      radiusClientDroppedCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
  
      radiusHandlerResponseCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      radiusHandlerDroppedCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      radiusHandlerRequestCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      radiusHandlerRetransmissionCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      radiusHandlerTimeoutCounter = mutable.Map[RadiusMetricKey, Long]().withDefaultValue(0)
      
    case HttpMetricsReset =>
      httpOperationsCounter = mutable.Map[HttpMetricKey, Long]().withDefaultValue(0)

    /*
     * Statistics query
     */
    case GetMetrics(DiameterMetrics, metricName, paramList) =>
      try{
        metricName match {
          case "diameterRequestReceived" => sender ! getMetrics(diameterRequestReceivedCounter, paramList)
          case "diameterAnswerReceived" => sender ! getMetrics(diameterAnswerReceivedCounter, paramList)
          case "diameterRequestTimeout" => sender ! getMetrics(diameterRequestTimeoutCounter, paramList)
          case "diameterAnswerSent" => sender ! getMetrics(diameterAnswerSentCounter, paramList)
          case "diameterRequestSent" => sender ! getMetrics(diameterRequestSentCounter, paramList)
          case "diameterAnswerDiscarded" => sender ! getMetrics(diameterAnswerDiscardedCounter, paramList)
          
          case "diameterRequestDropped" => sender ! getMetrics(diameterRequestDroppedCounter, paramList)
          
          case "diameterHandlerServer" => sender ! getMetrics(diameterHandlerServerCounter, paramList)
          case "diameterHandlerClient" => sender ! getMetrics(diameterHandlerClientCounter, paramList)
          case "diameterHandlerClientTimeout" => sender ! getMetrics(diameterHandlerClientTimeoutCounter, paramList)
          
          case "diameterPeerQueueSize" => sender ! getMetrics(diameterPeerQueueSizeGauge, paramList)

          case _ => throw new InvalidMetricException(s"Unknown metric $metricName")
        }
      } 
      catch {
        case e: Throwable => sender ! akka.actor.Status.Failure(e)
      }
      
    case GetMetrics(RadiusMetrics, metricName, paramList) =>
      try{
        metricName match {
          case "radiusServerRequest" => sender ! getMetrics(radiusServerRequestCounter, paramList)
          case "radiusServerDropped" => sender ! getMetrics(radiusServerDroppedCounter, paramList)
          case "radiusServerResponse" => sender ! getMetrics(radiusServerResponseCounter, paramList)
          
          case "radiusClientRequest" => sender ! getMetrics(radiusClientRequestCounter, paramList)
          case "radiusClientResponse" => sender ! getMetrics(radiusClientResponseCounter, paramList)
          case "radiusClientTimeout" => sender ! getMetrics(radiusClientTimeoutCounter, paramList)
          case "radiusClientDropped" => sender ! getMetrics(radiusClientDroppedCounter, paramList)
          
          case "radiusHandlerResponse" => sender ! getMetrics(radiusHandlerResponseCounter, paramList)
          case "radiusHandlerDropped" => sender ! getMetrics(radiusHandlerDroppedCounter, paramList)
          case "radiusHandlerRequest" => sender ! getMetrics(radiusHandlerRequestCounter, paramList)
          case "radiusHandlerRetransmission" => sender ! getMetrics(radiusHandlerRetransmissionCounter, paramList)
          case "radiusHandlerTimeout" => sender ! getMetrics(radiusHandlerTimeoutCounter, paramList)
          
          case "radiusClientQueueSize" => sender ! getMetrics(radiusClientQueueSizeGauge, paramList)

          case _ => throw new InvalidMetricException(s"Unknown metric $metricName")
        }
      } 
      catch {
        case e: Throwable => sender ! akka.actor.Status.Failure(e)
      }
      
    case GetMetrics(HttpMetrics, metricName, paramList) =>
      try{
        metricName match {
          case "httpOperation" => sender ! getMetrics(httpOperationsCounter, paramList)

          case _ => throw new InvalidMetricException(s"Unknown metric $metricName")
        }
      }
      catch {
        case e: Throwable => sender ! akka.actor.Status.Failure(e)
      }
      
    // paramList will be a list such as ["g1", "g2", ...]
    case GetMetrics(SessionMetrics, metricName, paramList) =>
      try {
        metricName match {
          case "sessions" =>
            // Generate a Map[SessionGroupMetricKey, Long]
            val metricsMap = mutable.Map[SessionGroupMetricKey, Long]() ++ yaas.database.SessionDatabase.getSessionGroups.map(item => {
              val keyValues = item._1.split(",")

              val keyMap = (for {
                i <- 0 until keyValues.length
              } yield (s"g$i", keyValues(i))).toMap

              (SessionGroupMetricKey(keyMap), item._2)
            }).toMap

            sender ! getMetrics(metricsMap, paramList)

          case _ => throw new InvalidMetricException(s"Unknown metric $metricName")
        }
      }
      catch {
        case e: Throwable => sender ! akka.actor.Status.Failure(e)
      }

    case _: Any =>
  }
}