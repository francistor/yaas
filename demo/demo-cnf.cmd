REM Setup environment
call "kubectl_env.bat"
call "C:\Program Files (x86)\Google\Cloud SDK\cloud_env.bat"
@echo off
cd c:\code\yaasws\yaas\doc\k8s

REM configure gcloud settings 
call gcloud config set proxy/type http
call gcloud config set proxy/address proxy.indra.es
call gcloud config set proxy/port 8080
call gcloud config set proxy/username frodriguezg
call gcloud config set proxy/password _Brumeando22

REM Or remove them
REM call gcloud config unset proxy/type
REM call gcloud config unset proxy/address
REM call gcloud config unset proxy/port 

REM Create cluster
call gcloud container clusters create yaas-cluster
call gcloud container clusters get-credentials yaas-cluster

REM Remove implicit limit to 100m
call kubectl delete limitrange limits

REM get target host IP address
call kubectl get nodes -o wide|gawk "BEGIN {line=0} /Ready/ {if(line==1) print $7; line++}" > scratch.txt
set /p NODE_ADDRESS= < scratch.txt
del scratch.txt

call kubectl get nodes -o wide|gawk "BEGIN {line=0} /Ready/ {if(line==2) print $7; line++}" > scratch.txt
set /p NODE_ADDRESS_SEC= < scratch.txt
del scratch.txt

call kubectl apply -f c:\code\yaasws\yaas\src\k8s\descriptors\namespace.yml 
call kubectl create configmap yaas-test-config --namespace yaas --from-literal YAAS_TEST_SERVER=%NODE_ADDRESS% --from-literal YAAS_TEST_SERVER_SEC=%NODE_ADDRESS_SEC%

REM Deploy kube-prometheus
call kubectl apply -f c:\code\yaasws\yaas\src\k8s\kprometheus-descriptors

REM monitor YAAS
call kubectl apply -f c:\code\yaasws\yaas\src\k8s\descriptors\serviceMonitor.yml

REM https://console.cloud.google.com/home/dashboard?project=sandbox-project-250013


echo.
echo Waiting for k8s deployment
:waitKubePrometheus
timeout /T 10 /nobreak 1>nul
call kubectl get pods --namespace monitoring |gawk "BEGIN {ready=1} {if($3 != \"STATUS\" && $3!= \"Running\") ready=0;} END {if(ready==1) print \"Ready\"; else print \".\"}" > scratch.txt
set /p STATUS= < scratch.txt
echo %STATUS%
if not "%STATUS:~0,5%"=="Ready" goto :waitKubePrometheus

echo.
set /P vvar="Press RETURN to deploy iAAA"

call kubectl apply -f c:\code\yaasws\yaas\src\k8s\descriptors\db-service.yml 
call kubectl apply -f c:\code\yaasws\yaas\src\k8s\descriptors\db.yml 
call kubectl apply -f c:\code\yaasws\yaas\src\k8s\descriptors\superserver-services.yml
call kubectl apply -f c:\code\yaasws\yaas\src\k8s\descriptors\superserver.yml
call kubectl apply -f c:\code\yaasws\yaas\src\k8s\descriptors\server-services.yml
call kubectl apply -f c:\code\yaasws\yaas\src\k8s\descriptors\server.yml
call kubectl apply -f c:\code\yaasws\yaas\src\k8s\descriptors\client-services.yml
call kubectl apply -f c:\code\yaasws\yaas\src\k8s\descriptors\radiusClient.yml
call kubectl apply -f c:\code\yaasws\yaas\src\k8s\descriptors\diameterClient.yml
call kubectl apply -f c:\code\yaasws\yaas\src\k8s\descriptors\kagent.yml
REM call kubectl apply -f c:\code\yaasws\yaas\src\k8s\descriptors\ignite.yml

echo.
echo Waiting for iAAA deployment
REM kubectl wait pod/yaas-server-1 --for condition=Ready --timeout=180s
:waitYaas
timeout /T 10 /nobreak 1>nul
call kubectl get pods --namespace yaas |gawk "BEGIN {ready=0} {if($1 == \"yaas-server-1\" && $2==\"1/1\" && $3==\"Running\") ready=1;} END {if(ready==1) print \"Ready\"; else print \".\"}" > scratch.txt
set /p STATUS= < scratch.txt
del scratch.txt
echo %STATUS%
if not "%STATUS:~0,5%"=="Ready" goto :waitYaas

echo.
set /P dummy="Press RETURN to send traffic to iAAA CNF"
call kubectl scale --namespace yaas --replicas=1 deployment/radiusclient

REM Redirect Grafana port
echo Redirecting Grafana port
start kubectl --namespace monitoring port-forward svc/grafana 3000
timeout /T 6 /nobreak 1>nul
start firefox.exe http://localhost:3000

echo.
echo kubectl scale --namespace yaas --replicas=2 statefulset/yaas-superserver
set /P dummy="Press RETURN to add an additional backend instance"
call kubectl scale --namespace yaas --replicas=2 statefulset/yaas-superserver

echo.
echo kubectl scale --namespace yaas --replicas=2 statefulset/yaas-db
set /P dummy="Press RETURN to add an additional Database instance"
call kubectl scale --namespace yaas --replicas=2 statefulset/yaas-db

echo.
set /P dummy="Press RETURN to kill a Database instance"
call kubectl delete --namespace yaas pod yaas-db-1 

echo.
start kubectl proxy

echo http://localhost:8001/api/v1/namespaces/yaas/pods
set /P dummy="Press RETURN to query the pods inventory"
REM start firefox.exe moz-extension://c5cda187-8898-4d30-94d8-c022bc4e9ed5/site/index.html
curl --noproxy localhost -s http://localhost:8001/api/v1/namespaces/yaas/pods|"d:\program files\jq\jq" ".items|.[].metadata.name"

echo.
echo http://localhost:8001/api/v1/namespaces/yaas/pods?watch / filter .object.metadata.name,.type
set /P dummy="Press RETURN to scale-in to 1 backend instance and watch the pods inventory"
call kubectl scale --namespace yaas --replicas=1 statefulset/yaas-superserver
curl --noproxy localhost -s http://localhost:8001/api/v1/namespaces/yaas/pods?watch|"d:\program files\jq\jq" ".object.metadata.name,.type"

