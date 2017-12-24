package diameterServer

import com.typesafe.config.ConfigFactory
import java.nio.file._
import java.io.File
import scala.io.Source
import org.json4s._
import org.json4s.jackson.JsonMethods._
import scala.util.{Try, Success, Failure}

// TODO: Turn this into another Actor
/**
 * Reads and caches configuration files
 * 
 * getConfigObject retrieves the contents of the specified configuration file from the cache, or reads it
 * if not available there, and caches it.
 */
object ConfigManager {
  
  // To be used later. We'll get the configuration file directory from application.conf
	val config = ConfigFactory.load()
	
	// Cache of read files
	val configObjects = scala.collection.mutable.Map[String, JValue]()
	  
  // Read mandatory configuration objects
  Array(
      "diameterDictionary.json", 
      "diameterHandlers.json",
      "diameterPeers.json",
      "diameterRoutes.json",
      "diameterServer.json"
      ).foreach(_ => readConfigObject(_))
  
  private def readConfigObject(objectName: String): JValue = {
    val source = Source.fromResource(objectName)
  			
  	// Parse JSON
  	Try(parse(source.mkString)) match {
  	  case Success(json) => 
  	    configObjects(objectName) = json
  	    json
  	  case Failure(ex) => 
  	    throw new java.text.ParseException(ex.getMessage, 0)
  	}
  }

	/**
	 * To be used by the applications to get a configuration file
	 */
	def getConfigObject(objectName: String):JValue = {
	  if(configObjects.contains(objectName)) configObjects(objectName)
	  else readConfigObject(objectName)
	}
}