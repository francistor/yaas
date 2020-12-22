# Fixed Broadband Handler

# Configuration

The configuration files are in the `hanlderConf` folder. The configuation variables that apply to a specific
request may be set in various places, the final value used depends on the precedence.

From lower to higher priority, properties are taken from:
* globalConfig.json
* realmConfig.json
* radiusClientConfig.json

## Authentication

### Provision Type
Determines where the user is looked for. The behaviour is defined in config variable `provisionType`, with values:

* `database`. User is looked for in database, using NAS-Port and NAS-IP-Address
* `file`. User is looked for in file, using User-Name
* `none`. Do not look for the user. Mainly for testing

The look up returns:

* legacyClientId
* basicServiceName
* addonServiceName
* overrideServiceName
* overrideAddonServiceName
* status
* userName of the line
* password of the line
* usability of the line
* IPAddress
* DelegatedIPv6Prefix

### Permissive behaviour

If there is a `permissiveServiceName` variable with value different from `none`, when the client is not found either in database
or in file, it is assigned as the basicServiceName. There may be a special `permissiveServiceName` with value `empty`, which does not
set any attributes and thus the global plus realm radius attributes are used (T. Chile xDom services).

### Client rejections

The `sendReject` variable is `yes` by default, forcing the sending of an Access-Reject in case of proxy Reject, client not found
without permissive service, bad password, or usability restrictions not met.

If `sendReject` is `no`, the `rejectServiceName` is assigned either as basic service or as addon service, depending on the value
of the variable `rejectIsAddon` (true or false, default false).

If `sendReject` is `filter`, the behaviour depends on whether a configured string is found inside the `Reply-Message` attribute.
This string is set in the `rejectFilter`

### Blocked users

The `blockingServiceName` is assigned to clients with status 2, as an addonService if `blockingIsAddon` is true and as basic service
if that variable is false.

### Authorization (password validation) 

If `authLocal` is `provision`, the password is validated locally, using the password in the database or file, as specified as
provision behaviour. If the password is empty, the user is authorized. If the value is `file`, an additional
search for the password is performed in the specialUser file using the User-Name as key, and the password is
checked. If any other value, the validation is done by the remote server, and thus not validated if proxy is not performed.

### Checkers and Filters

`Checkers` and `Filters` are used for proxy configuration

Checkers specify a set of conditions that have to be met in order for the packet to be proxied. The syntax is JSON
with "and" and "or" set of checks of different types: "is", "isNot", "present", "notPresent", "contains" and 
"matches" (for regular expression).

Filters specify a set of actions to the packet that may be "allow", specifying a set of attributes that are
sent in the proxy packet, removing all others, "remove", which deletes the named attributes, and "force", that
sets the values as specified.

### Authorization Proxy

Proxy is sent to the servers in the `inlineProxyGroupName`. The types of packets to send are also specified there. If empty
or "none", no proxy is performed.

The filter applied is specified in `authProxyFilter[In|Out]`

### Response

The attributes sent back in the reply may be classified in two categories. 

The regular attributes have a single instance of each type, with the following precedence:
* Received from proxy
* Those in the addon service
* Those in the basic service
* The ones specified in the realm
* The global ones

The attributes under "nonOverridableAttributes" in the realmConfig or serviceConfig are not overriden, as
the name implies, but are added, that is, multiple attributes may be sent in the response.

In addition, mutliple Class attributes are sent, with values:

* C=<legacy_client_id>
* S=<service_name>
* A=<addon_service>

## Accounting

Configuration is initially done as in authentication, but may be overridden per service.

### CDR Writing
The variables enforcing the writing are `writeSessionCDR` and `writeServiceCDR`, which are boolean. The formats specified
in `sessionCDRTransformer` and `serviceCDRTransformer`.

The CDR are written in the directories specified in `sessionCDRDirectories` and `serviceCDRDirectories`, which are
complex objects specifying:
* A path name
* A file pattern
* A checker to test whether to write this CDR or not

### Accounting Proxy

Proxy is performed to the `inlineProxyGroupName`, when the `proxySessionAccounting` or `proxySerivceAccounting`, as 
appropriate, is `true`. Notice that normally `proxySessionAccounting` is true and `proxyServiceAccounting` is false.

An unbounded number of copies may be sent to other proxyGroups, as specified in the `handlerConf/copyTargets.json"`
file. It contains an array of entries specifying the proxyGroupNames, checkers and filters to apply.

All accounting proxy is done in copy-mode with retries. That is, the answer is sent back to the requesting server
without waiting for the answer and not taking into account the result.

### Global configuration for testing
The global configuation is such that
* BlockingService is "pcautiv", not addon
* SendReject=yes, preset RejectService is "reject", not addon
* permissiveService is "permissive"

### Facilities for testing

Domain naming convention is <provision type>.<auth type>.<permissive behaviour>.<reject behaviour>.<blocking behaviour>[.noproxy|reject]

provision type
* "database"
* "file"
* "none"

auth type
* "provision"
* "file"
* "proxy"
* "none"

permissive behaviour
* "p0". Not permissive
* "p1p". Permissive with service name "permissive"
* "p1x". Permissive with service name "empty"

reject behaviour
* "r0". sendReject is yes
* "r1b". sendReject is false and permissive service is assigned as basic service
* "r1a". sendReject is false and permissive service is assigned as addon service
* "r1bfilter". sendReject is "filter". Use basic service
* "r1afilter". sendReject is "filter". Use addon service

blocking behaviour
* bb. blocking service assigned as basic
* ba. blocking service assigned as addon

proxy
* If nothing, proxy is performed
* If "noproxy", no radiusGroup is configured
* If "reject", the request is rejected by the upstream server

Special domain "none" with
* Provision type "none"
* Auth type "proxy"

As a facility for testing, the "superserver" stores the sessions with SS- prefix normally but with CC-prefix in the
Accounting-Session-Id and the Framed-IP-Address if the Service-Type is "Call-Check". This way, all accounting
may be sent to the same server and sessions received as inline proxy or additional proxy may be tracked. The
standard copyCheck.json filter checks that "copy" is present in the User-Name to perform additional copy, and the
associated filter forces the "Service-Type" to "Call-Check".



