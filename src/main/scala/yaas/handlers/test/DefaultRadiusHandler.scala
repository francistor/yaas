package yaas.handlers.test

import akka.actor.ActorRef
import yaas.coding._
import yaas.server._

import scala.util.{Failure, Success}

/**
 * Simple AccessRequest handler, which does proxy to yaas-superserver-group
 * @param statsServer the stats server
 * @param configObject the config object (unused)
 */
class DefaultRadiusHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {
  
  log.info("Instantiated DefaultRadiusHandler")

  val writer = new yaas.cdr.CDRFileWriter("cdr", "accounting_request_%d{yyyyMMdd-HHmm}.txt")
  // May use another format using
  // val format = RadiusSerialFormat.newCSVFormat(List("User-Name", "Acct-Session-Id"))
  val format: LivingstoneRadiusSerialFormat = RadiusSerialFormat.newLivingstoneFormat(List())

  override def handleRadiusMessage(ctx: RadiusRequestContext): Unit = {
    ctx.requestPacket.code match {
      case RadiusPacket.ACCESS_REQUEST => handleAccessRequest(ctx)
      case RadiusPacket.ACCOUNTING_REQUEST => handleAccessRequest(ctx)
    }
  }
  
  def handleAccessRequest(implicit ctx: RadiusRequestContext): Unit = {
    
    // Proxy to superserver
    sendRadiusGroupRequest("yaas-superserver-group", ctx.requestPacket.proxyRequest, 500, 1).onComplete {
              
        case Success(response) =>
          sendRadiusResponse(ctx.requestPacket.proxyResponse(response))
          
        case Failure(e) =>
          dropRadiusPacket
          log.error(e.getMessage)
        }
  }

  def handleAccountingRequest(implicit ctx: RadiusRequestContext): Unit = {

    writer.writeCDR(ctx.requestPacket.getCDR(format))

    sendRadiusGroupRequest("yaas-superserver-group", ctx.requestPacket.proxyRequest, 500, 1).onComplete{
      case Success(response) =>
        sendRadiusResponse(ctx.requestPacket.proxyResponse(response))
      case Failure(e) =>
        log.error(e.getMessage)
        dropRadiusPacket
    }
  }

}