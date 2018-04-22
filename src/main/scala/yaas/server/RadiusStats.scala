package yaas.server


/**
 * This object has to be encapsulated in an Actor, since it is not thread safe
 */
object RadiusStats {
  
   /*
   * rm = remote host (ip:port)
   * co = code
   * rt = response time (ceil(l2(rt)) in milliseconds) <void> if not answered
   *  
   */
  
  class RadiusStatItem(rm: String, co: String, rt: String) {
    def getKeyValue(key: String) = {
      key match {
        case "rm" => rm
        case "co" => co
        case "rt" => rt
      }
    }
    
    /**
     * Given a set of keys (e.g. List("io", "ra")) returns the corresponding values of this RadiusStatItem, to be used as a key
     * for aggregation
     */
    def getAggrKeyValue(keyList : List[String]) = {
      for {
        key <- keyList
      } yield getKeyValue(key)
    }
  }
  
  case class RadiusServerStatItem(rm: String, co: String, rt: String) extends RadiusStatItem(rm, co, rt)
  case class RadiusClientStatItem(rm: String, co: String, rt: String) extends RadiusStatItem(rm, co, rt)
  
  /////////////////////////////////////////////////////////////////////////////
  
  /*
   * Stats are kept here
   */
  val radiusServerStats = scala.collection.mutable.Map[RadiusServerStatItem, Long]().withDefaultValue(0)
  val radiusClientStats = scala.collection.mutable.Map[RadiusClientStatItem, Long]().withDefaultValue(0)
  
  /**
   * Get stats items aggregated by the specified keys
   */
  def getRadiusServerStats(keys : List[String]) = radiusClientStats.groupBy{ case (statItem, value) => statItem.getAggrKeyValue(keys)}.map{case (k, v) => (k, v.values.reduce(_+_))}
  def getRadiusClientStats(keys : List[String]) = radiusServerStats.groupBy{ case (statItem, value) => statItem.getAggrKeyValue(keys)}.map{case (k, v) => (k, v.values.reduce(_+_))}
}