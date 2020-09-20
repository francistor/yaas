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
