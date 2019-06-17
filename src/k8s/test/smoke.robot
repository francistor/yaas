*** Settings *** 
Documentation    Smoke tests for radius/diameter.
Library          OperatingSystem
Library          Process

# Use pip install --upgrade RESTinstance
Library 		 REST    http://localhost:9090

*** Variables *** 
${aaaserver}     		%{HOME}/Yaas/target/universal/stage/bin/aaaserver
${k8sClientConfig}      %{HOME}/Yaas/src/k8s/conf/client

*** Test Cases *** 	
Radius
	[Documentation]     Radius requests

	# Minimum is 5000 for warmup, and 1000 for each test
	Set Environment Variable    YAAS_TEST_REQUESTS   1000

	# Reset statistics
	Run Process         kubectl     exec    kagent     --     curl    -X    PATCH    http://yaas-server-0.yaas-server:19000/radius/metrics/reset
	Run Process         kubectl     exec    kagent     --     curl    -X    PATCH    http://yaas-server-1.yaas-server:19000/radius/metrics/reset
	Run Process         kubectl     exec    kagent     --     curl    -X    PATCH    http://yaas-superserver-0.yaas-superserver:19000/radius/metrics/reset
	Run Process         kubectl     exec    kagent     --     curl    -X    PATCH    http://yaas-superserver-1.yaas-superserver:19000/radius/metrics/reset

	# Redirect Promehteus port
	${redirector}       Start Process      kubectl     --namespace     monitoring     port-forward     svc/prometheus-k8s     9090

	# Run radius client
	${radius_client}    Start Process      ${aaaserver}    -Dinstance\=radius    -Dconfig.file\=${k8sClientConfig}/aaa-default.conf     -Dlogback.configurationFile\=${k8sClientConfig}/logback-default.xml    stdout=radius.stdout.log    stderr=radius.stderr.log

	# Kill some pod
	Sleep    5 seconds
	Run Process   		kubectl     delete     pod     yaas-server-1

	# Wait for termination of radius client
	Wait for Process    ${radius_client}

    # Wait some time to gather Prometheus statistics (15 secs) or for the redirector process to set up
	Sleep    20 seconds

	# Verify statistics /api/v1/query?query=sum(radius_server_requests\{pod=\'\yaas-server-0'\})
	# The number of radius requests should be 8016
    GET                 /api/v1/query?query=sum(radius_server_requests\{\})
    String     $.data.result[0].value[1]    pattern=80..

    # Terminate Prometheus redirector process
	Terminate Process    ${redirector}


