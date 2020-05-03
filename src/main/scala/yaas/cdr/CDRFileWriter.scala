package yaas.cdr


import akka.actor.ActorContext

/**
 * Wrapper for FileWriterActor creation.
 * 
 * File will be closed and flushed in a thread safe manner by the underlying Actor
 */
class CDRFileWriter(path: String, fileNamePattern: String)(implicit ac: ActorContext) {
  
  private val writerActor = ac.actorOf(CDRFileWriterActor.props(path, fileNamePattern))
 
  def writeCDR(cdrText: String): Unit = {
    writerActor ! CDRFileWriterActor.cdr(cdrText)
  }
}
