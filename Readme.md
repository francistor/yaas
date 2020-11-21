## What is Yaas?

Yaas is a Diameter an Radius Engine, capable of acting as a server or client.

Diameter and Radius are protocols used by telcos for signalling, that is, exchanging control
information (not user traffic) between network nodes. Both of them offer high
performance due to its asyncronous nature. A single connection (there is even no such a thing
as "connection" in the case of Radius) may multiplex requests and responses comming out or order,
avoiding wait times. This multiplex feature over the same connection is implemented in http2, and 
probably Diameter and Radius will become slowly out of favor when http2 gains traction in telcos, as is
already happening in 5G.

Yaas is built in scala and Akka.

Yaas starts up as a process with multiple "Handlers", each one being an Akka Actor, which may be
registered to receive certain types of Diameter/Radius messages and also are capabe to send Diameter/Radius
messages. Handlers inherit from the "yaas.server.MessageHandler", but may include any code and
of course thus perform any action, such as connecting to a database, invoke REST services or
whatever.

The advantage of this approach to build the logic internal to Diameter/Radius servers is that the
developer has the same freedom as in any programming language, and using a strongly typed one such
as scala. The disadvantage is that this may be too much freedom to be practical for very simple
algorigthms. Nevertheless, the author has never seen a simple Radius/Diameter algorithm, except
during the first day of development (people tend to make things complicated), and in the end,
the result in Yaas is typically much more compact, readable and maintainable than algorithms built
on Nokia TAL, pre-post-blah hooks in Radiator, etc., not to mention performance.

Yaas makes use of embedded Apache Ignite as a database for storing active sessions, IP address
assginments and, incidentally, for storing client data for internal tests.

## Installation

Clone the repository and use sbt. The Universal packager is used, so that rpm or deb or zip artifacts
may be created with the appropriate command.

If incorporating external handlers (which will usually be the case), those must be made available in the
classpath.

## Smoke testing

Execute `testAll` in the `bin` directory.

This will launch several instances:

- A "superserver" instance, which acts as a Diameter and Radius server plus an ignite database server. It stores sessions with a prefix in the Acct-Sesion-Id and IP-Address
- A "superserver-mirror" instance, which will act as an ignite replica.
- A "server" instance, which receives the client requests, looks for clients in the ignite database, stores sessions and proxies the requests to the superserver.
- A "client" instance, which generates the requests and performs the tests.

## Default configuration

After building Yaas, the server may be started using the command `aaaserver`, *without parameters at all*, to launch an instance named "default" with a reference configuration, using the files stored internally as resources. It includes RadiusAuth and RadiusAcct handler that simply do proxy to an upstream server, and a Diameter Credit Control Application server that also forwards the requests to a Diameter peer. 

The upstream server may be configured according to the files in `src/test/3rd_party`, listening to 11812 and 11813 ports for Radius and 3867 for Diameter, and simple test cases launched using the test scripts under the `bin/test` directory.

## Multiserver test scenario

The off-the-shelf installation creates configuration for three instances which may be launched on a single machine to execute a somewhat complex testing scenario.

- `test-client` includes a handler that acts as a client for Radius, Diameter and IP address allocations Requests, as well as checking metrics.
- `test-server` includes a complex radius request handler, similar to what can be found in production and which may be used as an example for coding. The sessions database role is "client". It executes database queries for looking up clients and session storage using the ignite server that is part of the "super-server" instance. It looksup the client in file or in database depending on the realm in the request.
- `test-superserver` acts as a ISP proxy and Ignite database for sessions, IP address allocations and client information. It does accept / reject / drop the request depending on the user-name.

The full smoke test may be executed by launching the `testAll` script in the test directory under bin. The tests executed may be customized by editing the source code in `yaas.handlers.test.TestClientMain.scala`

## Kubernetes test scenario

The Kubernetes test scenario includes four instances, all of them (except "client") are StatefulSets:

