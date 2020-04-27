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

// testIndexes is a Scala List. Handle with care in Javascript. You are warned!
// Build an array with the numbers of the tests to execute (testIndexes)
var testIndexes = [];
if(commandLine.length() > 0) testIndexes = commandLine.head().split(",");
else for(i = 0; i < testItems.length; i++) testIndexes[i] = i + 1;

// This index run from 0 to number of tests to be executed (size of testIndexes)
var j = 0;
executeNextTest();

function executeNextTest(){

	var callback = function(err, responseString){
		testItems[testIndexes[j] - 1]["validations"].forEach(function(validation, index){
			validate(err, responseString, validation);
		});
		j = j + 1;
		executeNextTest();
	}

	// Check finalization
	if(j >= testIndexes.length){
		print("");
		Notifier.end();
		return;
	}

	var testItem = testItems[testIndexes[j] - 1];
	print("[TEST] " +  testItem["description"]);

	if(testItem["type"] == "radiusRequest"){
		Yaas.radiusRequest(testItem.radiusGroup, JSON.stringify(testItem.request), testItem.timeout, testItem.retries, callback);
	} else if(testItem["type"] == "httpGetRequest"){
		Yaas.httpRequest(testItem.request.url + "?" + testItem.request.queryString, "GET", "{}", callback);
	}
}