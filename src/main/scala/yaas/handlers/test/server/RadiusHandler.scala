package yaas.handlers.test.server

import akka.actor.ActorRef
import slick.jdbc.JdbcBackend.Database
import yaas.coding.RadiusConversions._
import yaas.coding._
import yaas.config.ConfigManager._
import yaas.database.{JSession, SessionDatabase}
import yaas.handlers.RadiusAttrParser.{getFromCiscoAVPair, getFromClass}
import yaas.server.{MessageHandler, _}
import yaas.util.OctetOps
import yaas.util.JSONConfig.getRadiusAttrs

import scala.concurrent.Future
import scala.util.{Failure, Success}

import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.JsonDSL._

// Generic JDBC is deprecated. Use any profile
import slick.jdbc.SQLiteProfile.api._


class RadiusHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {

  private val pwNasPortIdRegex = "^([0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+):(([0-9]+)-)?([0-9]+)$".r
  
  log.info("Instantiated RadiusRequestHandler")
  
  // Get the database configuration
  private val dbConf = yaas.config.ConfigManager.getConfigObjectAsJson("handlerConf/clientsDatabase.json")
  private val nThreads = (dbConf \ "numThreads").extract[Int]
  
  val db = Database.forURL(
        (dbConf \ "url").extract[String],
        driver=(dbConf \ "driver").extract[String],
        executor = slick.util.AsyncExecutor("db-executor", numThreads=nThreads, queueSize=1000)
      )
      
  // Warm-up database connection
  val warmupQuery = sql"""select sysdate from dual""".as[String]
  db.run(warmupQuery)

  /**
   * Accounting Configuration
   */
  private val jGlobalConfig = getConfigObjectAsJson("handlerConf/globalConfig.json")
  private val sessionCDRDir = (jGlobalConfig \ "cdrDir").extract[String] + "/session"
  private val serviceCDRDir = (jGlobalConfig \ "cdrDir").extract[String] + "/service"

  val sessionCDRWriter = new yaas.cdr.CDRFileWriter(sessionCDRDir, "cdr_%d{yyyyMMdd-HHmm}.txt")
  val serviceCDRWriter = new yaas.cdr.CDRFileWriter(serviceCDRDir, "cdr_%d{yyyyMMdd-HHmm}.txt")

  // Write all attributes
  private val format = RadiusSerialFormat.newLivingstoneFormat(List())
  
