package yaas.handlers

import akka.actor.ActorRef
import yaas.config.ConfigManager
import yaas.server.MessageHandler
import javax.script._

/**
 * Invokes the javascript whose location is passed as parameter in the handler as <code>config</config>, and may refer to a URL
 * @param statsServer the statsServer object
 * @param configObject holds the name of the
 */
class JSHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {

  class Notifier {
    def end(): Unit = System.out.println("FINISHED")
  }

  log.info("Instantiated JSHandler")

  // Instantiate
  private val engine = new ScriptEngineManager().getEngineByName("nashorn")
  private val scriptName = configObject.get

  // Put objects in scope
  // Radius/Diameter/HTTP helper
  engine.put("Yaas", YaasJS)

  // Base location of the script
  private val scriptURL = ConfigManager.getConfigObjectURL(scriptName).getPath
  engine.put("baseURL", scriptURL.substring(0, scriptURL.indexOf(scriptName)))

  // To signal finalization
  // JScript will invoke Notifier.end
  engine.put("Notifier", new Notifier)

  // Publish command line
  engine.put("commandLine", ConfigManager.popCommandLine.toList)

  // Execute Javascript
  engine.eval(s"load(baseURL + '$scriptName');")
}