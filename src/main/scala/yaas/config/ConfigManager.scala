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
 * The rules for where to get the configuration objects are stored in the configuration variable <code>configSearchRules</code>
 * that holds an array with the locations where to look for configuration objects
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
 * The contents of the configuration files may include replacements for environment of system variables ${}
 * 
 */
object ConfigManager {
  
  val var1Regex = """\$\{(.+)\}""".r
  val var2Regex = """%(.+)%""".r
  
  val separator = System.lineSeparator
  val ti = System.getProperty("instance")
  val instance = if(ti == null) "default" else ti
  
  val log = LoggerFactory.getLogger(ConfigManager.getClass)
  
  // Case classes for JSON deserialization of the configSearchRules file
  case class SearchRule(nameRegex: Regex, locationType: String, base: Option[String])
		
	val config = ConfigFactory.load()
	
	// The default base is the location where the config.file or config.url file lives
	val cFile = if(Option(System.getProperty("config.url")).nonEmpty){
	  // URL was specified
	  (new java.net.URL(System.getProperty("config.url"))).getPath
	} 
	else if(Option(System.getProperty("config.file")).nonEmpty){
	  // File was specified
	  "file:///" + (new java.io.File(System.getProperty("config.file"))).getCanonicalPath
	}
	else "/"
	  
	val ncFile = cFile.replace("\\", "/")
	val defaultBase = ncFile.substring(0, ncFile.lastIndexOf("/") + 1)
	
	// Parse the search rules specified in the config file
	import scala.collection.JavaConversions._
	val rules = config.getConfigList("aaa.configSearchRules").map(rule => 
	  if(rule.getString("locationType") == "resource") 
	    SearchRule(rule.getString("nameRegex").r, rule.getString("locationType"), None)
	  else SearchRule(rule.getString("nameRegex").r, rule.getString("locationType"), 
	      // base is optional but throws exception if not found
	      Try(rule.getString("base")) match {
	        case Success(base) => Some(base)
	        case Failure(_) => None
	        }
	      )).toList
	
	// Cache of read files
  // Concurrent thread-safe map
  val configObjectCache = new scala.collection.concurrent.TrieMap[String, JValue]
	  
  /* Common configuration objects
      "diameterDictionary.json", 
      "radiusDictionary.json",
      "diameterPeers.json",
      "diameterRoutes.json",
      "diameterServer.json",
      "radiusServer.json",
      "radiusServers.json",
      "radiusClients.json",
      "handlers.json"
	*/
  
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
              val url = base.getOrElse(defaultBase) +  nameRegex.findFirstMatchIn(modObjectName).get.group(1)
              log.info(s"Reading $modObjectName from URL $url")
              parse(Source.fromURL(url).getLines.
                  flatMap(l => if(l.trim.startsWith("#") || l.trim.startsWith("//")) Seq() else Seq(l)).
                  map(replaceVars(_)).
                  mkString(separator))
            }
            else {
              val resName = nameRegex.findFirstMatchIn(modObjectName).get.group(1)
              log.info(s"Reading $modObjectName from resource $resName")
              // Remove comments
              parse(Source.fromInputStream(getClass.getResourceAsStream("/" + resName)).getLines.
                  flatMap(l => if(l.trim.startsWith("#") || l.trim.startsWith("//")) Seq() else Seq(l)).
                  map(replaceVars(_)).
                  mkString(separator))
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
	
	
	// Helpers to extract from JValue
	private implicit val formats = DefaultFormats
	
	private def nextPath(jValue: JValue, path: List[String]): JValue = {
	  path match {
	    case Nil => jValue
	    case head :: tail => nextPath(jValue \ head, tail)
	  }
	}
	
  def intFrom(jValue: JValue, path: List[String], default: Int) = { 
	  nextPath(jValue, path).extract[Option[Int]].getOrElse(default)
	}
		
	def longFrom(jValue: JValue, path: List[String], default: Long) = { 
	  nextPath(jValue, path).extract[Option[Long]].getOrElse(default)
	}
	
	def strFrom(jValue: JValue, path: List[String], default: String) = {
	  nextPath(jValue, path).extract[Option[String]].getOrElse(default)
	}
	
	// Helper
	private def replaceVars(input: String) = {
	  val varMap = System.getenv.toMap ++ System.getProperties.toMap
	  val r1 = var1Regex.replaceSomeIn(input, m => varMap.get(m.group(1)))
	  var2Regex.replaceSomeIn(r1, m => varMap.get(m.group(1)))
	}
}