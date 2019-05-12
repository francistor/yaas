# Environment
aaaserver=$HOME/Yaas/target/universal/stage/bin/aaaserver
k8clientConfig=$HOME/Yaas/src/k8/conf/client

# Launch radius client
$aaaserver -Dinstance=radius -Dconfig.file=${k8clientConfig}/aaa-default.conf -Dlogback.configurationFile=${k8clientConfig}/logback-default.xml
# Launch diameter client
$aaaserver -Dinstance=diameter -Dconfig.file=${k8clientConfig}/aaa-default.conf -Dlogback.configurationFile=${k8clientConfig}/logback-default.xml


# Deploy
kubectl create -f Yaas/src/k8/descriptors/kagent.yml

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
kubectl exec -it kagent -- curl http://yaas-superserver-0.yaas-superserver-diameter:19000/diameter/peers | jq .
kubectl exec -it kagent -- curl http://yaas-superserver-1.yaas-superserver-diameter:19000/diameter/peers | jq .

# Change log level
kubectl exec -it kagent -- curl http://yaas-server-0.yaas-server:19000/logLevel/set?loggerName=yaas&level=DEBUG