- `client/radius` and `client/diameter`. The common configuration files are under the "client" directory, with two instances named "radius" and "diameter". This instance is meant to be launched outside Kubernetes, to execute the test cases.
- `server`. Contains handlers for Radius and Diameter which in general do proxy to superserver. It uses one generic service (Headless) as required for the statefulset instantiation, a service of type NodePort for exposing the Radius ports and two additional services for Diameter, also of type NodePort, for each one of the Diameter Nodes. 
- `superserver`. Contains handler for Radius and Diameter. It uses a Headless service for exposing the Diameter Port to the server instance. The Diameter client (server instance) connects to one instance or the other using the DNS name. For Radius, a regular service with a single IP address is used, and Kubernetes does the balancing.  As explained above, all requests with the same origin IP address and port are sent to the same server, and any new server instantiated an added to the service does not get additional load.
- `db`. Contains a Yaas instance that does not start a Radius or Diameter server, but only the Ignite database and the REST server for performing IPAM operations and looking up sessions. The "server" and "superserver" instances are Ignite clients only. A Headless service is employed for internal use by the yaas clients, and a NodePort for external queries.

To build the Docker images and publish them to Dockerhub, execute the script `docker-build-sh` in the home directory of Yaas.

## Configuration

Yaas is not ashamed to be configured via files. They can be stored locally in the executing server or in a remote location (accesible via http). Some files may be reloaded without restarting, and some other cannot.

### Bootstrap

If no parameter is specified, Yaas will bootstrap using a local file in the "conf" directory with the name `aaa-<instance_name>.conf`. <instance_name> is passed as a parameter in the command line as  `-Dinstance=<instance_name>`, and "default" with be used if nothing is specified.

A specific bootstrap file may be forced using `-Dconfig.resource`, `-Dconfig.file` or `-Dconfig.url` parameters, the last one allowing for the file to be stored in a remote/centralized location.

In this bootstrap file, there is a section called "aaa.configSearchRules" that specifies where to look
for other configuration files based on the name of the file. That location may be a URL, whose base location is specified, or it will be assumed to be the location of the bootstrap file. It may be also specified as a resource, that will be looked for in the classpath: first in the "conf" directory and then in the resources embedded in the packaged .jar file.

	# Locations where to search for other configuration files
	# if "base" is not specified, the parent URL of this file is used as base
	# {"nameRegex": "Gx/(.*)", 	"locationType": "URL", "base": "http://localhost:8099/"},
	# {"nameRegex": "Gy/(.*)", 	"locationType": "URL", "base": "file:///etc/yaas/Gy/"},
	# {"nameRegex": "(.*)", 	"locationType": "resource"}

The location of the logging configuration file is, by default, logback-<instance_name>.xml as a resource in the classpath, but may be overriden by including -Dlogback.configurationfile pointing to any URL (typically should match the location of the bootstrap file). If not found, the logback.xml in the classpath will be used.

Each configuration file, irrespective o whether it is located in a URL or in a resource, will be tried first in the expected location with the `instance_name` string appended, and then in the raw expected location.

The embedded `aaa-reference.conf` must be always included in the boostrap file, and defines all the possible configuration parameters with some documentation. 
```
# Reference configuration by default
include "aaa-reference.conf"
```

The most important parameter is `aaa.sessionsDatabase.role`, which defines whether to launch an ignite client or server instance (values may be `client`, `server` and `none`). The ignite configuration parameters (memory, ip addresses and ports) may be configured in this file, or an `ignite.xml` file may be used. See comments inline `aaa-reference.conf` for details.

This looks complicated but usage should be simple and flexible. Here are some examples

#### Configuration in local files without instance name

The server is launched as `aaaserver`, without parameters. The instance name is thus `default`.

The configuration file is looked for as `conf/aaa-default.conf`. The name `conf/aaa.conf` may also be used (only for `default`instance). The other configuration files will be looked for also in the `conf` directory. If not found there, the defaults embedded as application resources will be used.

