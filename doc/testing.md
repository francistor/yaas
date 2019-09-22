# Testing

## Default configuration

After building Yaas, the server may be started using the command `aaaserver`, *without parameters at all*, to launch an instance named "default" with a reference configuration, using the files stored internally as resources. It includes RadiusAuth and RadiusAcct handler that simply do proxy to an upstream server, and a Diameter Credit Control Application server that also forwards the requests to a Diameter peer. 

The upstream server may be configured according to the files in `src/test/3rd_party`, listening to 11812 and 11813 ports for Radius and 3867 for Diameter, and simple test cases launched using the test scripts under the `bin/test` directory.

## Multiserver test scenario

The off-the-shelf installation creates configuration for three instances which may be launched on a single machine to execute a somewhat complex testing scenario.

- `test-client` includes a handler that acts as a client for Radius, Diameter and IP address allocations Requests, as well as checking metrics.
- `test-server` includes a complex radius request handler, similar to what can be found in production and which may be used as an example for coding. The sessions database role is "client". It executes database queries for looking up clients and session storage using the ignite server that is part of the "super-server" instance. It looksup the client in file or in database depending on the realm in the request.
- `test-superserver` acts as a ISP proxy and Ignite database for sessions, IP address allocations and client information. It does accept / reject / drop the request depending on the user-name.

The full smoke test may be executed by launching the `testAll` script in the test directory under bin. The tests executed may be customized by editing the source code in `yaas.handlers.test.TestClientMain.scala`

## Kubernetes test scenario

The Kubernetes test scenario includes four instances, all of them (except "client") are StatefulSets:

- `client/radius` and `client/diameter`. The common configuration files are under the "client" directory, with two instances named "radius" and "diameter". This instance is meant to be launched outside Kubernetes, to execute the test cases.
- `server`. Contains handlers for Radius and Diameter which in general do proxy to superserver. It uses one generic service (Headless) as required for the statefulset instantiation, a service of type NodePort for exposing the Radius ports and two additional services for Diameter, also of type NodePort, for each one of the Diameter Nodes. 
- `superserver`. Contains handler for Radius and Diameter. It uses a Headless service for exposing the Diameter Port to the server instance. The Diameter client (server instance) connects to one instance or the other using the DNS name. For Radius, a regular service with a single IP address is used, and Kubernetes does the balancing.  As explained above, all requests with the same origin IP address and port are sent to the same server, and any new server instantiated an added to the service does not get additional load.
- `db`. Contains a Yaas instance that does not start a Radius or Diameter server, but only the Ignite database and the REST server for performing IPAM operations and looking up sessions. The "server" and "superserver" instances are Ignite clients only. A Headless service is employed for internal use by the yaas clients, and a NodePort for external queries.

To build the Docker images and publish them to Dockerhub, execute the script `docker-build-sh` in the home directory of Yaas.