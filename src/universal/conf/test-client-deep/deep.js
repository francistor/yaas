var sessionsURL = "http://localhost:19503/sessions/find";

var TODO = "No proxy if group is <<none>>, acs, betatester, speedy, usability 4, second copy target";
	
var testItems = [
    {
        "description": "Basic Access-Request of standard user in database",
        "documentation": "Attributes from different sources. Session-Timeout from global config, Service-Type anc Cisco-AAPair from domain config, Framed-Protocol from Proxy, Unishpere-Service-Bundle and Huawei-Account-Info from service config",
        "type": "radiusRequest",
        "request": {
            "code": 1,
            "avps": {
              "Acct-Session-Id": ["acctsessionid_0"],
              "NAS-IP-Address": ["1.1.1.1"],
              "NAS-Port": [0],
              "User-Name": ["user_0@database.provision.p0.r0.bb"],
              "User-Password": ["password!_0"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 3000,
        "retries": 0,
        "validations":[
            ["code", 2],
            ["attributeValue", "Session-Timeout", 3600],
            ["attributeValue", "Service-Type", "Framed"],
            ["attributeValueContains", "Cisco-AVPair", "Hello=world"],
            ["attributeValue", "Framed-Protocol", "PPP"],
            ["attributeValueContains", "Unisphere-Service-Bundle", "Aservice_0"],
            ["attributeValueContains", "Huawei-Account-Info", "Aservice_0"],
            ["attributeValueContains", "Class", "C:legacy_0"],
            ["attributeValueContains", "Class", "S:service_0"]
        ]
    },
    {
        "description": "Basic Access-Request of standard user in file",
        "documentation": "Attributes from different sources. Session-Timeout from global config, Service-Type anc Ciso-AAPair from domain config, Framed-Protocol from Proxy, Unishpere-Service-Bundle and Huawei-Account-Info from service config",
        "type": "radiusRequest",
        "request": {
            "code": 1,
            "avps": {
              "Acct-Session-Id": ["acctsessionid_0"],
              "NAS-IP-Address": ["0.0.0.0"],
              "NAS-Port": [0],
              "User-Name": ["user_0@file.provision.p0.r0.bb"],
              "User-Password": ["password!_0"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 3000,
        "retries": 0,
        "validations":[
            ["code", 2],
            ["attributeValue", "Session-Timeout", 3600],
            ["attributeValue", "Service-Type", "Framed"],
            ["attributeValueContains", "Cisco-AVPair", "Hello=world"],
            ["attributeValue", "Framed-Protocol", "PPP"],
            ["attributeValueContains", "Unisphere-Service-Bundle", "Aservice_0"],
            ["attributeValueContains", "Huawei-Account-Info", "Aservice_0"],
            ["attributeValueContains", "Class", "C:legacy_0"],
            ["attributeValueContains", "Class", "S:service_0"]
        ]
    },
    {
        "description": "Using default domain, with database provision type",
        "documentation": "Attributes from different sources. Session-Timeout from global config, Service-Type anc Ciso-AAPair from domain config, Framed-Protocol from Proxy, Unishpere-Service-Bundle and Huawei-Account-Info from service config",
        "type": "radiusRequest",
        "request": {
            "code": 1,
            "avps": {
              "Acct-Session-Id": ["acctsessionid_0"],
              "NAS-IP-Address": ["1.1.1.1"],
              "NAS-Port": [0],
              "User-Name": ["user_0@non-exising-domain"],
              "User-Password": ["password!_0"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 3000,
        "retries": 0,
        "validations":[
            ["code", 2],
            ["attributeValue", "Session-Timeout", 3600],
            ["attributeValue", "Service-Type", "Framed"],
            ["attributeValueContains", "Cisco-AVPair", "Hello=world"],
            ["attributeValue", "Framed-Protocol", "PPP"],
            ["attributeValueContains", "Unisphere-Service-Bundle", "Aservice_0"],
            ["attributeValueContains", "Huawei-Account-Info", "Aservice_0"],
            ["attributeValueContains", "Class", "C:legacy_0"],
            ["attributeValueContains", "Class", "S:service_0"]
        ]
    },
    {
        "description": "Rejected due to incorrect password in database",
        "documentation": "",
        "type": "radiusRequest",
        "request": {
            "code": 1,
            "avps": {
              "Acct-Session-Id": ["acctsessionid_incorrect_password_db"],
              "NAS-IP-Address": ["1.1.1.1"],
              "NAS-Port": [0],
              "User-Name": ["user_0@database.provision.p0.r0.bb"],
              "User-Password": ["<bad-password>"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 3000,
        "retries": 0,
        "validations":[
            ["code", 3],
            ["attributeValueContains", "Reply-Message", "rejected"]
        ]
    },
    {
        "description": "Rejected due to incorrect password in file",
        "documentation": "",
        "type": "radiusRequest",
        "request": {
            "code": 1,
            "avps": {
              "Acct-Session-Id": ["acctsessionid_incorrect_password_file"],
              "NAS-IP-Address": ["1.1.1.1"],
              "NAS-Port": [0],
              "User-Name": ["user_0@file.provision.p0.r0.bb"],
              "User-Password": ["<bad-password>"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 3000,
        "retries": 0,
        "validations":[
            ["code", 3],
            ["attributeValueContains", "Reply-Message", "rejected"]
        ]
    },
    {
        "description": "Blocked user where service is not addon",
        "documentation": "",
        "type": "radiusRequest",
        "request": {
            "code": 1,
            "avps": {
              "Acct-Session-Id": ["acctsessionid_blocked_basic"],
              "NAS-IP-Address": ["1.1.1.1"],
              "NAS-Port": [1],
              "User-Name": ["user_0@database.provision.p0.r0.bb"],
              "User-Password": ["password!_1"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 3000,
        "retries": 0,
        "validations":[
            ["code", 2],
            ["attributeValue", "Session-Timeout", 3600],
            ["attributeValue", "Service-Type", "Framed"],
            ["attributeValueContains", "Cisco-AVPair", "Hello=world"],
            ["attributeValue", "Framed-Protocol", "PPP"],
            ["attributeValueContains", "Unisphere-Service-Bundle", "Apcautiv"],
            ["attributeValueContains", "Huawei-Account-Info", "Apcautiv"],
            ["attributeValueContains", "Class", "C:legacy_1"],
            ["attributeValueContains", "Class", "S:pcautiv"],
            ["attributeValueDoesNotContain", "Class", "A:"]
        ]
    },
    {
        "description": "Blocked user where service is addon",
        "documentation": "",
        "type": "radiusRequest",
        "request": {
            "code": 1,
            "avps": {
              "Acct-Session-Id": ["acctsessionid_blocked_addon"],
              "NAS-IP-Address": ["1.1.1.1"],
              "NAS-Port": [1],
              "User-Name": ["user_0@database.provision.p0.r0.ba"],
              "User-Password": ["password!_1"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 3000,
        "retries": 0,
        "validations":[
            ["code", 2],
            ["attributeValue", "Session-Timeout", 3600],
            ["attributeValue", "Service-Type", "Framed"],
            ["attributeValueContains", "Cisco-AVPair", "Hello=world"],
            ["attributeValue", "Framed-Protocol", "PPP"],
            ["attributeValueContains", "Unisphere-Service-Bundle", "Aservice_1"],
            ["attributeValueContains", "Huawei-Account-Info", "Aservice_1"],
            ["attributeValueContains", "Huawei-Account-Info", "ApcautivOnline"],
            ["attributeValueContains", "Class", "C:legacy_1"],
            ["attributeValueContains", "Class", "S:service_1"],
            ["attributeValueContains", "Class", "A:pcautivOnline"]
        ]
    },
    {
        "description": "Rejected user with basic reject service",
        "documentation": "",
        "type": "radiusRequest",
        "request": {
            "code": 1,
            "avps": {
              "Acct-Session-Id": ["acctsessionid_reject_basic"],
              "NAS-IP-Address": ["1.1.1.1"],
              "NAS-Port": [0],
              "User-Name": ["user_0@database.provision.p0.r1b.bb.reject"],
              "User-Password": ["password!_0"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 3000,
        "retries": 0,
        "validations":[
            ["code", 2],
            ["attributeValue", "Session-Timeout", 3600],
            ["attributeValue", "Service-Type", "Framed"],
            ["attributeValueContains", "Cisco-AVPair", "Hello=world"],
            ["attributeValueContains", "Unisphere-Service-Bundle", "Areject"],
            ["attributeValueContains", "Huawei-Account-Info", "Areject"],
            ["attributeValueDoesNotContain", "Huawei-Account-Info", "online"],
            ["attributeValueContains", "Class", "C:legacy_0"],
            ["attributeValueContains", "Class", "S:reject"],
            ["attributeValueDoesNotContain", "Class", "A:"]
        ]
    },
    {
        "description": "Rejected user with addon reject service",
        "documentation": "",
        "type": "radiusRequest",
        "request": {
            "code": 1,
            "avps": {
              "Acct-Session-Id": ["acctsessionid_reject_addon"],
              "NAS-IP-Address": ["1.1.1.1"],
              "NAS-Port": [0],
              "User-Name": ["user_0@database.provision.p0.r1a.bb.reject"],
              "User-Password": ["password!_0"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 3000,
        "retries": 0,
        "validations":[
            ["code", 2],
            ["attributeValue", "Session-Timeout", 3600],
            ["attributeValue", "Service-Type", "Framed"],
            ["attributeValueContains", "Cisco-AVPair", "Hello=world"],
            ["attributeValueContains", "Unisphere-Service-Bundle", "Aservice_0"],
            ["attributeValueContains", "Huawei-Account-Info", "Aservice_0"],
            ["attributeValueContains", "Huawei-Account-Info", "ArejectOnline"],
            ["attributeValueContains", "Class", "C:legacy_0"],
            ["attributeValueContains", "Class", "S:service_0"],
            ["attributeValueContains", "Class", "A:rejectOnline"]
        ]
    },
    {
        "description": "Rejected user with sendReject = filter and reply-message matching",
        "documentation": "",
        "type": "radiusRequest",
        "request": {
            "code": 1,
            "avps": {
              "Acct-Session-Id": ["acctsessionid_reject_filter_match"],
              "NAS-IP-Address": ["1.1.1.1"],
              "NAS-Port": [0],
              "User-Name": ["portal@database.provision.p0.r1bfilter.bb.reject"],
              "User-Password": ["password!_0"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 3000,
        "retries": 0,
        "validations":[
            ["code", 2],
            ["attributeValue", "Session-Timeout", 3600],
            ["attributeValue", "Service-Type", "Framed"],
            ["attributeValueContains", "Cisco-AVPair", "Hello=world"],
            ["attributeValueContains", "Unisphere-Service-Bundle", "Areject"],
            ["attributeValueContains", "Huawei-Account-Info", "Areject"],
            ["attributeValueContains", "Class", "C:legacy_0"],
            ["attributeValueContains", "Class", "S:reject"],
            ["attributeValueDoesNotContain", "Class", "A:"]
        ]
    },
    {
        "description": "Rejected user with sendReject = filter and reply-message not matching",
        "documentation": "",
        "type": "radiusRequest",
        "request": {
            "code": 1,
            "avps": {
              "Acct-Session-Id": ["acctsessionid_reject_filter_not_match"],
              "NAS-IP-Address": ["1.1.1.1"],
              "NAS-Port": [0],
              "User-Name": ["user_0@database.provision.p0.r1bfilter.bb.reject"],
              "User-Password": ["password!_0"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 3000,
        "retries": 0,
        "validations":[
            ["code", 3]
        ]
    },
    {
        "description": "Null password in database is not validated. Addon service",
        "documentation": "",
        "type": "radiusRequest",
        "request": {
            "code": 1,
            "avps": {
              "Acct-Session-Id": ["acctsessionid_null_password_addon"],
              "NAS-IP-Address": ["1.1.1.1"],
              "NAS-Port": [3],
              "User-Name": ["user_3@database.provision.p0.r0.bb"],
              "User-Password": ["<not-validated>"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 3000,
        "retries": 0,
        "validations":[
            ["code", 2],
            ["attributeValue", "Session-Timeout", 300],
            ["attributeValue", "Service-Type", "Framed"],
            ["attributeValueContains", "Cisco-AVPair", "Hello=world"],
            ["attributeValue", "Framed-Protocol", "PPP"],
            ["attributeValueContains", "Unisphere-Service-Bundle", "Aservice_3"],
            ["attributeValueContains", "Huawei-Account-Info", "Aservice_3"],
            ["attributeValueContains", "Huawei-Account-Info", "Aaddon_1"],
            ["attributeValueContains", "Class", "C:legacy_3"],
            ["attributeValueContains", "Class", "S:service_3"],
            ["attributeValueContains", "Class", "A:addon_1"]
        ]
    },
    {
       "description": "User not found without permissive service, and is rejected",
        "documentation": "",
        "type": "radiusRequest",
        "request": {
            "code": 1,
            "avps": {
              "Acct-Session-Id": ["acctsessionid_notfound_reject"],
              "NAS-IP-Address": ["1.1.1.0"],
              "NAS-Port": [0],
              "User-Name": ["user_0@database.provision.p0.r0.bb"],
              "User-Password": ["password!_0"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 3000,
        "retries": 0,
        "validations":[
            ["code", 3],
            ["attributeValueContains", "Reply-Message", "Client not provisioned"]
        ]
    },
    {
        "description": "User not found with permissive service",
        "documentation": "",
        "type": "radiusRequest",
        "request": {
            "code": 1,
            "avps": {
              "Acct-Session-Id": ["acctsessionid_notfound_permissive"],
              "NAS-IP-Address": ["1.1.1.0"],
              "NAS-Port": [3],
              "User-Name": ["user_0@database.provision.p1.r0.bb"],
              "User-Password": ["password!_0"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 3000,
        "retries": 0,
        "validations":[
            ["code", 2],
            ["attributeValue", "Session-Timeout", 2400],
            ["attributeValue", "Service-Type", "Framed"],
            ["attributeValueContains", "Cisco-AVPair", "Hello=world"],
            ["attributeValue", "Framed-Protocol", "PPP"],
            ["attributeValueContains", "Unisphere-Service-Bundle", "Apermissive"],
            ["attributeValueContains", "Huawei-Account-Info", "Apermissive"],
            ["attributeValueContains", "Class", "C:not-found"],
            ["attributeValueContains", "Class", "S:permissive"]
        ]
    },
    {
        "description": "Fixed IP address, and vala",
        "documentation": "",
        "type": "radiusRequest",
        "request": {
            "code": 1,
            "avps": {
              "Acct-Session-Id": ["acctsessionid_ip_vala"],
              "NAS-IP-Address": ["1.1.1.1"],
              "NAS-Port": [2],
              "User-Name": ["user_2@database.provision.p1.r0.bb"],
              "User-Password": ["password!_2"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 3000,
        "retries": 0,
        "validations":[
            ["code", 2],
            ["attributeValue", "Session-Timeout", 900],
            ["attributeValue", "Service-Type", "Framed"],
            ["attributeValueContains", "Cisco-AVPair", "Hello=world"],
            ["attributeValue", "Framed-Protocol", "PPP"],
            ["attributeValueContains", "Unisphere-Service-Bundle", "Avala"],
            ["attributeValueContains", "Huawei-Account-Info", "Avala"],
            ["attributeValueContains", "Huawei-Account-Info", "Aaddon_vala"],
            ["attributeValueContains", "Class", "C:legacy_2"],
            ["attributeValueContains", "Class", "S:vala"],
            ["attributeValue", "Framed-IP-Address", "100.100.100.100"],
            ["attributeValueContains", "Delegated-IPv6-Prefix", "bebe"],
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
        "timeout": 3000,
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
           "NAS-Port": [0],
           "User-Name": ["user_1@database.provision.p0.r0.bb"],
           "Huawei-Service-Info": ["Nservice_1"]
         }
     },
     "radiusGroup": "server-group",
     "timeout": 3000,
     "retries": 0,
     "validations":[
         ["code", 2],
         ["attributeValue", "Huawei-Remanent-Volume", 1000]
     ]
    },
    {
        "description": "Session Accounting request. No copy",
        "type": "radiusRequest",
        "request": {
            "code": 4,
            "avps": {
              "Acct-Session-Id": ["acctSessionId_0"],
              "NAS-IP-Address": ["1.1.1.1"],
              "NAS-Port": [0],
              "User-Name": ["user_0@database.provision.p0.r0.bb"],
              "Framed-IP-Address": ["200.0.0.0"],
              "Acct-Status-Type": ["Start"],
              "Class": ["C:legacy_0", "S:service_0", "P:phone_0", "M:mac_0"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 2000,
        "retries": 1,
        "validations":[
            ["code", 5]
        ]
    },
    {
        "description": "wait",
        "type": "wait",
        "waitMillis": 300,
        "validations":[]
    },
    {
        "description": ">> Copied CDR file has not been created",
        "type": "readFile",
        "fileName": "/var/yaas/cdr/session-hot/cdr_copied.txt",
        "validations":[
            ["shouldFail"]
        ]
    },
    {
        "description": ">> Service CDR file has not been created",
        "type": "readFile",
        "fileName": "/var/yaas/cdr/service/cdr_%d{yyyyMMdd}.txt",
        "validations":[
            ["shouldFail"]
        ]
    },
    {
        "description": ">> Inline CDR created with expected attributes",
        "type": "readFile",
        "fileName": "/var/yaas/cdr/session/cdr_%d{yyyyMMdd}.txt",
        "validations":[
            ["contains", "acctSessionId_0"],
            ["contains", "\"legacy_0\""],
            ["contains", "\"phone_0\""],
            ["contains", "PSA-BRAS-NAS-IP-Address"],
            ["contains", "PSA-MAC-Address"]
        ]
    },
    {
        "description": "Session Accounting request. With copy",
        "type": "radiusRequest",
        "request": {
            "code": 4,
            "avps": {
              "Acct-Session-Id": ["acctSessionId_1"],
              "NAS-IP-Address": ["1.1.1.1"],
              "NAS-Port": [1],
              "User-Name": ["copy@database.provision.p0.r0.bb"],
              "Framed-IP-Address": ["200.0.0.1"],
              "Acct-Status-Type": ["Start"],
              "Class": ["C:legacy_1", "S:service_1"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 2000,
        "retries": 1,
        "validations":[
            ["code", 5]
        ]
    },
    {
        "description": "wait",
        "type": "wait",
        "waitMillis": 300,
        "validations":[]
    },
    {
        "description": ">> CDR in hot-copy created with expected attributes",
        "type": "readFile",
        "fileName": "/var/yaas/cdr/session-hot/cdr_copied.txt",
        "validations":[
            ["contains", "200.0.0.1"]
        ]
    },
    {
        "description": ">> Service CDR file has not been created",
        "type": "readFile",
        "fileName": "/var/yaas/cdr/service/cdr_%d{yyyyMMdd}.txt",
        "validations":[
            ["shouldFail"]
        ]
    },
    {
        "description": ">> CDR inline found with expected attributes",
        "type": "readFile",
        "fileName": "/var/yaas/cdr/session/cdr_%d{yyyyMMdd}.txt",
        "validations":[
            ["contains", "acctSessionId_1"],
            ["contains", "\"legacy_1\""]
        ]
    },
    {
        "description": ">> CDR in proxy server found (Inline)",
        "type": "readFile",
        "fileName": "cdr/cdr_superserver.txt",
        "validations":[
            ["contains", "SS-acctSessionId_1"],
            ["contains", "\"legacy_1\""]
        ]
    },
    {
        "description": ">> CDR in proxy server found (Copy)",
        "type": "readFile",
        "fileName": "cdr/cdr_superserver.txt",
        "validations":[
            ["contains", "CC-acctSessionId_1"],
            ["contains", "\"legacy_1\""]
        ]
    },
    {
        "description": ">> Second CDR in proxy server found (Copy)",
        "type": "readFile",
        "fileName": "cdr/cdr_superserver.txt",
        "validations":[
            ["contains", "CC-acct-session-id-copied"]
        ]
    },
    {
        "description": "Service Accounting request",
        "type": "radiusRequest",
        "request": {
            "code": 4,
            "avps": {
              "Acct-Session-Id": ["acctSessionId_2"],
              "NAS-IP-Address": ["1.1.1.1"],
              "NAS-Port": [2],
              "User-Name": ["user_2@database.provision.p0.r0.bb"],
              "Framed-IP-Address": ["200.0.0.2"],
              "Acct-Status-Type": ["Start"],
              "Class": ["C:legacy_2", "S:service_2"],
              "Huawei-Service-Info": ["Aservice_2"]
            }
        },
        "radiusGroup": "server-group",
        "timeout": 2000,
        "retries": 1,
        "validations":[
            ["code", 5]
        ]
    },
    {
        "description": "wait",
        "type": "wait",
        "waitMillis": 300,
        "validations":[]
    },
    {
      "description": ">> Service CDR created",
      "type": "readFile",
      "fileName": "/var/yaas/cdr/service/cdr_%d{yyyyMMdd}.txt",
      "validations":[
          ["contains", "acctSessionId_2"],
          ["contains", "\"legacy_2\""],
          ["contains", "PSA-ServiceName"],
          ["contains", "PSA-LegacyClientId"]
      ]
    },
    {
      "description": ">> Session CDR not created",
      "type": "readFile",
      "fileName": "/var/yaas/cdr/session/cdr_%d{yyyyMMdd}.txt",
      "validations":[
          ["notContains", "acctSessionId_2"],
          ["notContains", "\"legacy_2\""],
          ["notContains", "PSA-ServiceName"]
      ]
    },
    {
      "description": ">> Service CDR not found in proxy server",
      "type": "readFile",
      "fileName": "cdr/cdr_superserver.txt",
      "validations":[
          ["notContains", "acctSessionId_2"],
          ["notContains", "legacy_2"]
      ]
    }
];

load(baseURL + "radiusTestLib.js");



