package diameterServer

import com.typesafe.config.ConfigFactory
import java.nio.file._
import java.io.File
import scala.io.Source
import org.json4s._
import org.json4s.jackson.JsonMethods._
import scala.util.{Try, Success, Failure}

// TODO: Turn this into another Actor
object ConfigManager {
  
	val config = ConfigFactory.load()
	
	val configObjects = scala.collection.mutable.Map[String, JValue]()
    
  // Try local directory, relative to code location 
	val codePath = ConfigManager.getClass.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()
	// If code is in .jar, go up twice
  val baseDir = if (codePath.endsWith(".jar")) new File(codePath).getParentFile().getParentFile() else new File(codePath).getParentFile()
  val baseConfigURI = baseDir.toURI().resolve("aaaconf/")
  println(baseDir)
	  
  // Read mandatory configuration objects
  val basicObjectNames = Array(
      "diameterDictionary.json", 
      "diameterHandlers.json",
      "diameterPeers.json",
      "diameterRoutes.json",
      "diameterServer.json"
      )
  basicObjectNames.foreach(objectName => readConfigObject(objectName))
  
  def readConfigObject(objectName: String): JValue = {
    val source = Source.fromURL(baseConfigURI.resolve(objectName).toURL, "UTF-8")
  			
  	// Parse JSON
  	Try(parse(source.mkString)) match {
  	  case Success(json) => 
  	    configObjects(objectName) = json
  	    json
  	  case Failure(ex) => 
  	    throw new java.text.ParseException(ex.getMessage, 0)
  	}
  }

	def getConfigObject(objectName: String):JValue = {
	  if(configObjects.contains(objectName)) configObjects(objectName)
	  else readConfigObject(objectName)
	}

	// Print results
	// for((objectName, json) <- configObjects) printf("%s -> %s %n", objectName, pretty(render(json)))
	
	/*
	// Find values
	implicit val formats = DefaultFormats
	
	val a = (configObjects("dos") \ "a").extract[Int]
	* */
}