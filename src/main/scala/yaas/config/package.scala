package yaas

/**
 * The bootstrap configuration takes place by using the -Dconfig.resource, -Dconfig.file or
 * -Dconfig.url Java properties, and has Akka parameters and syntax. In addition the 
 * -Dlogback.configurationFile for the logging (with logback syntax)
 * 
 * If not any -Dconfig<.type> is not defined the -Dinstance property is used to look for
 * a aaa-<instance>.conf and logback-<instance>.xml files in the Classpath. If the instance
 * name is not defined, "default" is used.
 * 
 * The "configSearchRulesLocation" property in application.conf defines the location of a
 * file specifying the rules to search for the rest of the configuration files. Those are
 * tried first in the specified location plus <instance> in the path, and then without
 * <instance> in the path.
 * 
 */
package object config {
  
}