If no `conf/aaa-default.conf` or `conf/aaa.conf` is specified, the application will use the values in the embedded `reference.conf` and will be able to start. This is useful only for simple smoke testing.

#### Configuration in local file. Multiple instance names

The server is launched as `aaaserver -Dinstance=<instance_name>`

The bootstrap file loaded is then `conf/aaa-<instance_name>.conf`. Other files will be looked for in this order:

1. First in the `/conf/<instance_name>` directory, 
2. Then in the `conf` directory
3. Then as an embedded resource. 

The logging configuration file will be `conf/logback-<instance_name>.xml` if found, or `conf/logback.xml` 

#### Configuration in http location, using instance name, but some files using defaults in embedded resources

For instance, the radius and diameter dictionaries used will be those embedded as application resources.

The server is launched as `aaaserver -Dinstance=<instance_name> -Dconfig.url=http://config.server/conf/aaa-<instance_name>.conf -Dlogback.configurationFile=http://config.server/conf/logback.xml`

The rules in the remote file are

```
configSearchRules = [
	{"nameRegex": "(.*Dictionary\\.json)", "locationType": "Resource"},
	{"nameRegex": "(.*)", "locationType": "URL"},
]
```

When specifying "locationType":"URL" without "base" the configuration files will be looked for in the same
location as the bootstrap file, but trying with the <instance_name> appended first.

## Configuration files

### Radius

#### radiusDictionary.json

#### radiusServer.json

If `bindAddress` is not an IP address (not having any dots), the radius server functionality is
not enabled.

If `clientBasePort` is not a number > 0, the radius client functionality is not enabled.

Radius requests use `clientBasePort` and up to `numClientPorts` as origin ports. For instance, if
`clientBasePort` is 45000 and `numClientPorts` is 2, the ports used will be 45000 and 45001. Ports
are used in a round-robin fashion, to facilitate load distribution to upstream servers (for instance,
Kubernetes will send all requests from the same origin port to the same destination).

```
{
	"bindAddress": "0.0.0.0",
	"authBindPort": 1812,
	"acctBindPort": 1813,
	"coABindPort": 3799,
	"clientBasePort": 45000,
	"numClientPorts": 10
}
```

#### radiusClients.json

The radius protocol assumes there is a shared secret between the server and the client, and clients can only be identified by its IP address. This is a problem for Kubernetes, where the origin address is NATed if the process is accessed by means of a Service. NodePorts may be configured to not use NAT, but this usually is not a practical solution. For Kubernetes, the solution is to have a service for each radius, with single secret shared among all clients by doing

```
{
	"name": "allClients",
	"IPAddress": "0.0.0.0/0",
	"secret": "the_only_secret"
}
```

#### radiusServers.json

Note the plural in the name.

This file specifies the radius server groups for balancing and the individual radius servers.

A single radius request to a group produces retries to different servers. The policy may be `random`,  `random-clear`, `fixed` or `fixed-clear`. If `fixed*` the requests are sent preferentially to the first member of the group that is available, and to the other ones in order in case of errors.  The suffix "clear" specifies what to do if all server in the group are in quarantine. If present, the quarantine status is ignored and all the servers are tried anyway.

#### Diameter

#### diameterDictionary.json

#### diameterServer.json

If "bindAddress" has no dots, the Diameter protocol is disabled

```
{
	"bindAddress": "127.0.0.1",
	"bindPort": 3868,
	"diameterHost": "server.yaasserver",
	"diameterRealm": "yaasserver",
	"vendorId": 1101,
	"productName": "Lever",
	"firmwareRevision": 1,
	"peerCheckTimeSeconds": 120
}
```

#### diameterPeers.json

The `diameterHost` must match what is reported by the Peer in the CER/CEA message exchange.

Diameter is also an unfriendly protocol for Kubernetes, due to the masking of origin IP addresses
that takes place when using services. For that reason, when accepting connections the `IPAddress`
value is not checked, but only the `originNetwork`, that may be set to `0.0.0.0/0` to accept any
value.

