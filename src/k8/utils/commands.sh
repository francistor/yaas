# Environment
aaaserver=$HOME/Yaas/target/universal/stage/bin/aaaserver
k8clientConfig=$HOME/Yaas/src/k8/conf/client

# Deploy
kubectl create -f Yaas/src/k8/descriptors/kagent.yml

# Reload servers
kubectl scale --replicas=0 statefulSet/yaas-server
kubectl scale --replicas=0 statefulSet/yaas-superserver
kubectl scale --replicas=2 statefulSet/yaas-server
kubectl scale --replicas=2 statefulSet/yaas-superserver

# Peer status

# Server
kubectl exec -it kagent -- wget -q -O- http://yaas-server-0.yaas-server:19000/diameter/peers | jq .
kubectl exec -it kagent -- wget -q -O- http://yaas-server-1.yaas-server:19000/diameter/peers | jq .

# Superserver
kubectl exec -it kagent -- wget -q -O- http://yaas-superserver-0.yaas-superserver-diameter:19000/diameter/peers | jq .
kubectl exec -it kagent -- wget -q -O- http://yaas-superserver-1.yaas-superserver-diameter:19000/diameter/peers | jq .

# Launch radius client
$aaaserver -Dinstance=radius -Dconfig.file=${k8clientConfig}/aaa-default.conf -Dlogback.configurationFile=${k8clientConfig}/logback-default.xml

