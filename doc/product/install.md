## Installation

Clone the repository and use sbt. The Universal packager is used, so that rpm or deb or zip artifacts
may be created with the appropriate command.

If incorporating external handlers (which will usually be the case), those must be made available in the
classpath (XXXXX how to do this).

## Smoke testing

Execute `testAll` in the `bin` directory.

This will launch several instances:

- A "superserver" instance, which acts as a Diameter and Radius server plus an ignite database server. It stores sessions with a prefix in the Acct-Sesion-Id and IP-Address
- A "superserver-mirror" instance, which will act as an ignite replica.
- A "server" instance, which receives the client requests, looks for clients in the ignite database, stores sessions and proxies the requests to the superserver.
- A "client" instance, which generates the requests and performs the tests.
