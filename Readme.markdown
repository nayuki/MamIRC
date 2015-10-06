MamIRC, the headless IRC client
===============================

MamIRC is a set of programs that implements a basic IRC client. The backend is written in Java, and the frontend is a web interface (HTML+CSS+JS).

The MamIRC client emphasizes on high uptime, hot upgradability, and accurate logging.


## Architecture

      +------------+                                                                  +---------------------+
      | IRC server | <-                                                            -> | Desktop web browser |
      +------------+   \                                                          /   +---------------------+
                        \     +------------------+      +------------------+     /                           
                         +--> | MamIRC connector | <--> | MamIRC processor | <--+                            
                        /     +------------------+      +------------------+     \                           
      +------------+   /                      |           ^                       \   +--------------------+ 
      | IRC server | <-                       v           |                        -> | Mobile web browser | 
      +------------+                       +-----------------+                        +--------------------+ 
                                           | SQLite database |                                               
                                           +-----------------+                                               

Notes:

* The connector and processor must run on the same machine. They communicate over an unencrypted local socket, and they must both have access to the same database file.
* The connector holds the network connections to the remote IRC servers. The connector is designed for high uptime and should not be restarted frequently. Whenever it is restarted, the user will lose all current connections to IRC servers, and other users on the IRC network will see this user quit and rejoin.
* The connector communicates with either zero or one processor. Attaching a new processor will cause the connector to detach the previously processor.
* The processor can be restarted anytime, without affecting the connections to the IRC servers.
* The processor can crash or not be attached, yet the connector will still handle IRC PINGs to ensure that the user does not suffer a forced quit due to a ping timeout.


## Setup

Operating system supported: Windows, Linux, or OS X. Must be x86 or x86-64, not ARM, etc.

Download these Java libraries:

* Nayuki's JSON library: http://www.nayuki.io/page/json-library-java
* almworks sqlite4java: https://bitbucket.org/almworks/sqlite4java

Edit the configuration files connector.ini and processor.ini (which are in JSON format), and launch the connector and processor with appropriate arguments.


## MamIRC connector

The connector is a headless Java program that performs these things:

* Holds zero or more active connections to remote IRC servers.
* Has either no processor attached, or accepts a connection from one processor.
* Whenever a processor is attached, the connector tells it the set of current connections and sequence numbers.
* Logs every "event" to a database file, with a connection number, sequence number, timestamp, type, and payload. An event is either a line received, a line sent, or a connection state change.
* If a processor is currently attached, the connector relays each new event to the processor.

The connector has almost no knowledge of the IRC protocol, except the fact that it is text-based and line-oriented (rather than binary), and can parse and respond to PINGs. Because of this, the connector is fairly feature-complete and expected to be stable.


### Database format

The connector logs all events to a database file, which has a single table:

    CREATE TABLE events (
    	connectionId  INTEGER,
    	sequence      INTEGER,
    	timestamp     INTEGER NOT NULL,
    	type          INTEGER NOT NULL,
    	data          BLOB NOT NULL,
    	PRIMARY KEY(connectionId,sequence)
    );

Extra notes and semantics in addition to the schema:

* `connectionId` starts at 0 and increments (with no gaps) for each connection attempt. It should fit in a signed int32 for convenience in Java. (Negative values are invalid.)
* `sequence` starts at 0 for each `connectionId`, and increments (with no gaps) for each event in the connection. It should fit in a signed int32. (Negative values are invalid.)
* `timestamp` is in milliseconds based on the Unix epoch. It should fit in a signed int64. Because computer clocks are generally unreliable, it is acceptable for timestamps to go backwards, etc. Treat this value as a best-effort piece of information.
* Events are ordered only within connections; they are not ordered between connections. Ordering within a connection is important for algorithms that track state changes, whereas ordering between connections is only of interest to human readers. So ordering between connections and with the external world can only be done through timestamps.
* `type` is 0 for a connection state change, 1 for a line received, or 2 for a line sent. (Other values are invalid.)
* `data` is a byte array of length at least 0. The interpretation depends on the type, but informally speaking it is usually a UTF-8 string.
* If `type` is 0, then `data` must be a UTF-8 string in one of four possible formats: "connect \<hostname> \<port> \<ssl/nossl> \<arbitrary metadata string>" (with `sequence` = 0), "opened \<IPv4/IPv6 address string>" (with `sequence` = 1), "disconnect" (which must come after "connect"), or "closed" (which must come after "connect", and after "disconnect" if any).
* If `type` is 1 or 2, then `data` is the payload line (respectively) received from or sent to the remote host over the outbound socket connection. It must not contain '\0', '\n', or '\r'. The string is not necessarily UTF-8, since it's allowable for the IRC protocol to use other character encodings such as ISO 8859-1, Shift JIS, EUC, etc. The connector faithfully preserves the raw bytes from the stream (without interpreting it as UTF-8) and lets the processor decide how to handle the character encoding of the text.


## MamIRC processor

The processor is another headless Java program, and is the one that does most of the heavy lifting:

* Connects to IRC servers based on the user's configuration file.
* Reconnects to IRC servers upon connection loss.
* Tracks the session state for each IRC connection (nickname, channels, channel members, etc.).
* Responds to various incoming events with appropriate responses.
* Hosts a web server and query API for web clients to view data.

The processor currently isn't very stable, and is not feature-complete with respect to the common features used in IRC. Luckily, the connector architecture allows the processor to crash, be restarted, and be upgraded without affecting the user's connections.


## SSL considerations

The MamIRC processor only implements an HTTP, not HTTPS web server. If the user runs the MamIRC Java daemons and the web browser on the same machine, there is no problem.

But the intended usage of MamIRC is to run the daemons on a remote, always-on server, and to access the web UI on a desktop or mobile browser. Therefore, encryption is essentially mandatory - to prevent eavesdropping, impersonation, and mischief.

One way to set up SSL is to use [stunnel](https://www.stunnel.org/index.html) on the server side to wrap an HTTPS session over a local HTTP session. You also need to generate your own SSL private key and get a signed certificate or make a self-signed certificate.

Another way is to open an SSH tunnel from your client machine to the server, and make your browser connect to the web server through the SSH tunnel.


## MamIRC web UI

The web UI is a couple of files served by the MamIRC processor. The web page loads data or sends messages by making XMLHttpRequests to the processor's web server. Updates are sent by long polling.

----

Copyright © Project Nayuki. All rights reserved. No warranty.  
Author's home page: http://www.nayuki.io/
