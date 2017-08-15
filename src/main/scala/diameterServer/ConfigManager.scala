package diameterServer

import com.typesafe.config.ConfigFactory
import java.nio.file._
import scala.io.Source
import org.json4s._
import org.json4s.jackson.JsonMethods._
import scala.util.{Try, Success, Failure}

// TODO Turn this into another Actor
object ConfigManager {
  
	val config = ConfigFactory.load()
	
	var configObjects = scala.collection.mutable.Map[String, JValue]()
	
	update()

	/**
	 * Loads the new contents of the configuration object Map
	 */
	def update(strict: Boolean = false) = {
	  
	  // New Map to be filled
	  val tmpConfigObjects = scala.collection.mutable.Map[String, JValue]()
	  
	  // List all files in directory
  	val files = Files.list(Paths.get("../aaaconf"))
  	try{
  		files.forEach(filePath => {
  			val source = Source.fromFile(filePath.toFile(), "UTF-8")
  			
  			// Parse JSON
  			Try(parse(source.mkString)) match {
  			  case Success(json) => 
  			    tmpConfigObjects(filePath.getFileName.toString) = json
  			  case Failure(ex) => 
  			    // IF not strict, will reuse existing value if available
  			    if(!strict && configObjects.get(filePath.getFileName.toString).isDefined) tmpConfigObjects(filePath.getFileName.toString) = configObjects(filePath.getFileName.toString)
  			    else throw new java.text.ParseException(ex.getMessage, 0)
  			}
  		})
	  }
	  finally {
		  files.close()
	  }
	  
	  // Swap current configuration object
		configObjects = tmpConfigObjects
	}
	
	/**
	 * If the configuration object does not exist, and exception is thrown
	 */
	def getConfigObject(objectName: String) = configObjects(objectName)

	// Print results
	// for((objectName, json) <- configObjects) printf("%s -> %s %n", objectName, pretty(render(json)))
	
	/*
	// Find values
	implicit val formats = DefaultFormats
	
	val a = (configObjects("dos") \ "a").extract[Int]
	* */

}