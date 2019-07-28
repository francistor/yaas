package yaas.config

import org.json4s._
import org.json4s.jackson.JsonMethods._

/**
 * Holds an entry from Handler name to the implementing class
 */
case class HandlerConfigEntry(name: String, clazz: String, config: Option[String])

/**
 * Placeholder for the Handler configuration.
 * 
 */
object HandlerConfigManager {

  // For deserialization of Json
  private implicit val formats = DefaultFormats
  
  /**
   * Holds the handlers configuration map.
   */
  var handlerConfig = Map[String, HandlerConfigEntry]()
  updateHandlerConfig(true)
  
  /**
   * Obtains the Handlers configuration reading it from the JSON configuration object.
   * 
   * If an updated version is required, make sure to call <code>ConfigManager.refresh</code> before invoking 
   */
  def updateHandlerConfig(withReload: Boolean) = {
    val jHandlers = if(withReload) ConfigManager.reloadConfigObject("handlers.json") else ConfigManager.getConfigObject("handlers.json")
    handlerConfig = (for {
      handler <- jHandlers.extract[Seq[HandlerConfigEntry]]
      } yield (handler.name -> handler)).toMap
  }
}

