package yaas.handlers.test

import akka.actor.ActorRef
import yaas.server.MessageHandler

class JSHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {
  
  log.info("Instantiated JSHandler")
  
    
  override def postStop = {

  }
}