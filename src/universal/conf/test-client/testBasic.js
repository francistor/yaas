var TODO = "SendReject filter, Vala"

var sessionsURL = "http://localhost:19503/sessions/find";
	
var testItems = [
	{
		"description": "Standard Access Request for existing / standard user",
		"type": "radiusRequest",
		"request": {
			"code": 1,
			"avps": {
			  "Acct-Session-Id": ["acctsessionid_0"],
			  "NAS-IP-Address": ["1.1.1.1"],
			  "NAS-Port": [0],
			  "User-Name": ["user_0@database"],
			  "User-Password": ["password!_0"]
			}
		},
		"radiusGroup": "yaas-server-group",
		"timeout": 2000,
		"retries": 1,
		"validations":[
			["code", 2],
			["attributeValue", "Session-Timeout", 3600],
			["attributeValue", "Framed-Protocol", "PPP"],
			["attributeValueContains", "Unisphere-Service-Bundle", "Aservice_0"],
			["attributeValueContains", "Class", "C:legacy_0"],
			["attributeValueContains", "Class", "S:service_0"]
		]
	},
	{
		"description": "Accounting request",
		"type": "radiusRequest",
		"request": {
			"code": 4,
			"avps": {
			  "Acct-Session-Id": ["acctSessionId_0"],
			  "NAS-IP-Address": ["1.1.1.1"],
			  "NAS-Port": [1],
			  "User-Name": ["user_0@database"],
			  "Framed-IP-Address": ["200.0.0.0"],
			  "Acct-Status-Type": ["Start"]
			}
		},
		"radiusGroup": "yaas-server-group",
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
			"queryString": "ipAddress=200.0.0.0",
		},
		"validations":[
			["jsonArray0PropertyValue", "ipAddress", "200.0.0.0"],
			["jsonArray0PropertyValue", "acctSessionId", "acctSessionId_0"]
		]
	},
	{
		"description": "Access request for blocked user where pcautiv is addon",
		"type": "radiusRequest",
		"request": {
			"code": 1,
			"avps": {
			  "Acct-Session-Id": ["acctSessionId_1"],
			  "NAS-IP-Address": ["1.1.1.1"],
			  "NAS-Port": [1],
			  "User-Name": ["user_1@database"],
			  "User-Password": ["password!_1"]
			}
		},
		"radiusGroup": "yaas-server-group",
		"timeout": 1000,
		"retries": 1,
		"validations":[
			["code", 2],
			["attributeValue", "Session-Timeout", 3600],
			["attributeValueContains", "Unisphere-Service-Bundle", "Aservice_1"],
			["attributeValueContains", "Huawei-Account-Info", "Aservice_1"],
			["attributeValueContains", "Class", "C:legacy_1"],
			["attributeValueContains", "Class", "S:service_1"],
			["attributeValueContains", "Huawei-Account-Info", "ApcautivOnline"]
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
		"radiusGroup": "yaas-server-group",
		"timeout": 1000,
		"retries": 1,
		"validations":[
			["code", 2],
			["attributeValue", "Session-Timeout", 2400],
			["attributeValueContains", "Unisphere-Service-Bundle", "Aacs"],
			["attributeValueContains", "Huawei-Account-Info", "Aacs"],
			["attributeValueContains", "Class", "C:legacy_12"],
			["attributeValueContains", "Class", "S:acs"]
		]
	},
	{
	"description": "Access request for betatester (override session-timeout, auth file, no proxy)",
		"type": "radiusRequest",
		"request": {
			"code": 1,
			"avps": {
			  "Acct-Session-Id": ["acctSessionId_13"],
			  "NAS-IP-Address": ["1.1.1.1"],
			  "NAS-Port": [0],
			  "User-Name": ["betatester@betatester"],
			  "User-Password": ["mypassword"]
			}
		},
		"radiusGroup": "yaas-server-group",
		"timeout": 1000,
		"retries": 1,
		"validations":[
			["code", 2],
			["attributeValue", "Session-Timeout", 1200],
			["attributeValueContains", "Unisphere-Service-Bundle", "Abetatester"],
			["attributeValueContains", "Huawei-Account-Info", "Abetatester"],
			["attributeValueContains", "Class", "C:legacy_0"],
			["attributeValueContains", "Class", "S:betatester"]
		]
	},
	{
		"description": "Standard Access Request with publi",
		"type": "radiusRequest",
		"request": {
			"code": 1,
			"avps": {
			  "Acct-Session-Id": ["acctsessionid_3"],
			  "NAS-IP-Address": ["1.1.1.1"],
			  "NAS-Port": [3],
			  "User-Name": ["user_3@database"],
			  "User-Password": ["password!_3"]
			}
		},
		"radiusGroup": "yaas-server-group",
		"timeout": 2000,
		"retries": 1,
		"validations":[
			["code", 2],
			["attributeValue", "Session-Timeout", 300],
			["attributeValue", "Framed-Protocol", "PPP"],
			["attributeValueContains", "Unisphere-Service-Bundle", "Aservice_3"],
			["attributeValueContains", "Huawei-Account-Info", "Aservice_3"],
			["attributeValueContains", "Huawei-Account-Info", "Aaddon_1"],
			["attributeValueContains", "Class", "C:legacy_3"],
			["attributeValueContains", "Class", "S:service_3"]
			
		]
	},
	{
		"description": "Rejected due to invalid password and domain with default sendReject=yes",
		"type": "radiusRequest",
		"request": {
			"code": 1,
			"avps": {
			  "Acct-Session-Id": ["acctsessionid_5"],
			  "NAS-IP-Address": ["1.1.1.1"],
			  "NAS-Port": [0],
			  "User-Name": ["user_5@database"],
			  "User-Password": ["<bad-password>"]
			}
		},
		"radiusGroup": "yaas-server-group",
		"timeout": 2000,
		"retries": 1,
		"validations":[
			["code", 3],
			["attributeValueContains", "Reply-Message", "Authorization rejected"]
		]
	},
	{
		"description": "Rejected due to invalid password and domain with sendReject=no",
		"type": "radiusRequest",
		"request": {
			"code": 1,
			"avps": {
			  "Acct-Session-Id": ["acctsessionid_6"],
			  "NAS-IP-Address": ["1.1.1.1"],
			  "NAS-Port": [0],
			  "User-Name": ["user_0@databasenr"],
			  "User-Password": ["<bad-password>"]
			}
		},
		"radiusGroup": "yaas-server-group",
		"timeout": 2000,
		"retries": 1,
		"validations":[
			["code", 2],
			["attributeValue", "Session-Timeout", 3600],
			["attributeNotPresent", "Framed-Protocol"],
			["attributeValueContains", "Unisphere-Service-Bundle", "Areject"],
			["attributeValueContains", "Huawei-Account-Info", "Areject"],
			["attributeValueContains", "Class", "C:legacy_0"],
			["attributeValueContains", "Class", "R:1"]
			
		]
	},
    {
        "description": "Accounting with copy mode",
        "type": "radiusRequest",
        "request": {
            "code": 4,
            "avps": {
			  "Acct-Session-Id": ["acctSessionId_copy"],
			  "NAS-IP-Address": ["1.1.1.1"],
			  "NAS-Port": [10],
			  "User-Name": ["copy@database"],
			  "Framed-IP-Address": ["200.0.0.10"],
			  "Acct-Status-Type": ["Start"],
			  "Class": ["C:legacy_10"]
            }
        },
        "radiusGroup": "yaas-server-group",
        "timeout": 2000,
        "retries": 0,
        "validations":[
			["code", 5]
        ]
    },
    {
        "description": "wait",
        "type": "wait",
        "waitMillis": 500,
        "validations":[]
    },
    {
        "description": "Copied session 1 stored",
        "type": "httpGetRequest",
        "request": {
            "url": sessionsURL,
            "queryString": "acctSessionId=CC-acctSessionId_copy",
        },
        "validations":[
            ["jsonArray0PropertyValue", "ipAddress", "CC-200.0.0.10"],
            ["jsonArray0PropertyValue", "acctSessionId", "CC-acctSessionId_copy"]
        ]
    },
    {
        "description": "Copied session 2 stored",
        "type": "httpGetRequest",
        "request": {
            "url": sessionsURL,
            "queryString": "ipAddress=CC2-200.0.0.10",
        },
        "validations":[
            ["jsonArray0PropertyValue", "ipAddress", "CC2-200.0.0.10"],
            ["jsonArray0PropertyValue", "acctSessionId", "CC2-acctSessionId_copy"]
        ]
    },
    {
        "description": "CDR Found",
        "type": "readFile",
        "fileName": "/var/yaas/cdr/session-hot/cdr_copied.txt",
        "validations":[
            ["contains", "acctSessionId_copy"],
            ["contains", "\"legacy_10\""]
        ]
    }
];

load(baseURL + "radiusTestLib.js");