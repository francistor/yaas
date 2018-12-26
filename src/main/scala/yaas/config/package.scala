package yaas

/**
 * Yaas uses the Akka configuration file (application.conf).
 * 
 * In fact, all <code>application.conf</code> files in the classpath are read. The classpath 
 * gives precedence to the <code>application.conf</code> in the <code>conf</code> directory,
 * and then reads the default <code>application.conf</code> in the resources
 * 
 * The launch script admits, as first parameter, the name of a configuration directory under
 * <code>conf</code>, whose files as read with the highest precedence.
 * 
 * <code>application.conf</code>. This file includes an "akka" section with the standard
 * configuration options for Akka. Note that the "loglevel" parameter needs to set a log level
 * higher than the one in <code>logback.xml</code>
 * 
 * The <code>logback.xml</code> configures logging properties.
 * 
 * The <code>configSearchRulesLocation</code> property specifies the file where the rest of
 * the configuration files are to be got. It points to a resource file by default, but may also
 * point to a URL. See <code>yaas.config.ConfigManager</code> documentation for the syntax
 * 
 * 
 */
package object config {
  
}