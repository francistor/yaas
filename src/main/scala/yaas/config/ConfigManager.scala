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
 * Reads and caches JSON configuration files from java resources, files or URLs. The syntax MUST be Json.
 * 
 * The class is a singleton and thread safe, thus usable anywhere in the code.
 * 
 * <code>getConfigObject</code> retrieves the contents of the specified configuration file from the cache, or reads it
 * if not available there, and caches it.
 * 
 * Entries are refreshed using <code>reloadConfigObject(objectName)</code> or <code>reloadAllConfigObjects</code>
 * 
 * The rules for where to get the configuration objects are stored in the file specified by the aaa.configSearchRulesLocation
 * property, that may point to a java resource or a URL
 * 
 * Example. The name of the resource is taken from the regular expression group
 * <code><br>
 * [<br>
 * 	{"nameRegex": "Gx/(.*)", 	"locationType": "URL", "base": "http://localhost:8099/"},<br>
 *	{"nameRegex": "Gy/(.*)", 	"locationType": "URL", "base": "file:///etc/yaas/Gy/"},<br>
 *	{"nameRegex": "(.*)", 		"locationType": "resource"}<br>
 * ]<br>
 * </code>
 * 
 */
object ConfigManager {
  
  val separator = System.lineSeparator
  val ti = System.getProperty("instance")
  val instance = if(ti == null) "default" else ti
  
  val log = LoggerFactory.getLogger(ConfigManager.getClass)
  
  // Case classes for JSON deserialization of the configSearchRules file
  case class SearchRule(nameRegex: Regex, locationType: String, base: Option[String])
		
  class SearchRuleSerializer extends CustomSerializer[SearchRule](implicit jsonFormats /* If I name this "formats" get an error in Scala 2.11 */ => (
		{ case jv: JValue => SearchRule((jv \ "nameRegex").extract[String].r, (jv \ "locationType").extract[String], (jv \ "base").extract[Option[String]]) },
		// Not used
		{ case v : SearchRule => JObject()}
		))
  
	val config = ConfigFactory.load()
	val configSearchRulesLocation = config.getString("aaa.configSearchRulesLocation")
	
	val configSearchRulesJson = readConfigSearchRules(configSearchRulesLocation)
  
  // Parse the Json that specifies where to get config objects from
  implicit val jsonFormats = DefaultFormats + new SearchRuleSerializer
  val rules = configSearchRulesJson.extract[List[SearchRule]]
	
	// Cache of read files
  // Concurrent thread-safe map
  val configObjectCache = new scala.collection.concurrent.TrieMap[String, JValue]
	  
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
      
  /**
   * Retrieves the configSearchRules. Tries first with instance name, then without instance name
   */
  private def readConfigSearchRules(configSearchRulesLocation: String) = {
    
    def lookUp(modLocation: String) = {
      Try(new URL(modLocation)) match {
        case Success(url) => 
          log.info(s"Bootstraping config from URL $modLocation")
          parse(Source.fromURL(url).getLines.flatMap(l => if(l.trim.startsWith("#") || l.trim.startsWith("//")) Seq() else Seq(l)).mkString(separator))
    
        case Failure(_) => 
          log.info(s"Bootstraping config from resource $modLocation")
          parse(Source.fromInputStream(getClass.getResourceAsStream(modLocation)).getLines.flatMap(l => if(l.trim.startsWith("#") || l.trim.startsWith("//")) Seq() else Seq(l)).mkString(separator))
          //Scala 1.12 parse(Source.fromResource(configSearchRulesLocation).mkString)
      }
    }
    
    val r = "(.*/)(.*)".r
    val (path, file) = if(configSearchRulesLocation.matches(r.regex)){
      val m = r.findFirstMatchIn(configSearchRulesLocation).get
      (m.group(1), m.group(2))
    } else ("/", configSearchRulesLocation)
      
    Try(lookUp(path + instance + "/" + file)).orElse(Try(lookUp(path + file))) match {
      case Success(j) =>
        j
       
      case Failure(_) =>
        throw new java.util.NoSuchElementException(configSearchRulesLocation + "not found")
    }
  }
  
  /*
   * Retrieves the specified configured object name
   */
  private def readConfigObject(objectName: String): JValue = { 	
    
    def lookUp(modObjectName: String) = {
      rules.collectFirst(
        {
          case SearchRule(nameRegex, locationType, base) if modObjectName.matches(nameRegex.regex) =>
            if(locationType == "URL"){
              // base + group found in objectName following nameRegex
              val url = base.get +  nameRegex.findFirstMatchIn(modObjectName).get.group(1)
              log.info(s"Reading $modObjectName from URL $url")
              parse(Source.fromURL(url).getLines.flatMap(l => if(l.trim.startsWith("#") || l.trim.startsWith("//")) Seq() else Seq(l)).mkString(separator))
            }
            else {
              val resName = nameRegex.findFirstMatchIn(modObjectName).get.group(1)
              log.info(s"Reading $modObjectName from resource $resName")
              // Remove comments
              parse(Source.fromInputStream(getClass.getResourceAsStream("/" + resName)).getLines.flatMap(l => if(l.trim.startsWith("#") || l.trim.startsWith("//")) Seq() else Seq(l)).mkString(separator))
              // Scala 2.12 parse(Source.fromResource(resName).getLines.flatMap(l => if(l.trim.startsWith("#") || l.trim.startsWith("//")) Seq() else Seq(l)).mkString(separator))
            }
        }
      )
    }
    
    // Try instance specific first. Then, regular object name
    Try(lookUp(s"$instance/$objectName")).orElse(Try(lookUp(objectName))) match {
      case Success(Some(j)) =>
        configObjectCache(objectName) = j
        j
        
      case _ =>
        throw new java.util.NoSuchElementException(objectName)
    }
    
  }

  /**
   * To be used by the applications to get the configuration object.
   * 
   * Throws java.util.NoSuchElementException if the object name is not matched by any
   * name regular expression, or IOException if could not be retrieved.
   * 
   * @return The JSON contents of the object.
   */
	def getConfigObject(objectName: String): JValue = { 
	  configObjectCache.getOrElse(objectName, readConfigObject(objectName))
	}
	
	/**
	 * Forces the reloading of the specific configuration object.
	 * 
	 * Throws java.util.NoSuchElementException if the object name is not matched by any
   * name regular expression, or IOException if could not be retrieved.
	 */
	def reloadConfigObject(objectName: String) = {
	  readConfigObject(objectName)
	}
	
	/**
	 * Forces the reloading of all configuration objects.
	 * 
	 */
	def reloadAllConfigObjects = {
	  for(objectName <- configObjectCache.keySet) configObjectCache(objectName) = readConfigObject(objectName)
	}
}