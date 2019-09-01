package yaas.database

import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model._
import akka.http.scaladsl._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.RejectionHandler
import akka.http.scaladsl.model.headers._

import com.typesafe.config._
import scala.concurrent.duration._
import scala.util.Try
import akka.actor.{ActorSystem, Actor, ActorLogging, ActorRef, Props}
import scala.util.{Success, Failure}

import de.heikoseeberger.akkahttpjson4s.Json4sSupport

import yaas.database._
import yaas.instrumentation.MetricsOps._

object SessionRESTProvider {
  def props(metricsServer: ActorRef) = Props(new SessionRESTProvider(metricsServer))
}

trait JsonSupport extends Json4sSupport {
  implicit val serialization = org.json4s.jackson.Serialization
  implicit val json4sFormats = org.json4s.DefaultFormats + new JSessionSerializer
}

class SessionRESTProvider(metricsServer: ActorRef) extends Actor with ActorLogging with JsonSupport {
  
  import SessionRESTProvider._
  
  implicit val actorSystem = context.system
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher
  
  val config = ConfigFactory.load().getConfig("aaa.sessionsDatabase")
  val bindAddress= config.getString("bindAddress")
  val bindPort = config.getInt("bindPort")
  val refreshLookupTableSeconds = config.getInt("iam.refreshLookupTableSeconds")
  val leaseTimeMillis = config.getInt("iam.leaseTimeSeconds") * 1000
  val graceTimeMillis = config.getInt("iam.graceTimeSeconds") * 1000
  implicit val askTimeout : akka.util.Timeout = 3000 millis
  
  // Synonim for iam Manager object
  val iam = yaas.database.SessionDatabase
  
  def receive = {
    case "RefreshLookupTable" =>
      context.system.scheduler.scheduleOnce(refreshLookupTableSeconds seconds, self, "RefreshLookupTable")
      iam.rebuildLookup
  }
  
  override def preStart = {
    // Do it for the first time, and schedule
    iam.rebuildLookup
    context.system.scheduler.scheduleOnce(refreshLookupTableSeconds seconds, self, "RefreshLookupTable")
  }
  
  // Cleanup
  override def postStop = {

  }   
  
  val notFoundHandler = RejectionHandler.newBuilder.handle {
    case _ => complete(400, "Unknown request could not be handled")
  }. result
  
