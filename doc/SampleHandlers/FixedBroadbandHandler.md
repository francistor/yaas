# Fixed Broadband Handler

Config variables may be set globally, per realm or per radius clients, each one overriding the previous one.

## Provision Type

Defined in config variable "provisionType", with values
* "database". User is looked for in database, using NAS-Port and NAS-IP-Address
* "file". User is looked for in file, using User-Name
* "none". Do not look for the user. Mainly for testing

The look up returns
* legacyClientId
* basicServiceName
* addonServiceName
* status
* userName of the line
* password of the line
* usability of the line
* IPAddress
* DelegatedIPv6Prefix

## Permissive behaviour

If there is a "permissiveServiceName" variable with value different from "none", when the client is not found either in database
or in file, it is assigned as the basicServiceName. There is an special permissiveServiceName with value "empty", which does not
set any attributes and thus the global plus realm attributes are used (T. Chile xDom services).

## Reject behaviour

The "sendReject" variable is "yes" by default, forcing the sending of an Access-Reject in case of proxy Reject, client not found
without permissive service, bad password or usability restrictions not met.

If "sendReject" is "no", the "rejectServiceName" is assigned either as basic service or as addon service, depending on the value
of the variable "rejectIsAddon" (true or false, default false).

If "sendReject" is "filter", the behaviour depend on whether a configured string is found inside the Reply-Message attribute

## Blocking behaviour

The "blockingService" is assigned to clients with status 2, as an addonService if "blockingIsAddon" is true and as basic service
if that variable is false.

## Authorization behaviour

If "authLocal" is "provision", the password is validated locally, using the password in the database or file, as specified as
provision behaviour. If the password is empty, the user is authorized. If the value is "file", an additional
search for the password is performed in the specialUser file using the User-Name as key, and the password is
checked. If any other value, the validationis done by the remote server, and thus not validated if proxy is not performed.

## Proxy behaviour

Proxy is sent to the servers in the "proxyGroupName" variable. The types of packets to send are also specified there. If empty
or "none", no proxy is performed.

### Global configuration for testing
The global configuation is such that
* BlockingService is "pcautiv", not addon
* SendReject=yes, preset RejectService is "reject", not addon
* permissiveService is "permissive"

### Domains for testing

Naming convention is <provision type>.<auth type>.<permissive behaviour>.<reject behaviour>.<blocking behaviour>

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



Special domain "none" with
* Provision type "none"
* Auth type "proxy"


database.provision.p1p.r1b.ba



