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
	[Documentation]     Radius requests

	# Minimum is 5000 for warmup, and 1000 for each test
	Set Environment Variable    YAAS_REQUEST_NUMBER    1000

	# Reset statistics
	Run Process         kubectl     exec -it kagent -- curl -X PATCH http://yaas-server-0.yaas-server:19000/radius/metrics/reset
	Run Process         kubectl     exec -it kagent -- curl -X PATCH http://yaas-server-1.yaas-server:19000/radius/metrics/reset
	Run Process         kubectl     exec -it kagent -- curl -X PATCH http://yaas-superserver-0.yaas-superserver:19000/radius/metrics/reset
	Run Process         kubectl     exec -it kagent -- curl -X PATCH http://yaas-superserver-0.yaas-superserver:19000/radius/metrics/reset

	# Redirect Promehteus port
	${redirector}       Start Process      kubectl     --namespace monitoring port-forward svc/prometheus-k8s 9090

	# Run radius client
	${radius_client}    Start Process      ${aaaserver}    -Dinstance\=radius    -Dconfig.file\=${k8sClientConfig}/aaa-default.conf     -Dlogback.configurationFile\=${k8sClientConfig}/logback-default.xml    stdout=radius.stdout.log    stderr=radius.stderr.log

	# Wait
	Sleep    20 seconds

	# Kill some pod

	# Wait for termination of radius client
	Wait for Process    ${radius_client}

	# Verify statistics /api/v1/query?query=sum(radius_server_requests\{pod=\'\yaas-server-0'\})
    GET                 /api/v1/query?query=sum(radius_server_requests\{})
    String     $.data.result[0].value[1]    pattern=70...

    # Terminate Prometheus redirector process
	Terminate Process    ${redirector}


