package yaas.database

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{RejectionHandler, Route}
import akka.stream.ActorMaterializer
import com.typesafe.config._
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import org.json4s.Formats
import org.json4s.jackson.Serialization
import yaas.instrumentation.MetricsOps._

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object SessionRESTProvider {
  def props(metricsServer: ActorRef): Props = Props(new SessionRESTProvider(metricsServer))
}

trait JsonSupport extends Json4sSupport {
  implicit val serialization: Serialization.type = org.json4s.jackson.Serialization
  implicit val json4sFormats: Formats = org.json4s.DefaultFormats + new JSessionSerializer
}

/**
 * Exposes a REST endpoint for IAM and Session queries
 * @param metricsServer the metrics server Actor
 */
class SessionRESTProvider(metricsServer: ActorRef) extends Actor with ActorLogging with JsonSupport {
  
  implicit val actorSystem: ActorSystem = context.system
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContextExecutor = actorSystem.dispatcher
  
  private val config = ConfigFactory.load().getConfig("aaa.sessionsDatabase")
  private val bindAddress= config.getString("bindAddress")
  private val bindPort = config.getInt("bindPort")
  private val refreshLookupTableSeconds = config.getInt("iam.refreshLookupTableSeconds")
  private val leaseTimeMillis = config.getInt("iam.leaseTimeSeconds") * 1000
  private val graceTimeMillis = config.getInt("iam.graceTimeSeconds") * 1000
  implicit val askTimeout : akka.util.Timeout = 3000.millis
  
  // Synonym for iam Manager object
  private val iam = yaas.database.SessionDatabase
  
  def receive: Receive = {
    case "RefreshLookupTable" =>
      context.system.scheduler.scheduleOnce(refreshLookupTableSeconds.seconds, self, "RefreshLookupTable")
      iam.rebuildLookup()
  }
  
  override def preStart: Unit = {
    // Do it for the first time, and schedule
    iam.rebuildLookup()
    context.system.scheduler.scheduleOnce(refreshLookupTableSeconds.seconds, self, "RefreshLookupTable")
  }
  
  // Cleanup
  override def postStop: Unit = {

  }
  
  private val sessionsRoute =
    pathPrefix("sessions") {
      get {
				// "find" returns a list of sessions, "session" returns the first one
        pathPrefix("find" | "session") {
          parameterMap { params =>
              log.debug(s"Find sessions for $params")
              if(params.isEmpty) complete(StatusCodes.NotAcceptable, "Required one of ipAddress, MACAddress, clientId or acctSessionId parameter")
              else {
								val (k, v) = params.head
								val sessionsOption = k match {
									case "acctSessionId" => Some(SessionDatabase.findSessionsByAcctSessionId(v))
									case "ipAddress" => Some(SessionDatabase.findSessionsByIPAddress(v))
									case "MACAddress" => Some(SessionDatabase.findSessionsByMACAddress(v))
									case "clientId" => Some(SessionDatabase.findSessionsByClientId(v))
									case _ => None
								}
								extractMatchedPath { path =>
									sessionsOption match {
										case Some(sessions) => if (path.toString.endsWith("find")) complete(sessions) else complete(sessions.head)
										case None => complete(StatusCodes.NotAcceptable, s"Attribute $k unknown")
								}
							}
            }
          }
        } 
      }
    }

