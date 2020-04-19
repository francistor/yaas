package yaas.config

import java.net.URL

import com.typesafe.config.ConfigFactory
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.slf4j.LoggerFactory

import scala.io.Source
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}


/**
 * Reads and caches configuration files from java resources, files or URLs.
 *
 * If the extension of the file is .json, tries to parse and cache the corresponding JSON. Otherwise the cache will
 * store the raw string.
 *
 * Comments starting with // or # are allowed in the JSON files.
 *
 * The class is a singleton and thread safe, thus usable anywhere in the code.
 *
 * <code>getConfigObject[AsJson|AsString]</code> retrieves the contents of the specified configuration file from the cache, or reads it
 * if not available there, and caches it.
 *
 * Entries are refreshed using <code>reloadConfigObject[AsJson|AsString](objectName)</code>, which returns the object,
 * or <code>reloadAllConfigObjects</code>
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
 * The contents of the configuration files may include replacements for environment of system variables '\\${key:defaultValue}'
 *
 */
object ConfigManager {

	private val var1Regex = """\$\{(.+)}""".r
	private val var2Regex = """%(.+)%""".r

	private val separator = System.lineSeparator

	private val instance = Option(System.getProperty("instance")).getOrElse("default")

	private val log = LoggerFactory.getLogger(ConfigManager.getClass)

	private val config = ConfigFactory.load()

	// The default base is the location where the config.file or config.url file lives (the first one specified)
	private val ncFile = Option(System.getProperty("config.url")).map(new java.net.URL(_).getPath)										// Get the URL of the bootstrap config file
		.getOrElse(Option(System.getProperty("config.file")).map(new java.io.File(_).getCanonicalPath).getOrElse("/"))  // Or the file, if the above was not set
		.replace("\\", "/")																																					// Fix from windows
	// Remove the file part
	private val defaultBase = ncFile.substring(0, ncFile.lastIndexOf("/") + 1)

	// Stores a parsed Search Rule
	private case class SearchRule(nameRegex: Regex, locationType: String, base: Option[String])

	// Parse the search rules specified in the config file
	import scala.collection.JavaConversions._
	private val rules = config.getConfigList("aaa.configSearchRules").map(rule =>
		if(rule.getString("locationType") == "resource")
			SearchRule(rule.getString("nameRegex").r, rule.getString("locationType"), None)
		else SearchRule(rule.getString("nameRegex").r, rule.getString("locationType"),
			// base is optional but .getString would throw exception if not present. We convert it to None
			Try(rule.getString("base")) match {
				case Success(base) => Some(base)
				case Failure(_) => None
			}
		)).toList


	case class ConfigObject(url: URL, value: String)
	case class ParsedConfigObject(url: URL, value: String, jValue: JValue)

	// Cache of read files
	// Concurrent thread-safe map
	private val configObjectCache = new scala.collection.concurrent.TrieMap[String, ParsedConfigObject]

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

	/**
	 * Retrieves the Configuration object from the specified URL
	 * @param url the url to retrieve
	 * @return Configuration Object as String
	 */
	private def retrieveURLAsString(url: URL): String = {
		val source = Source.fromURL(url)
		try {
			source
				.getLines.
				flatMap(l => if(l.trim.startsWith("#") || l.trim.startsWith("//")) Seq() else Seq(l)).
				map(replaceVars).
				mkString(separator)
		}
		catch {
			case e: Throwable =>
				log.debug(s"${url.getPath} not found")
				throw e
		}
		finally{
			// Could throw an exception
			source.close
		}
	}

