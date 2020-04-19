var sessionsURL = "http://localhost:19503/sessions/find";
	
var testItems = [
    {
        "description": "Basic Access-Request of standard user in database. Line guiding",
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
        "radiusGroup": "server-group",
        "timeout": 2000,
        "retries": 0,
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
        "description": "Request for Service-Remote-Definition",
        "type": "radiusRequest",
        "request": {
            "code": 1,
            "avps": {
              "Acct-Session-Id": ["acctsessionid_1"],
              "NAS-IP-Address": ["1.1.1.1"],
              "User-Name": ["service_1"],
              "Service-Type": ["Outbound-User"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 2000,
        "retries": 0,
        "validations":[
            ["code", 2],
            ["attributeValueContains", "Huawei-AVPair", "service:accounting-scheme:telefonica"],
            ["attributeValueContains", "Huawei-AVPair", "service:radius-server-group:psa"],
            ["attributeValue", "Huawei-Input-Committed-Information-Rate", 4000000],
            ["attributeValue", "Huawei-Output-Committed-Information-Rate", 2000000]
        ]
    },
    {
     "description": "Prepaid Request",
     "type": "radiusRequest",
     "request": {
         "code": 1,
         "avps": {
           "Acct-Session-Id": ["acctsessionid_1"],
           "NAS-IP-Address": ["1.1.1.1"],
           "NAS-Port": [1],
           "User-Name": ["user_1@database"],
           "Huawei-Service-Info": ["Nservice_1"]
         }
     },
     "radiusGroup": "server-group",
     "timeout": 2000,
     "retries": 0,
     "validations":[
         ["code", 2],
         ["attributeValue", "Huawei-Remanent-Volume", 1000]
     ]
    }
];

load(baseURL + "radiusTestLib.js");



