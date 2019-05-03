# Peer status

# Server
kubectl exec -it busybox -- wget -q -O- http://yaas-server-0.yaas-server:19000/diameter/peers | jq .
kubectl exec -it busybox -- wget -q -O- http://yaas-server-1.yaas-server:19000/diameter/peers | jq .

# Superserver
kubectl exec -it busybox -- wget -q -O- http://yaas-superserver-0.yaas-superserver-diameter:19000/diameter/peers | jq .
kubectl exec -it busybox -- wget -q -O- http://yaas-superserver-1.yaas-superserver-diameter:19000/diameter/peers | jq .