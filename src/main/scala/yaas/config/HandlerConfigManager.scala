package yaas.config

import org.json4s._
import org.json4s.jackson.JsonMethods._

/**
 * Holds an entry from Handler name to the implementing class
 */
case class HandlerConfig(name: String, clazz: String)

/**
 * Placeholder for the Handler configuration.
 * 
 * The values <code>diameterPeerConfig</code> and <code>diameterRouteConfig</code> give access to the last retrieved configuration values.
 * If a refreshed configuration is required, use the <code>get</code> methods, after calling <code>ConfigManager.reloadXX</code>
 */
object HandlerConfigManager {

  // For deserialization of Json
  private implicit val formats = DefaultFormats
  
  /**
   * Holds the handlers configuration map.
   */
  var handlerConfig = Map[String, String]()
  getHandlerConfig
  
    /**
   * Obtains the Handlers configuration reading it from the JSON configuration object.
   * 
   * If an updated version is required, make sure to call <code>ConfigManager.refresh</code> before invoking 
   */
  def getHandlerConfig = {
    handlerConfig = (for {
      handler <- ConfigManager.getConfigObject("handlers.json").extract[Seq[HandlerConfig]]
      } yield (handler.name -> handler.clazz)).toMap
    handlerConfig
  }
}

