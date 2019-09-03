package yaas.instrumentation

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props}
import yaas.server.RadiusActorMessages.RadiusEndpoint

object MetricsServer {
  
  def props() = Props(new MetricsServer)
  
  case object DiameterMetricsReset
  case object RadiusMetricsReset
  case object HttpMetricsReset
  
  // Queue sizes messages
  case class DiameterPeerQueueSize(dp: String, size: Int)
  case class RadiusClientQueueSizes(sizes: Map[RadiusEndpoint, Int])
  
  // Get metrics messages
  class Metrics
  object DiameterMetrics extends Metrics
  object RadiusMetrics extends Metrics
  object HttpMetrics extends Metrics
  object SessionMetrics extends Metrics
  case class GetMetrics(metricType: Metrics, metricName: String, params: List[String])
  
  case class MetricsItem(keyMap: Map[String, String], value: Long)
  
  trait MetricKey {
    def getValue(key : String) :String 
    def getAggrValue(keyList : List[String]) : List[String]
  }
  
  trait DiameterMetricKey extends MetricKey {    
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
  
  class DiameterPeerMetricsKey(peer: String, oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterMetricKey {
    override def getValue(key : String) = {
      key match {
        case "oh" => oh; case "or" => or; case "dh" => dh; case "dr" => dr; case "ap" => ap; case "cm" => cm;
        case "peer" => peer
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
  case class DiameterRequestDroppedKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterMetricKey {
    override def getValue(key : String) = {
        key match {
          case "oh" => oh; case "or" => or; case "dh" => dh; case "dr" => dr; case "ap" => ap; case "cm" => cm;
        }
      }
  }
  
  
  // Handler Metrics
  class DiameterHandlerMetricsKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterMetricKey {
    override def getValue(key : String) = {
      key match {
        case "oh" => oh; case "or" => or; case "dh" => dh; case "dr" => dr; case "ap" => ap; case "cm" => cm;
      }
    }
  }
  
  case class DiameterHandlerServerKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String, rt: String) extends DiameterHandlerMetricsKey(oh, or, dh, dr, ap, cm){
    override def getValue(key : String) = {
      key match {
        case "rc" => rc
        case "rt" => rt
        case k: String => super.getValue(k)
      }
    }
  }
  case class DiameterHandlerClientKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String, rc: String, rt: String) extends DiameterHandlerMetricsKey(oh, or, dh, dr, ap, cm){
    override def getValue(key : String) = {
      key match {
        case "rc" => rc
        case "rt" => rt
        case k: String => super.getValue(k)
      }
    }
  }
  case class DiameterHandlerClientTimeoutKey(oh: String, or: String, dh: String, dr: String, ap: String, cm: String) extends DiameterHandlerMetricsKey(oh, or, dh, dr, ap, cm)
  
  case class DiameterPeerQueueSizeKey(peer: String) extends DiameterMetricKey {
    override def getValue(key : String) = {
      key match {
        case "peer" => peer
      }
    }
  }
  
  /////////////////////////////////////////////////////////////////////////////////////////////
  // Radius
  ////////////////////////////////////////////////////////////////////////////////////////////
  
  trait RadiusMetricKey extends MetricKey {
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
  
  case class RadiusClientQueueSizeKey(rh: String) extends RadiusMetricKey {
    def getValue(key : String) = {
      key match {
        case "rh" => rh
      }
    }
  }

  
  //////////////////////////////////////////
  // Http
  //////////////////////////////////////////
  
  class HttpMetricKey(oh: String, method: String, path: String, rc: String) extends MetricKey {
    def getValue(key : String) = {
      key match {
        case "oh" => oh; case "method" => method; case "path" => path; case "rc" => rc;
      }
    }
    
    /**
     * Given a set of keys (e.g. List("oh", "rc")) returns the corresponding values of this HttpMetricsItem, 
     * to be used as a key for aggregation
     */
    def getAggrValue(keyList : List[String]) = {
      for {
        key <- keyList
      } yield getValue(key)
    }
  }
  
  case class HttpOperationKey(oh: String, method: String, path: String, rc: String) extends HttpMetricKey(oh, method, path, rc)
  
} 

class MetricsServer extends Actor with ActorLogging {
  
  import MetricsServer._

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Diameter
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Peer Counters
  
  // Contain the counter value for each received combination of keys
  private var diameterRequestReceivedCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  private var diameterAnswerReceivedCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  private var diameterRequestTimeoutCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  private var diameterAnswerSentCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  private var diameterRequestSentCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  private var diameterAnswerDiscardedCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  
  // Router Metrics
  private var diameterRequestDroppedCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  
  // Handler Metrics
  private var diameterHandlerServerCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  private var diameterHandlerClientCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  private var diameterHandlerClientTimeoutCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  
  // Queue Metrics
  private var diameterPeerQueueSizeGauge = scala.collection.mutable.Map[DiameterPeerQueueSizeKey, Long]()
  
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
  
  private var radiusClientQueueSizeGauge = scala.collection.mutable.Map[RadiusClientQueueSizeKey, Long]()
  
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Http
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  private var httpOperationsCounter = scala.collection.mutable.Map[HttpMetricKey, Long]().withDefaultValue(0)
  
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  
  def getMetrics[A <: MetricKey](metricsMap: scala.collection.mutable.Map[A, Long], keys: List[String]): List[MetricsItem] = {
    // The key for the aggregation is the list of values for the specified keys
    metricsMap.groupBy{case (metricsKey, value) => metricsKey.getAggrValue(keys)} 
    .map{case (k, v) => MetricsItem(keys.zip(k).toMap, v.values.reduce(_+_))}
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
      // Convert the map to mutable
      val mutableSizes = scala.collection.mutable.Map[RadiusEndpoint, Int]()
      sizes.map(size => mutableSizes += size)
      radiusClientQueueSizeGauge = for{(endPoint, size) <- mutableSizes} yield (RadiusClientQueueSizeKey(s"${endPoint.ipAddress}:${endPoint.port}"), size.toLong)
    
    // Http
    case s: HttpOperationKey => httpOperationsCounter(s) = httpOperationsCounter(s) + 1
    
    // Reset
    case DiameterMetricsReset =>      
      diameterRequestReceivedCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
      diameterAnswerReceivedCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
      diameterRequestTimeoutCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
      diameterAnswerSentCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
      diameterRequestSentCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
      diameterAnswerDiscardedCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)

      diameterRequestDroppedCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  
      diameterHandlerServerCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
      diameterHandlerClientCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
      diameterHandlerClientTimeoutCounter = scala.collection.mutable.Map[DiameterMetricKey, Long]().withDefaultValue(0)
  
      diameterPeerQueueSizeGauge = scala.collection.mutable.Map[DiameterPeerQueueSizeKey, Long]()
      
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
      
    case HttpMetricsReset =>
      httpOperationsCounter = scala.collection.mutable.Map[HttpMetricKey, Long]().withDefaultValue(0)

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
        }
      } 
      catch {
        case e: Throwable => sender ! akka.actor.Status.Failure(e)
      }
      
    case GetMetrics(HttpMetrics, metricName, paramList) =>
      try{
        metricName match {
          case "httpOperation" => sender ! getMetrics(httpOperationsCounter, paramList)
        }
      }
      catch {
        case e: Throwable => sender ! akka.actor.Status.Failure(e)
      }
      
    // paramList will be a list of integers corresponding to the positions to be aggregated
    case GetMetrics(SessionMetrics, metricName, paramList) =>
     try {
        metricName match {
          case "sessions" => 
            // getSessions : List[(String, Long)], where "String" is "keyValue1,keyValue2,..."
            // metricItems : List[MetricsItem], where a MetricsItem contains a Map[key, keyValue] and a counter
            val metricsItems = yaas.database.SessionDatabase.getSessionGroups.map(item => {
              val keyValues = item._1.split(",")
              val keyMap = (for {
                i <- 0 to (keyValues.length - 1)
              } yield (s"g$i", keyValues(i))).toMap
              
              MetricsItem(keyMap, item._2)
            })
            
            // Now we must aggregate the results as specified in the paramsList
            val aggregatedMetricItems = 
              if(paramList.length == 0 || paramList.length > 4) metricsItems
              
            else metricsItems.
              // Group by same values of parameter values, turned into a map key->value
              // This generates a Map[Map[String, String] -> List[MetricsItem]]
              groupBy(item => paramList.map(param => (param, item.keyMap(param))).toMap).
              // Which has to be turned into a List[MetricsItem]
              map{
                case (keyMap, values) => MetricsItem(keyMap, values.map(_.value).reduce(_+_))
              }
            
            sender ! aggregatedMetricItems.toList
        }
      }
      catch {
        case e: Throwable => sender ! akka.actor.Status.Failure(e)
      }
      
    case a: Any => 
      println(".........................")
      println(a.getClass.toString)
      println(".........................")
  }
}