  val sessionsRoute = 
    pathPrefix("sessions") {
      get {
        pathPrefix("find") {
          parameterMap { params => {
              log.debug(s"find radius sessions for $params")
              if(params.size == 0) complete(StatusCodes.NotAcceptable, "Required ipAddress, MACAddress, clientId or acctSessionId parameter")
              else params.keys.map(_ match {
                case "acctSessionId" => complete(SessionDatabase.findSessionsByAcctSessionId(params("acctSessionId")))
                case "ipAddress" => complete(SessionDatabase.findSessionsByIPAddress(params("ipAddress")))
                case "MACAddress" => complete(SessionDatabase.findSessionsByMACAddress(params("macAddress")))
                case "clientId" => complete(SessionDatabase.findSessionsByClientId(params("clientId")))
                case _ => complete(StatusCodes.NotAcceptable, "Required ipAddress, MACAddress, clientId or acctSessionId parameter")
              }).head
            }
          }
        } 
      }
    }
  
  
  val iamRoute = 
    pathPrefix("iam") {
  	  get {
  		  (pathPrefix("poolSelectors") & pathEndOrSingleSlash) {
  			  parameterMap { params =>
  			    log.debug(s"get poolSelectors ? $params")
    			  // Error if parameters other than selectorId
  			    checkParams(params, List("selectorId"), List()) match {
  			      case Some(err) =>
  			        complete(400, err)
  			        
  			      case None =>
  			        complete(iam.getPoolSelectors(params.get("selectorId")))
  			    }
  			  }
  		  } ~  
  		  (pathPrefix("pools") & pathEndOrSingleSlash) {
  		    log.debug(s"get pools")
  		    complete(iam.getPools(None))
  		  } ~ 
  		  (pathPrefix("ranges") & pathEndOrSingleSlash) {
  			  parameterMap { params =>
  			    log.debug(s"get ranges ? params")
  			    checkParams(params, List("poolId"), List()) match {
  			      case Some(err) =>
  			        complete(400, err)
  			        
  			      case None =>
  			        complete(iam.getRanges(params.get("poolId")))
  			    }
  			  } 
  		  } ~ 
  		  (pathPrefix("leases") & pathEndOrSingleSlash) {
  			  parameterMap { params =>
  			    log.debug(s"get leases ? params")
  			    checkParams(params, List("ipAddress"), List("ipAddress")) match {
  			      case Some(err) =>
  			        complete(400, err)
  			        
  			      case None =>
  			        val leases = iam.getLease(params.get("ipAddress").get)
  			        if(leases.size == 0) complete(404, s"Lease not found") else complete(leases.head)
  			    }
  			  }
  		  } 
  	  } ~ 
  	  post {
  		  (pathPrefix("factorySettings") & pathEndOrSingleSlash) {
  		    log.debug("post factorySettings")
  			  // Clear all caches
  			  iam.resetToFactorySettings
  			  complete(201, "OK")
  		  } ~
  		  (pathPrefix("reloadLookup") & pathEndOrSingleSlash) {
  		    log.debug("post reload")
  		    iam.rebuildLookup
  		    complete(201, "OK")
  		  } ~
  		  (pathPrefix("pool") & pathEndOrSingleSlash) {
  			  entity(as[Pool]) { pool => {
  			      log.debug(s"post pool $pool")
    				  if(iam.putPool(pool)) complete(201, "OK") else complete(409, "Pool already exists")
    			  }
  			  }
  		  } ~
  		  (pathPrefix("poolSelector") & pathEndOrSingleSlash) {
  			  entity(as[PoolSelector]) { poolSelector => {
  			      log.debug(s"post poolSelector $poolSelector")
    				  // Check that poolId already exists
    				  if(!iam.checkPoolId(poolSelector.poolId)) complete(409, "PoolId does not exist in Pools Table")
    				  else {
    					  if(iam.putPoolSelector(poolSelector)) complete(201, "OK") else complete(409, "PoolSelector already exists")
    				  }
    			  }
  			  }
  		  } ~
  		  (pathPrefix("range") & pathEndOrSingleSlash) {
  			  entity(as[Range]) { range => {
  			      log.debug(s"post range $range")
    				  // Check that poolId already exists
    				  if(!iam.checkPoolId(range.poolId)) complete(409, "PoolId does not exist in Pools Table")
    				  else {
    				    // Checks that there is no conflict
    				    if(iam.checkRangeOverlap(range)) complete(409, "Range conflict")
    					  else if(iam.putRange(range)) complete(201, "OK") else complete(409, "Range already exists")
    				  }
    			  }
  			  }
  		  } ~
  		  (pathPrefix("lease") & pathEndOrSingleSlash) {
  		    parameterMap { params =>
  			    log.debug(s"post lease ? $params")
  			    
  			    checkParams(params, List("selectorId", "requester"), List("selectorId")) match {
  			      case Some(err) =>
  			        complete(400, err)
  			        
  			      case None =>
  			        val selectorId = params("selectorId")
  			        if(iam.checkSelectorId(selectorId)) {
    			        iam.lease(selectorId, params.get("requester"), leaseTimeMillis, graceTimeMillis) match {
    			          case Some(lease) =>
    			            complete(lease)
    			            
    			          case None =>
    			            complete(420, "No IP address available")
    			        }
  			        } 
  			        else{
  			          log.warning(s"Selector [$selectorId] not found")
  			          complete(404, s"Selector [$selectorId] not found")
  			        }
  			    }
  		    }
  		  } ~ 
  		  (pathPrefix("release") & pathEndOrSingleSlash) {
  		    parameterMap { params =>
  			    log.debug(s"post release ? $params")
  			    
  			    checkParams(params, List("ipAddress"), List("ipAddress")) match {
  			      case Some(err) =>
  			        complete(400, err)
  			        
  			      case None =>
  			        Try(params("ipAddress").toLong) match {
  			          case Success(ipAddress) =>
  			            if(iam.release(ipAddress)){
  			              complete(200, "OK")
  			            }
  			            else{
  			              log.warning(s"IP address was not released: $ipAddress")
          				    complete(404, "Not released")
  			            }
  			            
  			          case Failure(e) =>
  			            complete(400, "ipAddress not properly specified")
  			        }
  			    }
  		    }
  		  } ~ 
  		  (pathPrefix("renew") & pathEndOrSingleSlash) {
  		    parameterMap { params =>
  			    log.debug(s"post renew ? $params")
  			    
  			    checkParams(params, List("ipAddress", "requester"), List("ipAddress", "requester")) match {
  			      case Some(err) =>
  			        complete(400, err)
  			        
              case None =>
  			        Try(params("ipAddress").toLong) match {
  			          case Success(ipAddress) =>
  			            if(iam.renew(ipAddress, params("requester"), leaseTimeMillis)){
  			              complete(200, "OK")
  			            }
  			            else{
  			              log.warning(s"IP address was not renewed: $ipAddress. May be due to already expired or requester not matching")
          				    complete(404, "Not renewed")
  			            }
  			            
  			          case Failure(e) =>
  			            complete(400, "ipAddress not properly specified")
  			        }
  			        
  			    }
  		    }
  		  } ~ 
  		  pathPrefix("fillPoolLeases") {
  		    // For testing only. Creates a set of old leases filling all the ranges in the specified pool
  		    path(Segment) { poolId => {
  		        iam.fillPoolLeases(poolId)
  		        complete("OK")
  		      }
  		    }
  		    
  		  }
  	  } ~ 
  	  delete {
  		  pathPrefix("poolSelector") {
  			  path(".+,.+".r) { spec => {
  			      log.debug(s"delete poolSelector $spec")
  			      val parts = spec.split(",")
  			      if(iam.deletePoolSelector(parts(0).trim, parts(1).trim)) complete(202, "OK") else complete(404, "poolSelector not found")
    			  }
  			  }
  		  } ~
  		  pathPrefix("pool") {
  			  path(Segment) { poolId => {
    				  // Check that pool is not in use
  			      log.debug(s"delete Pool $poolId")
  			      if(iam.deletePool(poolId)) complete(202, "OK") else complete(409, "Pool not deleted. Not found or still in use")
    			  }
  			  }
  		  } ~
  		  pathPrefix("range") {
  			  path(".+,.+".r) { spec => {
  			      log.debug(s"delete range $spec")
  			      val parts = spec.split(",")
    				  if(iam.deleteRange(parts(0).trim, parts(1).trim.toLong, false)) complete(202, "OK") else complete(404, "range not found")
    			  }
  			  }
  		  }
  	  }
  }
  
