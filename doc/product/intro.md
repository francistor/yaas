## What is Yaas?

Yaas is a Diameter an Radius Engine, capable of acting as a server or client.

Diameter and Radius are protocols used by telcos for signalling, that is, exchanging control
information (not user traffic) between network nodes. Both of them offer high
performance due to its asyncronous nature. A single connection (there is even no such a thing
as "connection" in the case of Radius) may multiplex requests and responses comming out or order,
avoiding wait times. This multiplex feature over the same connection is implemented in http2, and 
probably Diameter and Radius will become slowly out of favor when http2 gains traction in telcos, as is
already happening in 5G.

Yaas is built in scala and Akka.

Yaas starts up as a process with multiple "Handlers", each one being an Akka Actor, which may be
registered to receive certain types of Diameter/Radius messages and also are capabe to send Diameter/Radius
messages. Handlers inherit from the "yaas.server.MessageHandler", but may include any code and
of course thus perform any action, such as connecting to a database, invoke REST services or
whatever.

The advantage of this approach to build the logic internal to Diameter/Radius servers is that the
developer has the same freedom as in any programming language, and using a strongly typed one such
as scala. The disadvantage is that this may be too much freedom to be practical for very simple
algorigthms. Nevertheless, the author has never seen a simple Radius/Diameter algorithm, except
during the first day of development (people tend to make things complicated), and in the end,
the result in Yaas is typically much more compact, readable and maintainable than algorithms built
on Nokia TAL, pre-post-blah hooks in Radiator, etc., not to mention performance.

Yaas makes use of embedded Apache Ignite as a database for storing active sessions, IP address
assginments and, incidentally, for storing client data for internal tests.

