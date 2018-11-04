package yaas.config

import com.typesafe.config.ConfigFactory
import java.nio.file._
import java.net.URL
import scala.io.Source
import scala.util.Try
import scala.util.matching.Regex
import org.json4s._
import org.json4s.jackson.JsonMethods._
import scala.util.{Try, Success, Failure}

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Reads and caches configuration files from java resources, files or URL
 * 
 * getConfigObject retrieves the contents of the specified configuration file from the cache, or reads it
 * if not available there, and caches it.
 * 
 * The rules for where to get the configuration objects are stored in the file specified by the aaa.configSearchRulesLocation
 * property, that may point to a java resource or a URL
 * 
 * Example. The name of the resource is taken from the regular expression group
 * [
 * 	{"nameRegex": "Gx/(.*)", 	"locationType": "URL", "base": "http://localhost:8099/"},
 *	{"nameRegex": "Gy/(.*)", 	"locationType": "URL", "base": "file:///etc/yaas/Gy/"},
 *	{"nameRegex": "(.*)", 		"locationType": "resource"}
 * ]
 * 
 */
object ConfigManager {
  
  val separator = System.lineSeparator
  
  val log = LoggerFactory.getLogger(ConfigManager.getClass)
  
  // Case classes for JSON deserialization
  case class SearchRule(nameRegex: Regex, locationType: String, base: Option[String])
  class SearchRuleSerializer extends CustomSerializer[SearchRule](implicit formats => (
		{ case jv: JValue => SearchRule((jv \ "nameRegex").extract[String].r, (jv \ "locationType").extract[String], (jv \ "base").extract[Option[String]]) },
		{ case v : SearchRule => JObject()}
		))
  
	val config = ConfigFactory.load()
	val configSearchRulesLocation = config.getString("aaa.configSearchRulesLocation")
	
	// Try to parse bootstrapLocation as a URL. Otherwise interpret as a resource file in classpah
	val configSearchRulesJson = Try(new URL(configSearchRulesLocation)) match {
    case Success(url) => 
      log.info(s"Bootstraping config from URL $configSearchRulesLocation")
      parse(Source.fromURL(url).mkString)
    case Failure(_) => 
      log.info(s"Bootstraping config from resource $configSearchRulesLocation")
      parse(Source.fromResource(configSearchRulesLocation).mkString)
  }
  
  // Parse the Json that specifies where to get config objects from
  implicit var jsonFormats = DefaultFormats + new SearchRuleSerializer
  val rules = configSearchRulesJson.extract[List[SearchRule]]
	
	// Cache of read files
	val configObjectCache = scala.collection.mutable.Map[String, JValue]()
	  
  // Read mandatory configuration objects
  Array(
      "diameterDictionary.json", 
      "diameterPeers.json",
      "diameterRoutes.json",
      "diameterServer.json",
      "radiusDictionary.json",
      "radiusServer.json",
      "radiusServers.json",
      "radiusClients.json",
      "handlers.json"
      ).foreach(readConfigObject(_))
  
  /*
   * Retrieves the specified configured object name
   */
  private def readConfigObject(objectName: String): JValue = { 	
    
    val co = rules.collectFirst(
      {
        case SearchRule(nameRegex, locationType, base) if objectName.matches(nameRegex.regex) =>
          if(locationType == "URL"){
            val url = base.get +  nameRegex.findFirstMatchIn(objectName).get.group(1)
            log.info(s"Reading $objectName from URL $url")
            // Remove comments
            parse(Source.fromURL(url).getLines.flatMap(l => if(l.trim.startsWith("#") || l.trim.startsWith("//")) Seq() else Seq(l)).mkString(separator))
          }
          else {
            val resName = nameRegex.findFirstMatchIn(objectName).get.group(1)
            log.info(s"Reading $objectName from resource $resName")
            // Remove comments
            parse(Source.fromResource(resName).getLines.flatMap(l => if(l.trim.startsWith("#") || l.trim.startsWith("//")) Seq() else Seq(l)).mkString(separator))
          }
      }
    )
    
    co match {
      case Some(j) => 
        configObjectCache(objectName) = j
        j
      case None => throw new java.util.NoSuchElementException(objectName)
    }
  }

	/**
	 * To be used by the applications to get a configuration file
	 */
	def getConfigObject(objectName: String): JValue = {
	  configObjectCache.getOrElse(objectName, readConfigObject(objectName))
	}
}