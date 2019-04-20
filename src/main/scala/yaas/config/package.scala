package yaas

/**
 * The bootstrap configuration takes place by using the -Dconfig.resource, -Dconfig.file or
 * -Dconfig.url Java properties, which has Akka parameters and syntax. In addition the 
 * -Dlogback.configurationFile is used for the logging (with logback syntax)
 * 
 * If not any -Dconfig<.type> is defined, the -Dinstance property is used to look for
 * a aaa-<instance>.conf and logback-<instance>.xml files in the Classpath. If the instance
 * name is not defined, "default" is used.
 * 
 * The <code>configSearchRules<code> property in the main config file defines the locations
 * where to search for the rest of the configuration files. Those are tried first in the specified
 * location plus <instance> in the path, and then without <instance> in the path. Locations may
 * be the resource system or a URL. If no base URL is provided for the resource, the location
 * of the config.file or config.url is tried.
 * 
 * Ignite configuration is taken from the relevant attributes in the config.file or, with higher 
 * priority, from a ignite-yaas.xml file in the classpath/<instance> or classpath
 * 
 */
package object config {
  
}