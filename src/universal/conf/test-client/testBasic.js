var sessionsURL = "http://localhost:19503/sessions/find";
	
var testItems = [
	{
		"description": "Standard Access Request with OK result",
		"type": "radiusRequest",
		"request": {
			"code": 1,
			"avps": {
			  "Acct-Session-Id": ["acct-session-id-100"],
			  "NAS-IP-Address": ["1.1.1.1"],
			  "NAS-Port": [100],
			  "User-Name": ["user_1@database"],
			  "User-Password": ["password!_100"]
			}
		},
		"radiusGroup": "allServers",
		"timeout": 2000,
		"retries": 1,
		"validations":[
			["code", 2],
			["attributeValue", "Session-Timeout", 3600],
			["attributeValue", "Unisphere-Service-Bundle", "Abab00"],
			["attributeValueContains", "Class", "C=legacy_100"],
			["attributeValueContains", "Class", "S=service_0"]
		]
	}
];

/**
 * Helper functions
 */
function ok(message){
	print("[OK] " + message);
}

function fail(message){
	print("[ERROR] " + message);
}

/**
 * 
 * @param err the error object as returned by the Yaas.<request>
 * @param radiusResponse the response converted to JSON
 * @param validationItem the item to test, as one of the "validations" of a testItem
 * @returns
 */
function validate(err, radiusResponse, validationItem){
	// code
	if(validationItem[0] == "code"){
		var _code = validationItem[1];
		if(err) fail(err.message);
		else {
			if(radiusResponse["code"] == _code) ok("Code is " + _code);
			else fail("Code is " + radiusResponse["code"]);
		}
	}
	// attributeValue
	else if(validationItem[0] == "attributeValue"){
		var _attrName = validationItem[1];
		var _attrValue = validationItem[2];
		if(err) fail(err.message);
		else if(!radiusResponse["avps"][_attrName]) fail(_attrName + " not present");
		else {
			if(radiusResponse["avps"][_attrName][0] == _attrValue) ok(_attrName + " is " + _attrValue);
			else fail(_attrName + "is " + radiusResponse["avps"][_attrName]);
		}
	}
	// attributeValueContains
	else if(validationItem[0] == "attributeValueContains"){
		var _attrName = validationItem[1];
		var _attrValue = validationItem[2];
		if(err) fail(err.message);
		else if(!radiusResponse["avps"][_attrName]) fail(_attrName + " not present");
		else {
			if(radiusResponse["avps"][_attrName].indexOf(_attrValue) != -1) ok(_attrName + " contains " + _attrValue);
			else fail(_attrName + " is " + radiusResponse["avps"][_attrName]);
		}
	}
}

// Execute the tests specified in the "testItems" object
var testIndex = 0;
executeNextTest();

function executeNextTest(){
	
	var callback = function(err, responseString){
		testItems[testIndex]["validations"].forEach(function(validation, index){
			validate(err, JSON.parse(responseString), validation);
		});
		testIndex = testIndex + 1;
		executeNextTest();
	}
	
	// Check finalization
	if(testIndex >= testItems.length){
		print("Testing finished");
		Notifier.end();
		return;
	}

	var testItem = testItems[testIndex];
	print(testItem["description"]);
	
	if(testItem["type"] == "radiusRequest"){
		Yaas.radiusRequest(testItem.radiusGroup, JSON.stringify(testItem.request), testItem.timeout, testItem.retries, callback);
	} else if(testItem["type"] == "sessionRequest"){
		Yaas.httpRequest(sessionsURL + "?" + testItem["getSession"][0] +"=" + testItem["getSession"][1], "GET", "{}", callback);
	}
	
}



