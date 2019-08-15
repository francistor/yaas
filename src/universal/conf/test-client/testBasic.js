var sessionsURL = "http://localhost:19503/sessions/find";
	
var testItems = [
	{
		"description": "Standard Access Request for existing / standard user",
		"type": "radiusRequest",
		"request": {
			"code": 1,
			"avps": {
			  "Acct-Session-Id": ["acctsessionid_1"],
			  "NAS-IP-Address": ["1.1.1.1"],
			  "NAS-Port": [1],
			  "User-Name": ["user_1@database"],
			  "User-Password": ["password!_1"]
			}
		},
		"radiusGroup": "testServer",
		"timeout": 2000,
		"retries": 1,
		"validations":[
			["code", 2],
			["attributeValue", "Session-Timeout", 3600],
			["attributeValue", "Framed-Protocol", "PPP"],
			["attributeValueContains", "Unisphere-Service-Bundle", "Abab01"],
			["attributeValueContains", "Class", "C=legacy_1"],
			["attributeValueContains", "Class", "S=service_1"]
		]
	},
	{
		"description": "Accounting request",
		"type": "radiusRequest",
		"request": {
			"code": 4,
			"avps": {
			  "Acct-Session-Id": ["acctSessionId_1"],
			  "NAS-IP-Address": ["1.1.1.1"],
			  "NAS-Port": [1],
			  "User-Name": ["user_1@database"],
			  "Framed-IP-Address": ["200.0.0.1"],
			  "Acct-Status-Type": ["Start"]
			}
		},
		"radiusGroup": "testServer",
		"timeout": 2000,
		"retries": 1,
		"validations":[
			["code", 5]
		]
	},
	{
		"description": "Session has been stored",
		"type": "httpGetRequest",
		"request": {
			"url": sessionsURL,
			"queryString": "ipAddress=200.0.0.1",
		},
		"validations":[
			["jsonArray0PropertyValue", "ipAddress", "200.0.0.1"],
			["jsonArray0PropertyValue", "acctSessionId", "acctSessionId_1"]
		]
	},
	{
		"description": "Access request for blocked user",
		"type": "radiusRequest",
		"request": {
			"code": 1,
			"avps": {
			  "Acct-Session-Id": ["acctSessionId_11"],
			  "NAS-IP-Address": ["1.1.1.1"],
			  "NAS-Port": [11],
			  "User-Name": ["user_11@database"],
			  "User-Password": ["password!_11"]
			}
		},
		"radiusGroup": "testServer",
		"timeout": 1000,
		"retries": 1,
		"validations":[
			["code", 2],
			["attributeValue", "Session-Timeout", 3600],
			["attributeValueContains", "Unisphere-Service-Bundle", "Abab03"],
			["attributeValueContains", "Class", "C=legacy_11"],
			["attributeValueContains", "Class", "S=service_3"],
			["attributeValueContains", "Unisphere-Service-Bundle", "Apcautiv"]
		]
	},
	{
		"description": "Access request for ACS (override service, auth none)",
		"type": "radiusRequest",
		"request": {
			"code": 1,
			"avps": {
			  "Acct-Session-Id": ["acctSessionId_12"],
			  "NAS-IP-Address": ["1.1.1.1"],
			  "NAS-Port": [12],
			  "User-Name": ["acs@acs"],
			  "User-Password": ["<factory-set-password>"]
			}
		},
		"radiusGroup": "testServer",
		"timeout": 1000,
		"retries": 1,
		"validations":[
			["code", 2],
			["attributeValue", "Session-Timeout", 3600],
			["attributeValueContains", "Unisphere-Service-Bundle", "Aacs"],
			["attributeValueContains", "Class", "C=legacy_12"],
			["attributeValueContains", "Class", "S=acs"]
		]
	},
	{
	"description": "Access request for betateter (override session-timeout, auth file, no proxy)",
		"type": "radiusRequest",
		"request": {
			"code": 1,
			"avps": {
			  "Acct-Session-Id": ["acctSessionId_13"],
			  "NAS-IP-Address": ["1.1.1.1"],
			  "NAS-Port": [13],
			  "User-Name": ["betatester@betatester"],
			  "User-Password": ["mypassword"]
			}
		},
		"radiusGroup": "testServer",
		"timeout": 1000,
		"retries": 1,
		"validations":[
			["code", 2],
			["attributeValue", "Session-Timeout", 1200],
			["attributeValueContains", "Unisphere-Service-Bundle", "Abetatester"],
			["attributeValueContains", "Class", "C=legacy_13"],
			["attributeValueContains", "Class", "S=betatester"]
		]
	},
	{
		"description": "Standard Access Request with publi",
		"type": "radiusRequest",
		"request": {
			"code": 1,
			"avps": {
			  "Acct-Session-Id": ["acctsessionid_4"],
			  "NAS-IP-Address": ["1.1.1.1"],
			  "NAS-Port": [4],
			  "User-Name": ["user_4@database"],
			  "User-Password": ["password!_4"]
			}
		},
		"radiusGroup": "testServer",
		"timeout": 2000,
		"retries": 1,
		"validations":[
			["code", 2],
			["attributeValue", "Session-Timeout", 300],
			["attributeValue", "Framed-Protocol", "PPP"],
			["attributeValueContains", "Unisphere-Service-Bundle", "Abab00"],
			["attributeValueContains", "Unisphere-Service-Bundle", "Aaddon_1"],
			["attributeValueContains", "Class", "C=legacy_4"],
			["attributeValueContains", "Class", "S=service_0"]
			
		]
	},
	{
		"description": "Rejected due to invalid password and domain with default sendReject=true",
		"type": "radiusRequest",
		"request": {
			"code": 1,
			"avps": {
			  "Acct-Session-Id": ["acctsessionid_5"],
			  "NAS-IP-Address": ["1.1.1.1"],
			  "NAS-Port": [5],
			  "User-Name": ["user_5@database"],
			  "User-Password": ["<bad-password>"]
			}
		},
		"radiusGroup": "testServer",
		"timeout": 2000,
		"retries": 1,
		"validations":[
			["code", 3],
			["attributeValue", "Reply-Message", "Incorrect User-Name or User-Password"]
		]
	},
	{
		"description": "Rejected due to invalid password and domain with sendReject=false",
		"type": "radiusRequest",
		"request": {
			"code": 1,
			"avps": {
			  "Acct-Session-Id": ["acctsessionid_5"],
			  "NAS-IP-Address": ["1.1.1.1"],
			  "NAS-Port": [5],
			  "User-Name": ["user_5@databasenr"],
			  "User-Password": ["<bad-password>"]
			}
		},
		"radiusGroup": "testServer",
		"timeout": 2000,
		"retries": 1,
		"validations":[
			["code", 2],
			["attributeValue", "Session-Timeout", 3600],
			["attributeNotPresent", "Framed-Protocol"],
			["attributeValueContains", "Unisphere-Service-Bundle", "Areject"],
			["attributeValueContains", "Class", "C=legacy_5"],
			["attributeValueContains", "Class", "R=1"]
			
		]
	}
];

