package yaas.config

import akka.actor.{ ActorSystem, Actor, ActorRef, Props }
import akka.event.{ Logging, LoggingReceive }

import org.json4s._
import org.json4s.jackson.JsonMethods._

import yaas.server.DiameterRouter._

// This class represents a diameter peer as read from the configuration file
case class DiameterServerConfig(bindAddress: String, bindPort: Int, connectionInterval: Int, diameterHost: String, diameterRealm: String, vendorId: Int, productName: String, firmwareRevision: Int)
case class DiameterPeerConfig(diameterHost: String, IPAddress: String, port: Int, connectionPolicy: String, watchdogIntervalMillis: Int)
case class DiameterRouteConfig(realm: String, applicationId: String, peers: Option[Array[String]], policy: Option[String], handler: Option[String])
case class DiameterHandlerConfig(name: String, clazz: String)

// Best practise
object DiameterConfigManager {

  // For deserialization of Json
  private implicit val formats = DefaultFormats

  // General config
  private var diameterConfig = ConfigManager.getConfigObject("diameterServer.json").extract[DiameterServerConfig]

  // Peers
  private var diameterPeerConfig = (for {
    peer <- ConfigManager.getConfigObject("diameterPeers.json").extract[Seq[DiameterPeerConfig]]
  } yield (peer.diameterHost -> peer)).toMap

  // Routes
  private var diameterRouteConfig = ConfigManager.getConfigObject("diameterRoutes.json").extract[Seq[DiameterRouteConfig]]
  
  private var diameterHandlerConfig = (for {
    handler <- ConfigManager.getConfigObject("diameterHandlers.json").extract[Seq[DiameterHandlerConfig]]
  } yield (handler.name -> handler.clazz)).toMap
  
  def getDiameterConfig = diameterConfig
  def getDiameterPeerConfig = diameterPeerConfig
  def getDiameterRouteConfig = diameterRouteConfig
  def getDiameterHandlerConfig = diameterHandlerConfig
}