	/**
	 * Gets the configuration object
	 *
	 * Looks for the location using the configured SearchRules and tries to read it first from that
	 * base location and then from the "<instance>"/ base location
	 */
	private def readConfigObject(objectName: String) = {

		def tryReadConfigObject(modObjectName: String): ConfigObject = {
			log.debug(s"Trying read of Configuration Object $modObjectName")
			val urlOption = rules.collectFirst(
				{
					case SearchRule(nameRegex, locationType, base) if modObjectName.matches(nameRegex.regex) =>
						if(locationType == "URL"){
							// base + group found in objectName following nameRegex
							new URL(base.getOrElse(defaultBase) +  nameRegex.findFirstMatchIn(modObjectName).get.group(1))
						}
						else {
							val resName = nameRegex.findFirstMatchIn(modObjectName).get.group(1)
							getClass.getResource("/" + resName)
						}
				}
			)

			urlOption match {
				// Try to read it
				case Some(url) =>
					ConfigObject(url, retrieveURLAsString(url))

				case None =>
					log.debug(s"Do not know where to look for $objectName")
					throw new java.util.NoSuchElementException(s"Do not know where to look for $objectName")
			}
		}

		// Try instance specific first. Then, regular object name
		Try(tryReadConfigObject(s"$instance/$objectName")).orElse(Try(tryReadConfigObject(objectName))) match {
			case Success(confObj) =>
				if(objectName.endsWith("json")) ParsedConfigObject(confObj.url, confObj.value, parse(confObj.value))
				else ParsedConfigObject(confObj.url, confObj.value, JNothing)

			case _ =>
				throw new java.util.NoSuchElementException(objectName)
		}
	}


	/**
	 * Reads the configurtion object and caches it
	 * @param objectName the object name
	 * @param continueIfFailure if true, will swallow the error and just show an error message
	 * @return the parsed object
	 */
	private def cacheConfigObject(objectName: String, continueIfFailure: Boolean = false): ParsedConfigObject = {
		Try(readConfigObject(objectName)) match {
			case Success(obj) =>
				configObjectCache(objectName) = obj
				obj

			case Failure(e) =>
				if(continueIfFailure){
					log.error(s"Error getting $objectName ${e.getMessage}")
					configObjectCache(objectName)
				} else {
					throw e
				}
		}
	}

	/**
	 * Gets the Configuration Object in Raw format (as String)
	 *
	 * To be used by the applications to get the configuration object.
	 *
	 * Throws java.util.NoSuchElementException if the object name is not matched by any
	 * name regular expression, or IOException if it could not be retrieved.
	 *
	 * @param objectName the name of the object
	 * @return The JSON contents of the Configuration Object.
	 */
	def getConfigObjectAsString(objectName: String): String = {
		configObjectCache.getOrElse(objectName, cacheConfigObject(objectName)).value
	}

	/**
	 * Gets the Configuration Object in JSON format.
	 *
	 * To be used by the applications to get the configuration object.
	 *
	 * Throws java.util.NoSuchElementException if the object name is not matched by any
	 * name regular expression, or IOException if it could not be retrieved.
	 *
	 * @param objectName the name of the object
	 * @return The JSON contents of the Configuration Object.
	 */
	def getConfigObjectAsJson(objectName: String): JValue = {
		configObjectCache.getOrElse(objectName, cacheConfigObject(objectName)).jValue
	}

	/**
	 * Gets the URL of the Configuration Object.
	 *
	 * May get the info from the cache. If not present, the Config Object is retrieved
	 * and stored in the cache
	 *
	 * Throws java.util.NoSuchElementException if the object name is not matched by any
	 * name regular expression, or IOException if could not be retrieved.
	 *
	 * @param objectName the name of the object
	 * @return the URL where the Configuration Object resides
	 */
	def getConfigObjectURL(objectName: String): URL = {
		configObjectCache.getOrElse(objectName, cacheConfigObject(objectName)).url
	}


	/**
	 * Forces the reloading of the specific Configuration Object and retrieves it
	 *
	 * Throws java.util.NoSuchElementException if the object name is not matched by any
	 * name regular expression, or IOException if could not be retrieved.
	 *
	 * @param objectName the name of the object to retrieve
	 * @param continueIfFailure whether to swallow the potential error
	 * @return
	 */
	def reloadConfigObjectAsString(objectName: String, continueIfFailure: Boolean = false): String = {
		cacheConfigObject(objectName, continueIfFailure).value
	}

