package yaas.config

import org.json4s._
import org.json4s.jackson.JsonMethods._

case class HandlerConfig(name: String, clazz: String)

object HandlerConfigManager {

  // For deserialization of Json
  private implicit val formats = DefaultFormats
  
  private var handlerConfig = (for {
    handler <- ConfigManager.getConfigObject("handlers.json").extract[Seq[HandlerConfig]]
  } yield (handler.name -> handler.clazz)).toMap
  
  def getHandlerConfig = handlerConfig
}