The `connectionPolicy` may be `active` or `passive`. If `active`, the server will try to establish
a connection to the specified IPAddress and then send a CER message. If `passive`, the server will
not try to establish a connection but wait for the other Peer to do it.

```
[
	{
        "diameterHost": "client.yaasclient",
        "IPAddress": "127.0.0.1",
        "port": 3867,
		"connectionPolicy": "passive",
		"watchdogIntervalMillis": 300000,
		"originNetwork": "0.0.0.0/0"
	}
]
```

#### diameterRoutes.json

This file specifies what to do when receiving messages from a pair realm/applicationId. If a handler is
specified, the message is treated locally. If only a `peers` array is specified, the message is forwarded
to those, with a balancing policy that may be `fixed` or `random`.

```
[	
	// Handle Credit-Control locally
	{"realm": "yaasserver", "applicationId": "Credit-Control", "handler": "TestServerCCHandler"},

	// Handle NASREQ locally
	{"realm": "yaasserver", "applicationId": "NASREQ", "handler": "TestServerNASReqHandler"},

	// Send to superserver
	{"realm": "yaassuperserver", "applicationId": "*", "peers": ["superserver.yaassuperserver"], "policy": "fixed"}

]
```

### Common files

#### handlers.json

This file defines the handler (Actor class deriving from MessageHandler) to be used.

For radius, the name is predefined as "RadiusHandler"

```
[
	// Diameter
	{"name": "TestServerCCHandler", "clazz": "yaas.handlers.test.server.CCHandler", "config":"run.js"},
	{"name": "TestServerNASReqHandler", "clazz": "yaas.handlers.test.server.NASReqHandler"},

	// Radius
	{"name": "RadiusHandler", "clazz": "yaas.handlers.test.DefaultRadiusRequestHandler"}	
]
```

      "Framed-IP-Address": ["${ipAddress}"],
      "Acct-Status-Type": ["Start"]
    }
  }
  """
      
val accountingRequest: RadiusPacket = parse(sAccountingRequest)
```

## Storing and finding sessions

The Session Database functionality is exposed by the object yaas.database.SessionDatabase, which offers methods to create and to find sessions. They may be invoked by any Yaas instance whose `aaa.sessionsDatabase.role` is `client` or `server`.

The Session class is the one persisted in ignite, but developers should use the JSession class, which provides some convenience methods. 

The key parameter is the `acctSessionId`, and searches may be performed also by, `iPAddress`, `clientId`, and `macAddress`.

The groups attribute is a list with session attributes used for classification. a typical group list could be NAS-IP-Address plus ServiceName assigned. The instrumentation server may be queried by the number of sessions aggregated by one or more groups. The usage may be consistent in all the handlers, that is, groups and its ordering must be the same.

To create a session, create a new JSession. The last parameter is an arbitrary JValue

```
SessionDatabase.putSession(new JSession(
          request >> "Acct-Session-Id",
          request >> "Framed-IP-Address",
          getFromClass(request, "C").getOrElse("<UNKNOWN>"),
          getFromClass(request, "M").getOrElse("<UNKNOWN>"),
          List(nasIpAddress, realm),
          System.currentTimeMillis,
          System.currentTimeMillis,
          ("a" -> "myValue") ~ ("b" -> 2)))
```

The session may be later updated using the AccountingSessionId as a key. The provided JValue may replace or be merged with the previous value. The lastUpdatedTimestamp value will be automatically updated.

```
SessionDatabase.updateSession(request >> "Acct-Session-Id", Some(("interim" -> true)), true)
```

There are various methods to look for Sessions, all of them returning a list of JSession

```
findSessionByAcctSessionId(acctSessionId: String) 
findSessionsByIPAddress(ipAddress: String)
findSessionsByClientId(clientId: String)
findSessionsByMACAddress(macAddress: String)
```
