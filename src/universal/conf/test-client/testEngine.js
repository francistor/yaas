var testItems = [
	{
		"type": "radiusRequest",
		"request": {
			"code": 4,
			"avps": {
			  "NAS-IP-Address": "1.1.1.1",
			  "NAS-Port": 1,
			  "User-Name": "test@database",
			  "Acct-Session-Id": "acct-session-id-1",
			  "Framed-IP-Address": "199.0.0.1",
			  "Acct-Status-Type": "Start"
			}
		},
		"validator": function(err, response){
			if(err) fail(err.message);
			else ok(response);
		},
		"radiusGroup": "allServers",
		"timeout": 2000,
		"retries": 1
		
	}
];

function ok(message){
	print("[OK] " + message);
}

function fail(message){
	print("[ERROR] " + message);
}

function executeTestItem(i) {
	if(testItems.length <= i){
		print("JS Testing finished");
		Notifier.end();
	}
	else {
		var testItem = testItems[i];
		if(testItem["type"] == "radiusRequest"){
			Yaas.radiusRequest(testItem.radiusGroup, JSON.stringify(testItem.request), testItem.timeout, testItem.retries, function(err, response){
				
				testItem.validator(err, response);
				executeTestItem(i + 1);
			});
		}
	}
}

executeTestItem(0);

