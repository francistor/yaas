package yaas.cdr

import akka.actor.{ ActorSystem, Actor, ActorLogging, ActorRef, Props, PoisonPill, Cancellable }
import akka.event.{ Logging, LoggingReceive }

import scala.concurrent.duration._

object CDRFileWriterActor {
    def props(path: String, fileNamePattern: String) = Props(new CDRFileWriterActor(path, fileNamePattern))

    case class cdr(cdrText: String)
    case object flush
}

/**
 * Serializes writes to a CDR file and flushes periodically (non configurable to 4 times per second).
 * 
 * To be used through a CDRFileWriter class, not directly.
 */
class CDRFileWriterActor(path: String, fileNamePattern: String) extends Actor with ActorLogging {
  
  import CDRFileWriterActor._
  
  implicit val executionContext = context.system.dispatcher
  
  // To be used for file name generation, if the date part is found to have changed
  val datePartRegex = """%d\{.+\}""".r
  
  val fileNamePatternRegex = """.*%d\{(.+)\}.*""".r
  // Stores the specified date pattern
  val datePattern = fileNamePattern match {
    case fileNamePatternRegex(p) => Some(new java.text.SimpleDateFormat(p))
    case _ => None
  }
  
  // Make sure path exists
  new java.io.File(path).mkdirs
  
  var currentFormattedDate: Option[String] = None
  var fileWriterOption: Option[java.io.PrintWriter] = None
  var timer: Option[Cancellable] = None
  
  override def receive = {
    case cdr(cdrText) => 
      fileWriterOption.map(_.println(cdrText))
      
    case flush =>
      fileWriterOption.map(_.flush)
      timer = Some(context.system.scheduler.scheduleOnce(200 milliseconds, self, flush))
  }
  
  override def preStart = {
    checkPrintWriter
    timer = Some(context.system.scheduler.scheduleOnce(200 milliseconds, self, flush))
  }
  
  override def postStop = {
    timer.map(_.cancel)
    fileWriterOption.map(_.close)
  }
  
   /**
   * Checks whether the file has to be rotated o created
   */
  private def checkPrintWriter = {
    if(datePattern.isDefined) {
      val nowFormattedDate = datePattern.get.format(new java.util.Date)
      if(nowFormattedDate != currentFormattedDate.getOrElse("0000")){
        // Close old one if exists
        fileWriterOption.map(_.close)
        
        // New writer
        val fileName = datePartRegex.replaceAllIn(fileNamePattern, nowFormattedDate)
        fileWriterOption = Some(new java.io.PrintWriter(s"$path/$fileName"))
        currentFormattedDate = Some(nowFormattedDate)
      }
    } else {
      if(fileWriterOption == None){
        // This will be executed only once
        fileWriterOption = Some(new java.io.PrintWriter(s"$path/$fileNamePattern"))
      }
    }
  }
}