package yaas.handlers.test.server

import akka.actor.ActorRef
import slick.jdbc.JdbcBackend.Database
import yaas.coding.RadiusConversions._
import yaas.coding._
import yaas.config.ConfigManager._
import yaas.database.{JSession, SessionDatabase}
import yaas.handlers.RadiusPacketUtils.{getFromCiscoAVPair, getFromClass}
import yaas.handlers.RadiusConfigUtils._
import yaas.server.{MessageHandler, _}
import yaas.util.OctetOps
import yaas.cdr.CDRFileWriter
import yaas.handlers.RadiusPacketUtils

import scala.concurrent.Future
import scala.util.{Failure, Success}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.JsonDSL._

import scala.io.Source


// Generic JDBC is deprecated. Use any profile
import slick.jdbc.SQLiteProfile.api._


class RadiusHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject) {

  private val pwNasPortIdRegex = "^([0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+):(([0-9]+)-)?([0-9]+)$".r
  private val classRegex = "([A-Z])=(.+)".r

  // Helper to read configuration
  case class CDRDirectory(path: String, filenamePattern: String, checkerName: Option[String])

  // Helper
  case class FilteredCDRWriter(writer: CDRFileWriter, checkerName: Option[String])

  /**
   * Sanity check before starting
   */
  getConfigObjectAsJson("handlerConf/realmConfig.json")
  getConfigObjectAsJson("handlerConf/serviceConfig.json")
  getConfigObjectAsJson("handlerConf/radiusClientConfig.json")
  getConfigObjectAsJson("handlerConf/specialUserConfig.json")

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

  private val sessionCDRWriters = for {
    JArray(directories) <- (jGlobalConfig \ "sessionCDRDirectories")
    jDirectory <- directories
    path = (jDirectory \ "path").extract[String]
    filenamePattern = (jDirectory \ "filenamePattern").extract[String]
    checker = (jDirectory \ "checkerName").extract[Option[String]]
  } yield FilteredCDRWriter(new CDRFileWriter(path, filenamePattern), checker)


  private val serviceCDRWriters = for {
    JArray(directories) <- (jGlobalConfig \ "serviceCDRDirectories")
    jDirectory <- directories
    path = (jDirectory \ "path").extract[String]
    filenamePattern = (jDirectory \ "filenamePattern").extract[String]
    checker = (jDirectory \ "checkerName").extract[Option[String]]
  } yield FilteredCDRWriter(new CDRFileWriter(path, filenamePattern), checker)

  // Write all attributes
  private val cdrFormat = RadiusSerialFormat.newLivingstoneFormat(List())
  
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

    if(log.isDebugEnabled){
      log.debug("jGlobalConfig: {}\\n", pretty(jGlobalConfig))
      log.debug("jRealmConfig: {} -> {}\\n", realm, pretty(jRealmConfig.forKey(realm, "DEFAULT")))
      log.debug("jRadiusClientConfig: {} -> {}\\n", nasIpAddress, pretty(jRadiusClientConfig.forKey(nasIpAddress, "DEFAULT")))
      log.debug("jConfig: {}\\n", pretty(jConfig))
    }