/**
 * Helper functions
 */
function ok(message){
	print("\t[OK] " + message);
}

function fail(message){
	print("\t[ERROR] " + message);
}

function jsonHasPropertyValue(json, propName, propValue){
	if(!json) fail("Element not present");
	else {
		if(json[propName] == propValue) ok(propName + " is " + propValue);
		else{
			if(!json[propName]) fail(propName + " not present");
			else fail(propName + " is " + json[propName] + " instead of " + propValue)
		}
	}
}

/**
 * 
 * @param err the error object as returned by the Yaas.<request>
 * @param radiusResponse the response converted to JSON
 * @param validationItem the item to test, as one of the "validations" of a testItem
 * @returns
 */
function validate(err, response, validationItem){
	// code
	if(validationItem[0] == "code"){
		if(err) fail(err.message);
		else{
			var jResponse = JSON.parse(response);
			var _code = validationItem[1];
			if(jResponse["code"] == _code) ok("Code is " + _code);
			else fail("Code is " + jResponse["code"]);
		}
	}
	// attributeValue
	else if(validationItem[0] == "attributeValue"){
		if(err) fail(err.message);
		else{
			var jResponse = JSON.parse(response);
			var _attrName = validationItem[1];
			var _attrValue = validationItem[2];
			jsonHasPropertyValue(jResponse["avps"], _attrName, _attrValue);
		}
	}
	// attributeValue
	else if(validationItem[0] == "attributeNotPresent"){
		if(err) fail(err.message);
		else{
			var jResponse = JSON.parse(response);
			var _attrName = validationItem[1];
			if(jResponse[_attrName]) fail(_attrName + " is present"); else ok(_attrName + " not present");
		}
	}
	// attributeValueContains
	else if(validationItem[0] == "attributeValueContains"){
		if(err) fail(err.message);
		else {
			var jResponse = JSON.parse(response);
			var _attrName = validationItem[1];
			var _attrValue = validationItem[2];
			if(!jResponse["avps"][_attrName]) fail(_attrName + " not present");
			else if(jResponse["avps"][_attrName].indexOf(_attrValue) == -1) fail(_attrName + " has no " + _attrValue);
			else ok(_attrValue + " found in " + _attrName);
		}
	}
	// jsonPropertyValue
	else if(validationItem[0] == "jsonPropertyValue"){
		if(err) fail(err.message);
		else{
			var jResponse = JSON.parse(response);
			var _attrName = validationItem[1];
			var _attrValue = validationItem[2];
			jsonHasPropertyValue(jResponse, _attrName, _attrValue);
		}
	}
	// jsonArray0PropertyValue
	else if(validationItem[0] == "jsonArray0PropertyValue"){
		if(err) fail(err.message);
		else{
			var jResponse = JSON.parse(response);
			var _attrName = validationItem[1];
			var _attrValue = validationItem[2];
			if(!jResponse[0]) fail("Response is empty");
			else jsonHasPropertyValue(jResponse[0], _attrName, _attrValue);
		}
	}
}

// Execute the tests specified in the "testItems" object
var testIndex = 0;
executeNextTest();

function executeNextTest(){
	
	var callback = function(err, responseString){
		testItems[testIndex]["validations"].forEach(function(validation, index){
			validate(err, responseString, validation);
		});
		testIndex = testIndex + 1;
		executeNextTest();
	}
	
	// Check finalization
	if(testIndex >= testItems.length){
		print("");
		Notifier.end();
		return;
	}

	var testItem = testItems[testIndex];
	print("");
	print(testItem["description"]);
	
	if(testItem["type"] == "radiusRequest"){
		Yaas.radiusRequest(testItem.radiusGroup, JSON.stringify(testItem.request), testItem.timeout, testItem.retries, callback);
	} else if(testItem["type"] == "httpGetRequest"){
		Yaas.httpRequest(testItem.request.url + "?" + testItem.request.queryString, "GET", "{}", callback);
	}	
}



