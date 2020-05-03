package yaas.cdr

import akka.actor.{Actor, ActorLogging, Cancellable, Props}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

object CDRFileWriterActor {
  def props(path: String, fileNamePattern: String): Props = Props(new CDRFileWriterActor(path, fileNamePattern))

  // Messages
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

  implicit val executionContext: ExecutionContextExecutor = context.system.dispatcher

  // To be used for file name generation, if the date part is found to have changed
  private val datePartRegex = """%d\{.+}""".r

  private val fileNamePatternRegex = """.*%d\{(.+)}.*""".r

  // Make sure path exists
  new java.io.File(path).mkdirs

  // Stores the specified date pattern as a Java Object
  private val dateFormatterOption = fileNamePattern match {
    case fileNamePatternRegex(p) => Some(new java.text.SimpleDateFormat(p))
    case _ => None
  }

  var currentFormattedDate: String = dateFormatterOption match {
    case Some(df) => df.format(new java.util.Date)
    case None => ""
  }

  private var fileWriter = dateFormatterOption match {
    case Some(dp) => newPrintWriter(dp.format(new java.util.Date))
    case None => new java.io.PrintWriter(s"$path/$fileNamePattern")
  }

  private var timer: Option[Cancellable] = None

  override def receive: Receive = {
    case cdr(cdrText) =>
      fileWriter.println(cdrText)

    case flush =>
      fileWriter.flush()

      dateFormatterOption match {
        case Some(df) =>
          val nowFormattedDate = df.format(new java.util.Date)
          if(nowFormattedDate != currentFormattedDate){

            // Close old one
            fileWriter.close()

            // New writer
            currentFormattedDate = nowFormattedDate
            fileWriter = newPrintWriter(nowFormattedDate)
          }
        case None =>
      }

      timer = Some(context.system.scheduler.scheduleOnce(200.milliseconds, self, flush))
  }

  override def preStart: Unit = {
    timer = Some(context.system.scheduler.scheduleOnce(200.milliseconds, self, flush))
  }

  override def postStop: Unit = {
    timer.foreach(_.cancel)
    fileWriter.close()
  }

  private def newPrintWriter(formattedDate: String) = {
    val fileName = datePartRegex.replaceAllIn(fileNamePattern, formattedDate)
    new java.io.PrintWriter(s"$path/$fileName")
  }

}