package yaas.config

import com.typesafe.config.ConfigFactory
import java.nio.file._
import scala.io.Source
import org.json4s._
import org.json4s.jackson.JsonMethods._
import scala.util.{Try, Success, Failure}

/**
 * Reads and caches configuration files
 * 
 * getConfigObject retrieves the contents of the specified configuration file from the cache, or reads it
 * if not available there, and caches it.
 */
object ConfigManager {
  
	val config = ConfigFactory.load()
	
	// Cache of read files
	val configObjects = scala.collection.mutable.Map[String, JValue]()
	  
  // Read mandatory configuration objects
  Array(
      "diameterDictionary.json", 
      "diameterHandlers.json",
      "diameterPeers.json",
      "diameterRoutes.json",
      "diameterServer.json",
      "radiusDictionary.json",
      "radiusServer.json",
      "radiusServers.json",
      "radiusClients.json"
      ).foreach(readConfigObject(_))
  
  private def readConfigObject(objectName: String): JValue = { 			
  	// Parse JSON
    val json = parse(Source.fromResource(objectName).mkString)
    configObjects(objectName) = json
  	json
  }

	/**
	 * To be used by the applications to get a configuration file
	 */
	def getConfigObject(objectName: String): JValue = {
	  configObjects.getOrElse(objectName, readConfigObject(objectName))
	}
}