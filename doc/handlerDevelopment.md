# Handler development

## Development of Diameter handlers

Create a class that inherits from MessageHandler. The statsServer parameter is only used normally by the parent
class, and the configObject is any configuration object that may have been specified in the `config` parameter
in the `handlers.json` file, to be used as required by the developer.

```
class CCHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject)

	override def handleDiameterMessage(ctx: DiameterRequestContext) = {
    	...
  	}
```

The processing logic is inside `handleDiameterMessage`. 

The `DiameterRequestContext` parameter includes the request, and is used in some internal functions as an implicit
parameter to reduce verbosity.

A new DiameterMessage may be created using

```
DiameterMessage.request(applicationName : String, commandName: String)
```

The Diameter message may be used to generate a derived one
- `copy` generates another message with the same attributes and AVP, but different E2EId and HopByHopId
- `proxy` generates another message with the same attributes and AVP, but removing all AVP related to
routing (Destination Host and Realm), and adding new routing attributes defining the origin (Origin Host
and Realm)
- `answer` generates another message with the required attributes to be signaled as an answer to the 
original one, that is, same applicationId, commandCode, Ids but with the "isRequest" to false flag. 

There is one DiameterAVP parametrized class for each type of Diameter AVP. They can be created the hard
way with something like

```
val integer32AVP = new Integer32AVP(2, true, false, 1001, 31416)
```

or using implicit conversions from tuple (string to string, int or Long), JField or List of 
(String, Seq[(String, String)] (for grouped AVP)

```
val integer32AVP = ("francisco.cardosogil@gmail.com-myInteger, 31416)
```

Names of attributes are composed of the vendor, hyphen, name-of-raw-attribute.

Example of creating a Grouped AVP

```
# The tunneling AVP is of type Grouped, with code 401, and includes two other AVP
("Tunneling" -> Seq(("Tunnel-Type" -> "L2TP"), ("Tunnel-Client-Endpoint" -> "my-tunnel-endpoint")))
```

The DiameterMessage includes a List of DiameterAVP which can be manipulated with a few helper methods.

### Extracting AVP from the DiameterMessage

`>>` or `get` extracts an Option[DiameterAVP], which is the first AVP with the specified name, and None
if no match is found.

`>>*` or `getDeep` does the same, but the name of the parameter may be nested, using dot notation (such
as "Subscription-Id.IMSI".

`>>>` or `getGroup` also does the same, but the extracted AVP is of type Grouped, which is useful sometimes
for type safety.

`>>+` or `getAll`extracts a List of attributes with the specified name, instead of only the first one. This
is not very usual in Diameter.

`>>++` or `getAsString` extracts all the attributes with the specified name forcing conversion to a single
string, comma separated if there are several values. Not very usual in Diameter.

### Adding AVP to the DiameterMessage

`<<` or `put` adds an `DiameterAVP`, an `Option[DiameterAVP]` or a `List[DiameterAVP]` to the message.

`<<<` or `putGroup` adds an `GroupedAVP` or `Option[GroupedAVP]` to the message. In reality, the above methods may also be used.

### JSON format for Diameter messages

There is an implicit conversion DiameterMessage to/from JSON. In the following example, a DiameterMessage
is created using Json4s constructs.

```
val gxRequest: DiameterMessage = 
  ("applicationId" -> "Gx") ~
  ("commandCode" -> "Credit-Control") ~
  ("isRequest" -> true) ~
  ("avps" ->
    ("Origin-Host" -> "client.yaasclient") ~
    ("Origin-Realm" -> "yaasclient") ~
    ("Destination-Realm" -> "yaassuperserver") ~
    ("Subscription-Id" ->
      ("Subscription-Id-Type" -> "EndUserIMSI") ~
      ("Subscription-Id-Data" -> subscriptionId)
    ) ~
    ("Framed-IP-Address" -> "1.2.3.4") ~
    ("Session-Id" -> "This-is-the-session-id")
  )
```


## Development of Radius handlers

Create a class that inherits from MessageHandler. It may be the same class for handling Radius and
Diameter messages.

```
class CCHandler(statsServer: ActorRef, configObject: Option[String]) extends MessageHandler(statsServer, configObject)

	override def handleRadiusMessage(ctx: RadiusRequestContext) = {
    	...
  	}
```

The processing logic is inside `handleRadiusMessage`. 

The `RadiusRequestContext` parameter includes the RadiusPacket, and is used in some internal functions as an implicit
parameter to reduce verbosity.

A new RadiusPacket may be created using

```
RadiusPacket.request(code: Int)
```

The RadiusPacket message may be used to generate a derived one

- `response` generates a message which is a response to the specified one, thus having the same Id and authenticator (as
a seed for calculating the final authenticator on the wire), without any AVP
- `proxyRequest` generates another message with the same code and AVP
- `proxyResponse` generates a message which is a response to the specified one, thus having the same Id and authenticator (as
a seed for calculating the final authenticator on the wire), with the AVP of the other packet passed as a parameter

There is one RadiusAVP parametrized class for each type of Radius AVP. They can be created the hard
way with something like

```
val addressAVP = new AddressRadiusAVP(4, 0, java.net.InetAddress.getByName("100.100.100.100"))
```

or using implicit conversions from tuple (string to string, int or Long), or JField

```
val addressAVP = ("Framed-IP-Address" -> "100.100.100.100")
```

Names of attributes are composed of the vendor, hyphen, name-of-raw-attribute.

### Extracting AVP from the RadiusPacket

`>>` or `get` extracts an `Option[RadiusAVP]`, which is the first AVP with the specified name, and None
if no match is found.

`>>+` or `getAll`extracts a List of attributes with the specified name, instead of only the first one.

`>>++` or `getAsString` extracts all the attributes with the specified name forcing conversion to a single
string, comma separated if there are several values. Not very usual in Diameter.

The following extractors try to get a single attribute, with type String or Long, generating an exception
if not found, more than one attribute found, or unable to perform casting

```
def S(attributeName: String) 
def L(attributeName: String) 
```

### Adding AVP to the RadiusPacket

`<<` or `put` adds a RadiusAVP, an Option[RadiusAVP] or a List[RadiusAVP] to the message.

`<<?` or `putIfAbsent` adds a RadiusAVP, an Option[RadiusAVP] or a List[RadiusAVP] to the message if not already present

`:<<` or `putOrReplace` adds a RadiusAVP, an Option[RadiusAVP] or a List[RadiusAVP] to the message, replacing the existing values

### Removing AVP from the RadiusPacket

`removeAll(String)` or `removeAll(codeId, vendorId)` should be used.
​	
### JSON format for Radius packets

There is an implicit conversion DiameterPacket to/from JSON. In the following example, a RadiusPacket
is created parsing from a String. Note that the radius attributes are *always* Arrays

```
val sAccountingRequest = s"""
  {
    "code": 4,
    "avps": {
      "NAS-IP-Address": ["1.1.1.1"],
      "NAS-Port": [0],
      "User-Name": ["test@database"],
      "Acct-Session-Id": ["${acctSessionId}"],
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

