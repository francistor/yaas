package yaas.server

import akka.actor.ActorSystem
import yaas.config.ConfigManager
import yaas.dictionary.{DiameterDictionary, RadiusDictionary}

import scala.util.Try

////////////////////////////////////////////////////////////////////////
// Main Diameter Object and application
////////////////////////////////////////////////////////////////////////

object AAAServer extends App {

  // Mostly for Kubernetes. We want to react fast to services pointing to different endpoints
  // TODO: Check if this is necessary
  java.security.Security.setProperty("networkaddress.cache.ttl", "30")
  java.security.Security.setProperty("networkaddress.cache.negative.ttl", "20")

  // Set config.file property as aaa-<instance>.conf, where <instance> is -Dinstance value or "default"
  private val instance = Option(System.getProperty("instance")).getOrElse("default")

  // Will use the application.conf and logback.xml as configured in the usual config.file|url|resource
  // and logback.configurationFile java switches but, if not present will try to use an -<instance> appended
  // version of the resource in the classpath before trying with the canonical names (application.conf and logback.xml)
  // Change bootstrap from application.conf to aaa-<instance>.conf

  // If no bootstrap file is configured, try the resource aaa-<instance>.conf or aaa.conf (only if instance is "default") or fail
  if (System.getProperty("config.file") == null && System.getProperty("config.url") == null && System.getProperty("config.resource") == null) {
    if (Option(getClass.getResource(s"/aaa-$instance.conf")).nonEmpty) System.setProperty("config.resource", s"aaa-$instance.conf")
    else if (instance == "default" && Option(getClass.getResource("/aaa.conf")).nonEmpty) System.setProperty("config.resource", s"aaa.conf")

  }

  if(System.getProperty("logback.configurationFile") == null)
    if(Try(getClass.getResource(s"/logback-$instance.xml")).isSuccess) System.setProperty("logback.configurationFile", s"logback-$instance.xml")

  // Publish command line for the benefit of handlers
  ConfigManager.pushCommandLine(args)
	
	// Create dictionary. Just to do initialization of the Singleton
	private val diameterDictionary = DiameterDictionary
	private val radiusDictionary = RadiusDictionary
	
	// The router will create the peers and handlers
	ActorSystem("AAA").actorOf(Router.props())
}