  override def handleRadiusMessage(ctx: RadiusRequestContext): Unit = {

    /***
    Common to all packet types
     ***/
    val request = ctx.requestPacket

    // Detect Client type
    val radiusClientType =
      if ((request >> "Unisphere-PPPoE-Description").isDefined) "HUAWEI"
      else if ((request >> "Alc-Client-Hardware-Addr").isDefined) "ALU"
      else if ((request >> "Unishpere-PPPoE-Description").isDefined) "MX"
      else "DEFAULT"

    /**
     * Read configuration
     */
    val jRealmConfig = getConfigObjectAsJson("handlerConf/realmConfig.json")
    val jServiceConfig = getConfigObjectAsJson("handlerConf/serviceConfig.json")
    val jRadiusClientConfig = getConfigObjectAsJson("handlerConf/radiusClientConfig.json")
    val jSpecialUsersConfig = getConfigObjectAsJson("handlerConf/specialUserConfig.json")

    // Get and normalize request data.
    val userName = request.S("User-Name").toLowerCase()
    val userNameComponents = userName.split("@")
    val realm = if(userNameComponents.length > 1) userNameComponents(1).toLowerCase() else "none"
    val macAddressOption: Option[String] = (request >> "Huawei-User-MAC").asInstanceOf[Option[String]].orElse[String](request >> "Alc-Client-Hardware-Addr").orElse[String]((request >> "Unishpere-PPPoE-Description").map(_.stringValue.substring(6)))
    val nasPortIdOption: Option[String] = request >> "NAS-Port-Id"

    val (nasIpAddress, nasPortOption): (String, Option[Long]) = if(nasPortIdOption.nonEmpty && (radiusClientType == "HUAWEI" || radiusClientType == "MX")) {
      // NAS-Port-Id <dslam-ip>:<svlan>-<cvlan>
      // Used in Chile. NAS-Port is calculated using the vlan values and NAS-IP-Address is that ot the DSLAm as reported in NAS-Port-Id
      nasPortIdOption.get match {
        case pwNasPortIdRegex(dslamIP, _, svlan, cvlan) =>
          (dslamIP, Some(svlan.toLong * 4096 + cvlan.toLong))

        case _ => (request >> "NAS-IP-Address", request >> "NAS-Port")
      }
    } else (request >> "NAS-IP-Address", request >> "NAS-Port")

    // Priorities Client --> Realm --> Global
    val jConfig = jGlobalConfig.merge(jRealmConfig.forKey(realm, "DEFAULT")).
      merge(jRadiusClientConfig.forKey(nasIpAddress, "DEFAULT"))

    if(false){
      log.debug("jGlobalConfig: {}\\n", pretty(jGlobalConfig))
      log.debug("jRealmConfig: {}\\n", pretty(jRealmConfig))
      log.debug("jRadiusClientConfig: {}\\n", pretty(jRadiusClientConfig))
      log.debug("jConfig: {}\\n", pretty(jConfig))
    }

    ctx.requestPacket.code match {
      case RadiusPacket.ACCESS_REQUEST =>
        try {
          handleAccessRequest(ctx)
        } catch {
        case e: RadiusExtractionException =>
          log.error(e, e.getMessage)
          dropRadiusPacket(ctx)
        }
      case RadiusPacket.ACCOUNTING_REQUEST =>
        try {
          handleAccountingRequest(ctx)
        } catch {
          case e: RadiusExtractionException =>
            log.error(e, e.getMessage)
            dropRadiusPacket(ctx)
        }
    }


    /**
     * Handles Access-Request.
     *
     * @param ctx
     */
    def handleAccessRequest(implicit ctx: RadiusRequestContext): Unit = {

      // Take branch
      if(request >>* "Service-Type" == "Outbound-User") handleServiceDefinition(ctx)
      else if((request >>* "Huawei-Service-Info").matches("N.+")) handlePrepaidRequest(ctx)
      else handleStdAccessRequest(ctx)

      /**
       * Handles the Service Definition Request.
       *
       * @param ctx
       */
      def handleServiceDefinition(implicit ctx: RadiusRequestContext): Unit = {

        log.debug("Handling Service Definition")

        // Send the attributes in the oobRadiusAttrs section. The serviceName is
        val response = RadiusPacket.response(request) <<
          getRadiusAttrs(jServiceConfig, Some(userName), "oobRadiusAttrs")

        sendRadiusResponse(response)
      }

      /**
       * Handles a prepaid Request.
       *
       * @param ctx
       */
      def handlePrepaidRequest(implicit ctx: RadiusRequestContext): Unit = {

        // Here the nasPort is mandatory
        val nasPort = nasPortOption.get

        log.debug(s"Handling prepaid Access-Request for $nasIpAddress : $nasPort")

        // TODO: Do something here
        val response = RadiusPacket.response(request) << ("Huawei-Remanent-Volume", 1000)

        sendRadiusResponse(response)
      }

      /**
       *
       * Looks for client in database or file, depending on the realm. May set reject message if no permissiveService
       * and not provisioned. Proxies also depending on the realm
       * Validates locally the password if configured for the realm
       *
       * Rejects if there is a reject-reason and the reject service is not configured
       * Otherwise sets the service to reject or to the assigned one and merges attributes
       *
       * *@param ctx Radius Context
       **/
      def handleStdAccessRequest(implicit ctx: RadiusRequestContext): Unit = {

        // Here the nasPort is mandatory
        val nasPort = nasPortOption.get

        log.debug(s"Handling Standard Access-Request for $nasIpAddress : $nasPort")

        // Cook some configuration variables
        val blockedServiceOption = (jConfig \ "blockedService").extract[Option[String]]
        val permissiveServiceOption = (jConfig \ "permissiveService").extract[Option[String]]
        val sendReject = (jConfig \ "sendReject").extract[Option[Boolean]].getOrElse(true)
        val rejectServiceOption = if(!sendReject) (jConfig \ "rejectService").extract[Option[String]] else None
        val acceptOnProxyError = (jConfig \ "acceptOnProxyError").extract[Option[Boolean]].getOrElse(false)
        val authLocalOption = (jConfig \ "authLocal").extract[Option[String]]

        if(log.isDebugEnabled) log.debug("permissiveServiceOption: {}, sendReject: {}", permissiveServiceOption, sendReject)
        if(log.isDebugEnabled) log.debug("rejectServiceOption: {}, acceptOnProxyError: {}, authLocalOption: {}", rejectServiceOption, acceptOnProxyError, authLocalOption)

        // Lookup client
        val provisionType = jConfig.jStr("provisionType").getOrElse("database")
        val subscriberFuture = provisionType match {
          case "database" =>
            // userName, password, serviceName, addonServiceName, legacyClientId
            // if UserName and password, they have to be verified
            // Stored procedure example: val clientQuery = sql"""{call getClient($nasPort, $nasIpAddress)}""".as[(Option[String], Option[String], Option[String], Option[String])]
            log.debug(s"Executing query with $nasIpAddress : $nasPort")
            val clientQuery = sql"""select UserName, Password, SERVICE_NAME as ServiceName, OPC_CL_INFO_03 as AddonServiceName, LEGACY_CLIENT_ID as LegacyClientId, BLOCKING_STATE as Status from ServicePlan SP, Client CLI, UserLine UL where CLI.CLIENT_ID=UL.CLIENT_ID AND UL.NASIP_ADDRESS=$nasIpAddress and UL.NASPORT=$nasPort AND CLI.PLAN_NAME=SP.PLAN_NAME""".as[(Option[String], Option[String], Option[String], Option[String], Option[String], Int)]
            db.run(clientQuery)

          case "file" =>
            log.debug(s"Searching in file using $userName")
            val subscriberEntry = jSpecialUsersConfig \ userName
            Future.successful(
              if(subscriberEntry == JNothing) Vector() else
                Vector((
                  Some(userName),
                  (subscriberEntry \ "password").extract[Option[String]],
                  (subscriberEntry \ "serviceName").extract[Option[String]],
                  None,
                  (subscriberEntry \ "legacyClientId").extract[Option[String]],
                  0
                ))
            )

          case "none" =>
            Future.successful(Vector((None, None, None, None, None, 0)))

          case _ =>
            Future.failed(new Exception("Invalid provision type $provisionType"))
        }


        subscriberFuture.onComplete {
          case Failure(error) =>
            // Database error. Drop packet to signal something is wrong
            dropRadiusPacket
            log.error(s"Error looking up client: ${error.getMessage}")

          case Success(queryResult) =>
            log.debug("Query executed")

            var rejectReason: Option[String] = None

            val (userNameOption, passwordOption, serviceNameOption, addonServiceNameOption, legacyClientIdOption, status) =
              // Client found
              if(queryResult.nonEmpty){
                queryResult(0)
              }
              // Client not found
              else
              {
                log.warning(s"Client not found $nasIpAddress : $nasPort - $userName")
                if(permissiveServiceOption.isEmpty) rejectReason = Some("Client not provisioned")
                // userName, password, serviceName, addonServiceName
                (None, None, permissiveServiceOption, None, None, 0)
              }

            if(log.isDebugEnabled) log.debug(s"legacyClientId: ${legacyClientIdOption.getOrElse("")}, serviceName: ${serviceNameOption.getOrElse("")}, addonServiceName: ${addonServiceNameOption.getOrElse("")}")

            // Verify password
            authLocalOption match {
              case Some("database") =>
                passwordOption match {
                  case Some(provisionedPassword) =>
                    if (! (request >>* "User-Password").equals(OctetOps.fromUTF8ToHex(provisionedPassword))){
                      log.debug("Incorrect password")
                      rejectReason = Some("Incorrect User-Name or User-Password")
                    }

                  case None =>
                  // Not provisioned. Do not verify
                }

              case Some("file") =>
                val subscriberEntry = jSpecialUsersConfig \ userName
                if(subscriberEntry == JNothing){
                  log.warning(s"User-Name $userName not found in file")
                  rejectReason = Some(s"User-Name $userName not found in file")
                } else {
                  subscriberEntry \ "password" match {
                    case JString(password) =>
                      if (! (request >>* "User-Password").equals(OctetOps.fromUTF8ToHex(password))){
                        log.debug("Incorrect password")
                        rejectReason = Some(s"Incorrect Password for $userName")
                      }

                    case _ =>
                    // Not provisioned. Do not verify
                  }
                }

              case _ =>
              // Do not verify
            }

            // Override service
            val oServiceNameOption = jConfig \ "overrideService" match {
              case JString(overrideServiceName) => Some(overrideServiceName)
              case _ => serviceNameOption
            }

            // Proxy if requested for this realm
            val proxyGroup = jConfig.jStr("proxyServerGroup")
            val proxyAVPFuture = proxyGroup match {

              case _ if rejectReason.nonEmpty =>
                // If to be rejected, do not ever proxy
                Future(List[RadiusAVP[Any]]())

              case None =>
                // No proxy required. Return empty list of attributes
                Future(List[RadiusAVP[Any]]())

              case Some(group) =>
                // Remove sensitive information
                val proxyRequest = request.proxyRequest.
                  removeAll("NAS-IP-Address").
                  removeAll("NAS-Port").
                  removeAll("NAS-Identifier").
                  removeAll("NAS-Port-Id")

                val proxyTimeoutMillis = jGlobalConfig.jInt("proxyTimeoutMillis").getOrElse(3000)
                val proxyRetries = jGlobalConfig.jInt("proxyRetries").getOrElse(1)

                log.debug(s"Proxy to $group, timeout: $proxyTimeoutMillis, retries: $proxyRetries")

                sendRadiusGroupRequest(group, proxyRequest, proxyTimeoutMillis, proxyRetries).map { packet =>

                  if(packet.code == RadiusPacket.ACCESS_REJECT){
                    log.info(s"$userName rejected by proxy server")
                    rejectReason = Some("Proxy: " + (packet >>* "Reply-Message"))
                  } else log.debug("received response")

                  // Filter the valid AVPs from proxy
                  packet.avps.filter(avp =>
                    avp.getName == "Class" ||
                      avp.getName == "Framed-IP-Address" ||
                      avp.getName == "Reply-Message" ||
                      avp.getName == "User-Password" ||
                      avp.getName == "Framed-Protocol"
                  )
                } recover {
                  case e: Exception if acceptOnProxyError =>
                    log.error(s"Timeout in ServerGroup $group. Will continue due to permissive proxy policy")
                    // Permissive policy. Return empty list of attributes
                    List[RadiusAVP[Any]]()
                }
            }

            proxyAVPFuture.onComplete {
              case Success(proxyAVPList) =>

                // Decide whether to reject
                val response = rejectReason match {
                  case Some(reason) if rejectServiceOption.isEmpty =>
                    // Real reject, since there is no rejectService configured
                    RadiusPacket.responseFailure(request) << ("Reply-Message", reason)

                  case Some(reason) =>
                    // Use the rejectService
                    RadiusPacket.response(request) <<
                      getRadiusAttrs(jServiceConfig, rejectServiceOption, "radiusAttrs") <<
                      ("Class" -> s"S=${rejectServiceOption.get}") <<
                      ("Class" -> s"R=1")

                  case None =>
                    val serviceAVPList = getRadiusAttrs(jServiceConfig, oServiceNameOption, "radiusAttrs")

                    if(log.isDebugEnabled){
                      log.debug("Adding proxy attributes: {}", proxyAVPList.map(_.pretty).mkString)
                      log.debug("Adding service attributes: {}", serviceAVPList.map(_.pretty).mkString)
                    }

                    // Accept. Add proxied attributes and service attributes
                    val radiusResponse = RadiusPacket.response(request) <<
                      proxyAVPList <<
                      serviceAVPList
                    // Add serviceName
                    oServiceNameOption.map(sn =>  radiusResponse <<("Class" -> s"S=$sn"))

                    // Add addon service attributes. May be blocked, or else the configured one (e.g. advertising)
                    if(status == 2){
                      val blockedServiceAttributes = getRadiusAttrs(jServiceConfig, blockedServiceOption, "radiusAttrs")
                      if(log.isDebugEnabled) log.debug("Adding blocked service attributes: {}", blockedServiceAttributes.map(_.pretty).mkString)
                      radiusResponse << blockedServiceAttributes
                    }
                    else {
                      addonServiceNameOption match {
                        case Some(addOnServiceName) =>
                          val addonServiceAttributes = getRadiusAttrs(jServiceConfig, addonServiceNameOption, "radiusAttrs")
                          val noAddonServiceAttributes = getRadiusAttrs(jServiceConfig, addonServiceNameOption, "nonOverridableRadiusAttrs")
                          if(log.isDebugEnabled) log.debug("Adding addon service attributes: {}", addonServiceAttributes.map(_.pretty).mkString)
                          if(log.isDebugEnabled) log.debug("Adding non overridable addon service attributes: {}", noAddonServiceAttributes.map(_.pretty).mkString)

                          radiusResponse <:< addonServiceAttributes << noAddonServiceAttributes
                        case None =>
                      }
                    }

                    radiusResponse
                }

                if(response.code == RadiusPacket.ACCESS_ACCEPT){
                  // Add the rest of the attributes

                  // Get domain attributes
                  val realmAVPList = getRadiusAttrs(jRealmConfig, Some(realm), "radiusAttrs")
                  val noRealmAVPList = getRadiusAttrs(jRealmConfig, Some(realm), "nonOverridableRadiusAttrs")

                  // Get global attributes
                  val globalAVPList = getRadiusAttrs(jGlobalConfig, None, "radiusAttrs")
                  val noGlobalAVPList = getRadiusAttrs(jGlobalConfig, None, "nonOverridableRadiusAttrs")

                  if(log.isDebugEnabled){
                    log.debug("Adding realm attributes: {}", realmAVPList.map(_.pretty).mkString)
                    log.debug("Adding non overridable realm attributes: {}", noRealmAVPList.map(_.pretty).mkString)
                    log.debug("Adding global attributes: {}", globalAVPList.map(_.pretty).mkString)
                    log.debug("Adding non overridable global attributes: {}", noGlobalAVPList.map(_.pretty).mkString)
                  }

                  // Insert into packet
                  response <<? realmAVPList <<? globalAVPList << noRealmAVPList << noGlobalAVPList

                  // Add another class attribute
                  legacyClientIdOption.map(lcid => response << ("Class" -> s"C=$lcid"))
                }

                sendRadiusResponse(response)

              case Failure(e) =>
                dropRadiusPacket
            }
        }
      }
    }

    /**
     * Accounting Handler.
     *
     * @param ctx
     */
    def handleAccountingRequest(implicit ctx: RadiusRequestContext): Unit = {

      val request = ctx.requestPacket

      // Check whether it is session or serviceCDR, and in that case, get serviceName
      val serviceName =
        if((jConfig \ "isSRC").extract[Option[Boolean]].getOrElse(false)){
          // Radius clients is an SRC
          Some(request >>* "Class")
        } else {
          val redbackServiceName = (request >> "Service-Name").map(_.stringValue)
          val hwServiceInfo = (request >> "Service-Info").map(_.stringValue)
          val alcServiceActivate = (request >> "Sub-Serv-Activate").map(_.stringValue.split(":")(0))
          val ciscoServiceName = {
            getFromCiscoAVPair(request, "echo-string-1").orElse(getFromCiscoAVPair(request, "service-name"))
          }
          List(redbackServiceName, hwServiceInfo, alcServiceActivate, ciscoServiceName).find(_.isDefined).flatten
        }

      // Write CDR to file
      val writeSessionCDR = (jConfig \ "writeSessionCDR").extract[Option[Boolean]].getOrElse(false)
      val writeServiceCDR = (jConfig \ "writeServiceCDR").extract[Option[Boolean]].getOrElse(false)
      if(serviceName.isDefined && writeServiceCDR)
        serviceCDRWriter.writeCDR(ctx.requestPacket.getCDR(format))
      else if(writeSessionCDR) sessionCDRWriter.writeCDR(ctx.requestPacket.getCDR(format))

      // Store in session database
      if(!userName.contains("nosession") && serviceName.isEmpty){
        if((request >> "Acct-Status-Type").contentEquals("Start")){

          // Store in sessionDatabase
          SessionDatabase.putSession(new JSession(
            request >> "Acct-Session-Id",
            request >> "Framed-IP-Address",
            getFromClass(request, "C").getOrElse("<UNKNOWN>"),
            getFromClass(request, "M").getOrElse("<UNKNOWN>"),
            List(nasIpAddress, realm),
            System.currentTimeMillis,
            System.currentTimeMillis,
            ("a" -> "aval") ~ ("b" -> 2)))
        }
        else if((request >> "Acct-Status-Type").contentEquals("Stop")){

          // Remove session
          SessionDatabase.removeSession(request >>* "Acct-Session-Id")
        } else if((request >> "Acct-Status-Type").contentEquals("Interim-Update")){

          // Update Session
          SessionDatabase.updateSessionAsync(request >> "Acct-Session-Id", Some("interim" -> true), merge = true)
        }
      }

      val proxyGroup = jConfig.jStr("proxyServerGroup")
      val proxyAVPFuture: Unit = proxyGroup match {
        case None =>
          // No proxy
          sendRadiusResponse(request.response())

        case Some(proxyGroup) =>
          val proxyRequest = request.proxyRequest.
            removeAll("NAS-IP-Address").
            removeAll("NAS-Port").
            removeAll("NAS-Identifier").
            removeAll("NAS-Port-Id")

          val proxyTimeoutMillis = jGlobalConfig.jInt("proxyTimeoutMillis").getOrElse(3000)
          val proxyRetries = jGlobalConfig.jInt("proxyRetries").getOrElse(1)

          sendRadiusGroupRequest(proxyGroup, request.proxyRequest, proxyTimeoutMillis, proxyRetries).onComplete{
            case Success(response) =>
              sendRadiusResponse(request.response())

            case Failure(e) =>
              log.error(e.getMessage)

              // Only for testing purposes. In a production handler there should always be an answer, even
              // before doing proxy
              dropRadiusPacket
          }
      }
    }
  }

  override def postStop: Unit = {
    db.close()
  }
}