    try {
      request.code match {
        case RadiusPacket.ACCESS_REQUEST => handleAccessRequest(ctx)
        case RadiusPacket.ACCOUNTING_REQUEST => handleAccountingRequest(ctx)
      }
    }
    catch {
      case e: RadiusExtractionException =>
        log.error(e, e.getMessage)
        dropRadiusPacket(ctx)
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
        val blockingServiceNameOption = (jConfig \ "blockingServiceName").extract[Option[String]]
        val blockingIsAddon = (jConfig \ "blockingIsAddon").extract[Option[Boolean]].getOrElse(false)
        val permissiveServiceNameOption = (jConfig \ "permissiveServiceName").extract[Option[String]]
        val sendReject = (jConfig \ "sendReject").extract[Option[String]].getOrElse("yes")
        val rejectServiceNameOption = (jConfig \ "rejectServiceName").extract[Option[String]]
        val rejectFilterOption = (jConfig \ "rejectFilter").extract[Option[String]]
        val rejectIsAddon = (jConfig \ "rejectIsAddon").extract[Option[Boolean]].getOrElse(false)
        val acceptOnProxyError = (jConfig \ "acceptOnProxyError").extract[Option[Boolean]].getOrElse(false)
        val authLocalOption = (jConfig \ "authLocal").extract[Option[String]]

        if(log.isDebugEnabled){
          log.debug("blockingServiceOption: {}", blockingServiceNameOption)
          log.debug("blockingIsAddon: {}", blockingIsAddon)
          log.debug("permissiveServiceNameOption: {}", permissiveServiceNameOption)
          log.debug("sendReject: {}", sendReject)
          log.debug("rejectServiceNameOption: {}", rejectServiceNameOption)
          log.debug("rejectIsAddon: {}", rejectIsAddon)
          log.debug("rejectFilterOption: {}", rejectFilterOption)
          log.debug("acceptOnProxyError: {}", acceptOnProxyError)
          log.debug("authLocalOption: {}", authLocalOption)
        }

        // Lookup client
        val provisionType = jConfig.jStr("provisionType").getOrElse("database")
        val subscriberFuture = provisionType match {
          case "database" =>
            // userName, password, serviceName, addonServiceName, legacyClientId
            // if UserName and password, they have to be verified
            // Stored procedure example: val clientQuery = sql"""{call getClient($nasPort, $nasIpAddress)}""".as[(Option[String], Option[String], Option[String], Option[String])]
            log.debug(s"Executing query with $nasIpAddress : $nasPort")
            val clientQuery = sql"""select UserName, Password, SERVICE_NAME as ServiceName, OPC_CL_INFO_09 as AddonServiceName, LEGACY_CLIENT_ID as LegacyClientId, BLOCKING_STATE as Status, OPC_CL_INFO_03 as overrideServiceName, OPC_CL_INFO_04 as overrideAddonServiceName, ip_address, ipv6_delegated_prefix, usability from ServicePlan SP, Client CLI, UserLine UL where CLI.CLIENT_ID=UL.CLIENT_ID AND UL.NASIP_ADDRESS=$nasIpAddress and UL.NASPORT=$nasPort AND CLI.PLAN_NAME=SP.PLAN_NAME""".as[(Option[String], Option[String], Option[String], Option[String], Option[String], Int, Option[String], Option[String], Option[String], Option[String])]
            db.run(clientQuery)

          case "file" =>
            log.debug(s"Searching in file using $userName")
            val subscriberEntry = jSpecialUsersConfig \ userName
            Future.successful(
              if(subscriberEntry == JNothing) Vector() else
                Vector((
                  Some(userName),
                  (subscriberEntry \ "password").extract[Option[String]],
                  (subscriberEntry \ "basicServiceName").extract[Option[String]],
                  (subscriberEntry \ "addonServiceName").extract[Option[String]],
                  (subscriberEntry \ "legacyClientId").extract[Option[String]],
                  (subscriberEntry \ "status").extract[Option[Integer]].getOrElse(0),
                  (subscriberEntry \ "overrideServiceName").extract[Option[String]],
                  (subscriberEntry \ "overrideAddonServiceName").extract[Option[String]],
                  (subscriberEntry \ "ipAddress").extract[Option[String]],
                  (subscriberEntry \ "delegatedIpv6Prefix").extract[Option[String]],
                ))
            )

          case "none" =>
            Future.successful(Vector((None, None, None, None, None, 0, None, None, None, None)))

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

            val (userNameOption, passwordOption, serviceNameOption, addonServiceNameOption, legacyClientIdOption, status, overrideServiceNameOption, overrideAddonServiceNameOption, ipAddressOption, delegatedIpv6PrefixOption) =
              // Client found
              if(queryResult.nonEmpty){
                queryResult(0)
              }
              // Client not found
              else
              {
                log.warning(s"Client not found $nasIpAddress : $nasPort - $userName")
                if(permissiveServiceNameOption.isEmpty) rejectReason = Some("Client not provisioned")
                // userName, password, serviceName, addonServiceName
                (None, None, permissiveServiceNameOption, None, None, 0, None, None, None, None)
              }

            if(log.isDebugEnabled) log.debug(s"legacyClientId: $legacyClientIdOption, serviceName: $serviceNameOption, addonServiceName: $addonServiceNameOption, overrideServiceName: $overrideServiceNameOption, overrideAddonServiceName: $overrideAddonServiceNameOption")

            // Verify password
            authLocalOption match {
              case Some("provision") =>
                passwordOption match {
                  case Some(provisionedPassword) =>
                    if (! (request >>* "User-Password").equals(OctetOps.fromUTF8ToHex(provisionedPassword))){
                      log.debug("Incorrect password")
                      rejectReason = Some(s"Authorization rejected for $userName")
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
                        rejectReason = Some(s"Authorization rejected for $userName")
                      }

                    case _ =>
                    // Not provisioned. Do not verify
                  }
                }

              case _ =>
              // Do not verify
            }

            /**
             * Apply Overrides
             */

            val (oServiceNameOption, oAddonServiceNameOption) = {
              jConfig \ "overrideServiceName" match {
                  // First priority is override service in the domain, which does not allow an addon
                case JString(overrideServiceName) => (Some(overrideServiceName), None)
                case _ if(status == 2) =>
                  // Second priority is blocked user, which may change the basic service or assign an addon
                  if (blockingIsAddon) (serviceNameOption, blockingServiceNameOption)
                  else (blockingServiceNameOption, None)
                case _ =>
                  // Finally, use the simple overrides or the original services
                  (overrideServiceNameOption.orElse(serviceNameOption), overrideAddonServiceNameOption.orElse(addonServiceNameOption))
              }
            }

            /**
             * Proxy
             */
            val proxyGroup = jConfig.jStr("inlineProxyGroupName")
            val proxyAVPFuture = proxyGroup match {

              case _ if rejectReason.nonEmpty =>
                // If to be rejected, do not ever proxy
                Future(List[RadiusAVP[Any]]())

              case None =>
                // No proxy required. Return empty list of attributes
                Future(List[RadiusAVP[Any]]())

              case Some("none") =>
                // No proxy required. Return empty list of attributes
                Future(List[RadiusAVP[Any]]())

              case Some(group) =>
                // Apply configured out filter in authProxyFilterOut
                val proxyRequest = jConfig \ "authProxyFilterOut" match {
                  case JString(filterName) =>
                    RadiusPacketUtils.filterAttributes(
                      request.proxyRequest,
                      Some(getConfigObjectAsJson("handlerConf/filters/" + filterName))
                    )
                  case _ =>
                    request.proxyRequest
                }

                val proxyTimeoutMillis = jGlobalConfig.jInt("proxyTimeoutMillis").getOrElse(3000)
                val proxyRetries = jGlobalConfig.jInt("proxyRetries").getOrElse(1)

                log.debug(s"Proxy to $group, timeout: $proxyTimeoutMillis, retries: $proxyRetries")

                sendRadiusGroupRequest(group, proxyRequest, proxyTimeoutMillis, proxyRetries).map { packet =>

                  if(packet.code == RadiusPacket.ACCESS_REJECT){
                    log.info(s"$userName rejected by proxy server")
                    rejectReason = Some("Proxy: " + (packet >>* "Reply-Message"))
                  } else log.debug("received response")

                  // Filter the valid AVPs from proxy
                  jConfig \ "authProxyFilterIn" match {
                    case JString(filterName) =>
                      RadiusPacketUtils.filterAttributes(
                        packet,
                        Some(getConfigObjectAsJson("handlerConf/filters/" + filterName))
                      ).avps
                    case _ =>
                      packet.avps
                  }

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
                val (response, fServiceNameOption, fAddonServiceNameOption) = rejectReason match {

                  // If sendReject==no or filter with matching Reply-Message, and rejectServiceName is defined, use rejectServiceName
                  // Otherwise send reject
                  case Some(reason) =>
                    if (
                      rejectServiceNameOption.isDefined &&
                      (
                      sendReject == "no" ||
                        (
                          sendReject == "filter" && rejectFilterOption.nonEmpty && proxyAVPList.exists(avp => avp.getName == "Reply-Message" &&
                          avp.stringValue.contains(rejectFilterOption.get))
                        )
                      )
                    )
                      (
                        RadiusPacket.response(request) << ("Class" -> s"R:1") << ("Reply-Message", reason),
                        if (!rejectIsAddon) rejectServiceNameOption else oServiceNameOption,
                        if (!rejectIsAddon) None else rejectServiceNameOption
                      )

                    else
                      (RadiusPacket.responseFailure(request) << ("Reply-Message", reason), None, None)

                  case None =>
                    (
                      RadiusPacket.response(request),
                      oServiceNameOption,
                      oAddonServiceNameOption
                    )
                }

                if(response.code == RadiusPacket.ACCESS_ACCEPT){

                  // Get basic service attributes
                  val serviceAVPList = getRadiusAttrs(jServiceConfig, fServiceNameOption, "radiusAttrs")
                  val noServiceAVPList = getRadiusAttrs(jServiceConfig, fServiceNameOption, "nonOverridableRadiusAttrs")

                  val addonAVPList = if(fAddonServiceNameOption.isDefined) getRadiusAttrs(jServiceConfig, fAddonServiceNameOption, "radiusAttrs") else List()
                  val noAddonAVPList = if(fAddonServiceNameOption.isDefined) getRadiusAttrs(jServiceConfig, fAddonServiceNameOption, "nonOverridableRadiusAttrs") else List()

                  // Get domain attributes
                  val realmAVPList = getRadiusAttrs(jRealmConfig, Some(realm), "radiusAttrs")
                  val noRealmAVPList = getRadiusAttrs(jRealmConfig, Some(realm), "nonOverridableRadiusAttrs")

                  // Get global attributes
                  val globalAVPList = getRadiusAttrs(jGlobalConfig, None, "radiusAttrs")
                  val noGlobalAVPList = getRadiusAttrs(jGlobalConfig, None, "nonOverridableRadiusAttrs")

                  if(log.isDebugEnabled){
                    log.debug("Adding Proxied Attributes: {}", proxyAVPList.map(_.pretty).mkString)
                    log.debug("Adding non overridable Addon attributes: {} -> {}", fAddonServiceNameOption, noAddonAVPList.map(_.pretty).mkString)
                    log.debug("Adding non overridable Service attributes: {} -> {}", fServiceNameOption, noServiceAVPList.map(_.pretty).mkString)
                    log.debug("Adding non overridable realm attributes: {} -> {}", realm, noRealmAVPList.map(_.pretty).mkString)
                    log.debug("Adding non overridable global attributes: {}", noGlobalAVPList.map(_.pretty).mkString)
                    log.debug("Adding Addon attributes: {} -> {} ", fAddonServiceNameOption, addonAVPList.map(_.pretty).mkString)
                    log.debug("Adding Service attributes: {} -> {} ", fServiceNameOption, serviceAVPList.map(_.pretty).mkString)
                    log.debug("Adding realm attributes: {} -> {}", realm, realmAVPList.map(_.pretty).mkString)
                    log.debug("Adding global attributes: {}", globalAVPList.map(_.pretty).mkString)
                  }

                  // Compose the response packet
                  response <<
                    proxyAVPList <<
                    noAddonAVPList <<
                    noServiceAVPList <<
                    noRealmAVPList <<
                    noGlobalAVPList <<?
                    addonAVPList <<?
                    serviceAVPList <<?
                    realmAVPList <<?
                    globalAVPList <<
                    ("Class" -> s"S:${fServiceNameOption.getOrElse("none")}") <<
                    ("Class" -> s"C:${legacyClientIdOption.getOrElse("not-found")}")

                  if(fAddonServiceNameOption.isDefined) response << ("Class" -> s"A:${fAddonServiceNameOption.getOrElse("none")}")
                  if(ipAddressOption.isDefined) response <:< ("Framed-IP-Address" -> ipAddressOption.get)                           // With Override
                  if(delegatedIpv6PrefixOption.isDefined) response <:< ("Delegated-IPv6-Prefix" -> delegatedIpv6PrefixOption.get)   // With Override
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

      // Check whether it is session or service, and in that case, get serviceName
      val serviceNameOption =
        if(radiusClientType == "SRC"){
          Some(request >>* "Class")
        } else {
          (request >> "Redback-Service-Name")
            .orElse(request >> "Huawei-Service-Info")
            .orElse(request >> "Alu-Sub-Serv-Activate")
            .map(_.stringValue)
            .orElse(getFromCiscoAVPair(request, "echo-string-1"))
            .orElse(getFromCiscoAVPair(request, "service-name"))
        }

      // Build synthetic attributes from class
      for {
        classAttr <- request >>+ "Class"
      } classAttr.stringValue match {
        case classRegex("C", value) => request << ("PSA-LegacyClientId", value)
        case _ =>
      }

      serviceNameOption match {
        case Some(serviceName) => request << ("PSA-ServiceName", serviceName)
        case _ =>
      }


      // Logic might be more complex than this. For instance, emulate service accounting with session accounting
      val isServiceAccounting = serviceNameOption.nonEmpty
      val isSessionAccounting = serviceNameOption.isEmpty

      if(isSessionAccounting) request.pushCookie("isSessionAccounting", "true")
      if(isServiceAccounting) request.pushCookie("isServiceAccounting", "true")

      // Write CDR to file
      // Session
      val shouldWriteSessionCDR = (jConfig \ "writeSessionCDR").extract[Option[Boolean]].getOrElse(false)
      if(isSessionAccounting && shouldWriteSessionCDR) sessionCDRWriters.foreach { filteredWriter =>
        if(RadiusPacketUtils.checkRadiusPacket(request, filteredWriter.checkerName.map(fn => getConfigObjectAsJson("handlerConf/filters/" + fn)))
        )
          filteredWriter.writer.writeCDR(request.getCDR(cdrFormat))
      }

      // Service
      val shouldWriteServiceCDR = (jConfig \ "writeServiceCDR").extract[Option[Boolean]].getOrElse(false)
      if(isServiceAccounting && shouldWriteServiceCDR) serviceCDRWriters.foreach { filteredWriter =>
        if(RadiusPacketUtils.checkRadiusPacket(request, filteredWriter.checkerName.map(fn => getConfigObjectAsJson("handlerConf/filters/" + fn)))
        )
          filteredWriter.writer.writeCDR(request.getCDR(cdrFormat))
      }

      val proxyTimeoutMillis = jGlobalConfig.jInt("proxyTimeoutMillis").getOrElse(3000)
      val proxyRetries = jGlobalConfig.jInt("proxyRetries").getOrElse(1)

      // Proxy to copy targets
      // For the tests, copy is sent if UserName contains "copy", and the filter will force "Service-Type" to "Call-Check", so
      // that the superserver stores the session with "CC" prefix for the AcctSessionId and the FramedIPAddress
      val acctCopyTargets = getConfigObjectAsJson("handlerConf/copyTargets.json").extract[List[RadiusPacketUtils.CopyTarget]]

      for(
        acctCopyTarget <- acctCopyTargets if
          RadiusPacketUtils.checkRadiusPacket(request, acctCopyTarget.checker.map(c => getConfigObjectAsJson("handlerConf/filters/" + c)))
      ) {
        sendRadiusGroupRequest(
          acctCopyTarget.radiusProxyGroupName,
          RadiusPacketUtils.filterAttributes(
            request.proxyRequest,
            acctCopyTarget.filter match {
              case Some(filterName) => Some(getConfigObjectAsJson("handlerConf/filters/" + filterName))
              case _ => None
            }),
          proxyTimeoutMillis,
          proxyRetries).
        onComplete {
          case Success(_) =>
          case Failure(e) =>
            log.error(e.getMessage)
        }
      }

      // Inline proxy
      jConfig.jStr("inlineProxyGroupName") match {
        case None =>
        case Some("none") =>
        case Some(proxyGroupName) =>
          sendRadiusGroupRequest(
            proxyGroupName,
            RadiusPacketUtils.filterAttributes(
              request.proxyRequest,
              jConfig.jStr("acctProxyFilterOut") match {
                case Some(filterName) => Some(getConfigObjectAsJson("handlerConf/filters/" + filterName))
                case _ => None
              }),
            proxyTimeoutMillis,
            proxyRetries).

            onComplete {
            case Success(_) =>

            case Failure(e) =>
              log.error(e.getMessage)
          }
      }

      // Store in session database
      if(!userName.contains("nosession") && serviceNameOption.isEmpty){
        if((request >> "Acct-Status-Type").contentEquals("Start")){

          // Store in sessionDatabase
          SessionDatabase.putSessionAsync(new JSession(
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
          SessionDatabase.removeSessionAsync(request >>* "Acct-Session-Id")
        } else if((request >> "Acct-Status-Type").contentEquals("Interim-Update")){

          // Update Session
          SessionDatabase.updateSessionAsync(request >> "Acct-Session-Id", Some("interim" -> true), merge = true)
        }
      }

      // Always sends answer
      sendRadiusResponse(request.response())
    }
  }

  override def postStop: Unit = {
    db.close()
  }
}