  // Custom directive for statistics and rejections
  def logAndRejectWrapper(innerRoutes: => Route): Route = { 
    ctx => 
    (
      mapResponse(rsp => {
        pushHttpOperation(
            metricsServer, 
            ctx.request.header[`Remote-Address`].get.address.getAddress.get.getHostAddress, 
            ctx.request.method.value,
            ctx.request.uri.path.toString, 
            rsp.status.intValue)
        rsp
      })(handleRejections(notFoundHandler)(innerRoutes))
    )(ctx)
  }
  
  val bindFuture = Http().bindAndHandle(logAndRejectWrapper(sessionsRoute ~ iamRoute), bindAddress, bindPort)
  
  bindFuture.onComplete {
    case Success(binding) =>
      log.info("Sessions database REST server bound to {}", binding.localAddress )
    case Failure(e) =>
       log.error(e.getMessage)
  }
  
 /**
 * To validate the queryString
 */
  private def checkParams(receivedParams: Map[String, String], validParams: List[String], mandatoryParams: List[String]): Option[String] = {
    // Check invalid
    val invalidParams = receivedParams.keys.filter(!validParams.contains(_))
    if(invalidParams.size > 0) Some(s"""Invalid parameters ${invalidParams.mkString(",")}""")
    else {
      // Check valid mandatory
      val missingParams = mandatoryParams.filter(!receivedParams.contains(_))
      if(missingParams.size > 0) Some(s"""Missing parameters ${missingParams.mkString(",")}""") else None
    }
  }
}