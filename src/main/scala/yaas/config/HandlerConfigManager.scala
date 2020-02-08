package yaas.config

import org.json4s._

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
   * Obtains the Handlers configuration reading it from the JSON configuration object. If <code>withReload</code>
   * is true, forces reading from the origin, which may not be needed if a previous
   * <code>ConfigManager.reloadAllConfigObjects</code> has been invoked
   */
  def updateHandlerConfig(withReload: Boolean) = {
    val jHandlers = if(withReload) ConfigManager.reloadConfigObjectAsJson("handlers.json")
                    else ConfigManager.getConfigObjectAsJson("handlers.json")
    handlerConfig = (for {
      handler <- jHandlers.extract[Seq[HandlerConfigEntry]]
      } yield handler.name -> handler).toMap
  }
}