  private val iamRoute =
    pathPrefix("iam") {
  	  get {
  		  (pathPrefix("poolSelectors") & pathEndOrSingleSlash) {
					parameters("selectorId".?){ selectorIdOption => {
							log.debug(s"get poolSelectors ? $selectorIdOption")
							complete(iam.getPoolSelectors(selectorIdOption))
						}
  			  }
  		  } ~  
  		  (pathPrefix("pools") & pathEndOrSingleSlash) {
  		    log.debug(s"get pools")
  		    complete(iam.getPools(None))
  		  } ~ 
  		  (pathPrefix("ranges") & pathEndOrSingleSlash) {
					parameters("poolId".?){ poolIdOption => {
							log.debug(s"get ranges ? $poolIdOption")
							complete(iam.getRanges(poolIdOption))
						}
					}
  		  } ~ 
  		  (pathPrefix("leases") & pathEndOrSingleSlash) {
					parameter("ipAddress"){ ipAddress =>{
						val leases = iam.getLease(ipAddress)
						if(leases.isEmpty) complete(404, s"Lease not found") else complete(leases.head)
						}
					}
  		  } 
  	  } ~ 
  	  post {
  		  (pathPrefix("factorySettings") & pathEndOrSingleSlash) {
  		    log.debug("post factorySettings")
  			  // Clear all caches
  			  iam.resetToFactorySettings()
  			  complete(201, "OK")
  		  } ~
  		  (pathPrefix("reloadLookup") & pathEndOrSingleSlash) {
  		    log.debug("post reload")
  		    iam.rebuildLookup()
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
					parameters("selectorId","requester".?){ (selectorId, requesterOption) => {
							log.debug(s"lease $selectorId $requesterOption")
							if(iam.checkSelectorId(selectorId)) {
								iam.lease(selectorId, requesterOption, leaseTimeMillis, graceTimeMillis) match {
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
					parameters("ipAddress".as[Int]){ ipAddress => {
							log.debug(s"release $ipAddress")
							if(iam.release(ipAddress)){
								complete(200, "OK")
							}
							else{
								log.warning(s"IP address was not released: $ipAddress")
								complete(404, "Not released")
							}
						}

					}
  		  } ~ 
  		  (pathPrefix("renew") & pathEndOrSingleSlash) {
					parameters("ipAddress".as[Int], "requester"){ (ipAddress, requester) => {
							log.debug(s"renew  $ipAddress")
							if(iam.renew(ipAddress, requester, leaseTimeMillis)){
								complete(200, "OK")
							}
							else{
								log.warning(s"IP address was not renewed: $ipAddress. May be due to already expired or requester not matching")
								complete(404, "Not renewed")
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
				// poolSelector/<selectorId>,<poolId>
				pathPrefix("poolSelector") {
					path(".+,.+".r) { spec => {
						log.debug(s"delete poolSelector $spec")
						val parts = spec.split(",")
						val retCode = iam.deletePoolSelector(parts(0).trim, parts(1).trim)
						if (retCode == 0) complete(202, "OK") else complete(404, s"$retCode ${SessionDatabase.iamRetCodes(retCode)}")
					}
					}
				} ~
					// pool[?deleteRanges=true$withActiveLeases=true]
					pathPrefix("pool") {
						path(Segment) { poolId => {
							// Check that pool is not in use
							log.debug(s"delete Pool $poolId")
							parameters("deleteRanges" ? false, "withActiveLeases" ? false) { (deleteRanges, withActiveLeases) => {
								val retCode = iam.deletePool(poolId, deleteRanges, withActiveLeases)
								if (retCode == 0) complete(202, "OK") else
									if(retCode == SessionDatabase.DOES_NOT_EXIST) complete(404, s"$retCode ${SessionDatabase.iamRetCodes(retCode)}")
									else complete(409, s"$retCode ${SessionDatabase.iamRetCodes(retCode)}")
							}
							}
						}
						}
					} ~
				  // range[?withActiveLeases]
					pathPrefix("range") {
						path(".+,.+".r) { spec => {
							log.debug(s"delete range $spec")
							parameters("withActiveLeases" ? false) { withActiveLeases => {
								val parts = spec.split(",")
								val retCode = iam.deleteRange(parts(0).trim, parts(1).trim.toLong, withActiveLeases)
								if (retCode == 0) complete(202, "OK") else
									if(retCode == SessionDatabase.DOES_NOT_EXIST) complete(404, s"$retCode ${SessionDatabase.iamRetCodes(retCode)}")
									else complete(409, s"$retCode ${SessionDatabase.iamRetCodes(retCode)}")
							}
							}
						}
						}
					}
			} ~
			patch {
				// poolSelector/<selectorId>,<poolId>?priority=<value>
				pathPrefix("poolSelector") {
					path(".+,.+".r) { spec => {
						parameters("priority".as[Int]) { priority =>
							log.debug(s"patching poolSelector $spec to priority $priority")
							val parts = spec.split(",")
							if (iam.modifyPoolSelectorPriority(parts(0).trim, parts(1).trim, priority)) complete(202, "OK") else complete(404, "Not found")
						}
					}
					}
				} ~
				// range/<poolId>,<startIPAddress>?status=<value>
				pathPrefix("range") {
					path(".+,.+".r) { spec => {
						parameters("status".as[Int]) { status =>
							log.debug(s"patching range $spec to status $status")
							val parts = spec.split(",")
							if(iam.modifyRangeStatus(parts(0).trim, parts(1).trim.toLong, status)) complete(202, "OK") else complete(404, "Not found")
						}
					}
					}
				}
			}
  }

	private val notFoundHandler = RejectionHandler.newBuilder.handle {
		case _ => complete(400, "Unknown request could not be handled")
	}. result
  
  // Custom directive for statistics and rejections
	// Pass Route by name
  def logAndRejectWrapper(innerRoutes: => Route): Route = { 
    ctx => 
    mapResponse(rsp => {
			pushHttpOperation(
					metricsServer,
					ctx.request.header[`Remote-Address`].get.address.getAddress.get.getHostAddress,
					ctx.request.method.value,
					ctx.request.uri.path.toString,
					rsp.status.intValue)
			rsp
		})(handleRejections(notFoundHandler)(innerRoutes))(ctx)
  }
  
  private val bindFuture = Http().bindAndHandle(logAndRejectWrapper(sessionsRoute ~ iamRoute), bindAddress, bindPort)
  
  bindFuture.onComplete {
    case Success(binding) =>
      log.info("Sessions database REST server bound to {}", binding.localAddress )
    case Failure(e) =>
       log.error(e.getMessage)
  }

}