	/**
	 * Forces the reloading of the specific Configuration Object and retrieves it
	 *
	 * Throws java.util.NoSuchElementException if the object name is not matched by any
	 * name regular expression, or IOException if could not be retrieved.
	 * @param objectName the name of the object to retrieve
	 * @param continueIfFailure whether to swallow the potential error
	 * @return
	 */
	def reloadConfigObjectAsJson(objectName: String, continueIfFailure: Boolean = false): JValue = {
		cacheConfigObject(objectName, continueIfFailure).jValue
	}

	/**
	 * Forces the reloading of all configuration objects in the cache.
	 *
	 */
	def reloadAllConfigObjects(): Unit = {
		configObjectCache.foreach{case (k, _) => cacheConfigObject(k)}
	}

	/**
	 * Helpers for Radius and Diameter Handlers
	 */
	private var commandLine: Array[String] = Array()

	def pushCommandLine(commandLine: Array[String]): Unit = {
		this.commandLine = commandLine
	}

	def popCommandLine: Array[String] = commandLine

	def baseURL: String = {
		defaultBase
	}


	/**
	 * Helpers to extract from JValue
	 *
	 * Defines implicit conversion of JValue to JDefault (a custom class), which has some helper methods to be
	 * used in handlers. Given a JValue, if importing ConfigManager._, we can use
	 *
	 * jValue.jInt("mydomain.subKey", "key") to get an Int, or
	 * jValue.key("mydomain.subKey", "DEFAULT") to get a full json object under the specified key
	 *
	 *
	 */
	private implicit val formats: DefaultFormats.type = DefaultFormats

	@scala.annotation.tailrec
	private def nextPath(jValue: JValue, path: List[String]): JValue = {
		path match {
			case Nil => jValue
			case head :: tail => nextPath(jValue \ head, tail)
		}
	}

	/**
	 * Helper to include some extraction methods.
	 *
	 * There is an implicit conversion from JValue to this
	 * @param jv the JSON value to convert
	 */
	class JDefault(jv: JValue) {

		/**
		 * Retrieves the JValue nested in the specified key or the default key if the former is not found
		 * @param key property in the JSON to look for
		 * @param defaultKey default property (e.g. DEFAULT)
		 * @return JSON contents
		 */
		def forKey(key: String, defaultKey: String): JValue = {
			jv \ key match {
				case JNothing => jv \ defaultKey
				case v: JValue => v
			}
		}

		/**
		 * Gets the integer in the specified path
		 * @param path dot separated path
		 * @return an Integer
		 */
		def jInt(path: String): Option[Int] = {
			nextPath(jv, path.split("\\.").toList).extract[Option[Int]]
		}

		/**
		 * Gets the long in the specified path
		 * @param path dot separated path
		 * @return a long value
		 */
		def jLong(path: String): Option[Long] = {
			nextPath(jv, path.split("\\.").toList).extract[Option[Long]]
		}

		/**
		 * Gets the string in the specified path
		 * @param path dot separated path
		 * @return a string value
		 */
		def jStr(path: String): Option[String] = {
			nextPath(jv, path.split("\\.").toList).extract[Option[String]]
		}
	}

	implicit def fromJValueToJDefault(jv: JValue): JDefault = new JDefault(jv)

	/**
	 *
	 * @param input string to convert
	 * @return string with replaced values
	 */
	private def replaceVars(input: String) = {
		// Replacer is made of the environment and the system properties
		val varMap = System.getenv.toMap ++ System.getProperties.toMap
		val r1 = var1Regex.replaceSomeIn(input, m => {
			val keyAndDefault = m.group(1).split(":").toList
			// Try to use the value for the key or the defaultValue if not found
			varMap.get(keyAndDefault.head).orElse(Some(keyAndDefault.last))
		})
		var2Regex.replaceSomeIn(r1, m => {
			val keyAndDefault = m.group(1).split(":").toList
			// Try to use the value for the key or the defaultValue if not found
			varMap.get(keyAndDefault.head).orElse(Some(keyAndDefault.last))
		})
	}
}