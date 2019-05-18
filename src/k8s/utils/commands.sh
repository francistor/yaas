# Environment
aaaserver=$HOME/Yaas/target/universal/stage/bin/aaaserver
k8sClientConfig=$HOME/Yaas/src/k8s/conf/client

# Launch radius client
$aaaserver -Dinstance=radius -Dconfig.file=${k8sClientConfig}/aaa-default.conf -Dlogback.configurationFile=${k8sClientConfig}/logback-default.xml
# Launch diameter client
$aaaserver -Dinstance=diameter -Dconfig.file=${k8sClientConfig}/aaa-default.conf -Dlogback.configurationFile=${k8sClientConfig}/logback-default.xml


# Deploy
kubectl create -f Yaas/src/k8s/descriptors/kagent.yml

# Reload servers
kubectl scale --replicas=0 statefulSet/yaas-server
kubectl scale --replicas=0 statefulSet/yaas-superserver
kubectl scale --replicas=2 statefulSet/yaas-server
kubectl scale --replicas=2 statefulSet/yaas-superserver

# Peer status

# Server
kubectl exec -it kagent -- curl http://yaas-server-0.yaas-server:19000/diameter/peers | jq .
kubectl exec -it kagent -- curl http://yaas-server-1.yaas-server:19000/diameter/peers | jq .

# Superserver
kubectl exec -it kagent -- curl http://yaas-superserver-0.yaas-superserver:19000/diameter/peers | jq .
kubectl exec -it kagent -- curl http://yaas-superserver-1.yaas-superserver:19000/diameter/peers | jq .

# Change log level
kubectl exec -it kagent -- curl http://yaas-server-0.yaas-server:19000/config/setLogLevel?loggerName=yaas&level=DEBUG

