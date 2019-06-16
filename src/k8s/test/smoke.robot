*** Settings *** 
Documentation    Smoke tests for radius/diameter. Use pip install --upgrade RESTinstance
Library          OperatingSystem
Library          Process
Library 		 REST    http://localhost:9090

*** Variables *** 
${aaaserver}     		%{HOME}/Yaas/target/universal/stage/bin/aaaserver
${k8sClientConfig}      %{HOME}/Yaas/src/k8s/conf/client

*** Test Cases *** 	
Radius
	[Documentation]     Launch Radius server
#	${radius_client}    Start Process      ${aaaserver}    -Dinstance\=radius    -Dconfig.file\=${k8sClientConfig}/aaa-default.conf     -Dlogback.configurationFile\=${k8sClientConfig}/logback-default.xml    stdout=radius.stdout.log    stderr=radius.stderr.log
#	Wait for Process    ${radius_client}
#    ${redirector}       Start Process      kubectl     --namespace monitoring port-forward svc/prometheus-k8s 9090
    GET                 /api/v1/query?query=sum(radius_server_requests\{pod=\'\yaas-server-0'\})
    String     $.data.result[0].value[1]    pattern=18...
#    Terminate Process    ${redirector}


