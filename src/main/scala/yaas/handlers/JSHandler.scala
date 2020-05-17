package yaas.handlers

import akka.actor.ActorRef
import yaas.server.MessageHandler
import scala.util.{Failure, Success}

/**
 * Invokes the javascript whose location is passed as parameter in the handler as <code>config</config>, and may refer to a URL
 * @param statsServer the statsServer object
 * @param configObject holds the name of the
 */
class JSHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {

  configObject match {
    case Some(scriptResource) =>
      runJS(scriptResource).onComplete{
        case Success(_) => print("\nHandler finished with success")
        case Failure(e) => print("Error: " + e.getMessage)
      }
    case None =>
      log.error("Script not specified")
  }

}