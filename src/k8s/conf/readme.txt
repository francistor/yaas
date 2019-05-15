= Example Kubernetes deployment

== Database nodes
	Stateful set with two ignite-only nodes
	radius client, radius server, diameter server disabled
	
== Radius / Diameter Server
	Replicaset with two servers
	
== Radius / Diameter Superservers 
	Replicaset with two server
	
== Client to be launched outside Kubernetes

Each pod contains a git sidecar container which pulls the configuration from github