MamIRC Connector
================

The Connector is a headless Java program that performs these tasks:

* Holds zero or more active connections to IRC servers.

* Logs every interaction with an IRC server as an event in a database file. An event is either a line received, line sent, or connection state change.

* Accepts connections from Processors, checks the authentication, and tells them the set of current connections and sequence numbers.

* Has either zero or one Processor attached. When a Processor is present, every new event is forwarded to it.

* Responds to PINGs from IRC servers. (This allows the Processor to crash or hang, without losing the IRC connection.)

* Actively detects broken TCP connections by sending blank lines every minute.

The Connector has almost no knowledge of the IRC protocol - except that it treats the protocol as text-based and line-oriented (rather than binary) and that it handles PINGs. Because of this simplicity, the Connector is fairly feature-complete and expected to be quite stable.


Database format
---------------

The Connector logs all events to a simple SQLite database file, which has a single table:

    CREATE TABLE events (
        connectionId  INTEGER,
        sequence      INTEGER,
        timestamp     INTEGER NOT NULL,
        type          INTEGER NOT NULL,
        data          BLOB NOT NULL,
        PRIMARY KEY(connectionId,sequence)
    );

In addition to the schema, here are more notes and semantics about the data format:

* `connectionId` starts at 0 and increments (with no gaps) for each connection attempt. It should fit in a signed int32 for convenience in Java, and negative values are invalid.

* `sequence` starts at 0 for each connectionId, and increments (with no gaps) for each event in the connection. It should fit in a signed int32, and negative values are invalid.

* `timestamp` is in milliseconds based on the Unix epoch. It should fit in a signed int64. Because computer clocks are generally unreliable, it is acceptable for timestamps to go backwards, jump randomly, etc. Treat this value as a best-effort piece of information, and never trust it to determine which event came before another.

* Events are ordered only within each connection; they are not ordered between connections. Ordering within a connection is important for algorithms that track state changes, whereas ordering between connections is only of interest to human readers. So ordering between connections and with the external world can only be done through timestamps (unfortunately).

* `type` is 0 for a connection state change, 1 for a line received (IRC server to MamIRC Connector), or 2 for a line sent (MamIRC Connector to IRC server); other values are invalid.

* `data` is a byte array of length at least 0. The interpretation depends on the type, but informally speaking it is usually a UTF-8 string.

* If `type` is 0, then `data` must be a UTF-8 string in one of four possible formats:

   0. "connect &lt;hostname> &lt;port> &lt;ssl/nossl> &lt;metadata>", where metadata is zero or more characters, possibly having spaces, and not containing '\0'; sequence must be 0.
   0. "opened &lt;IPv4/IPv6 address string>"; sequence must be 1.
   0. "disconnect"; if this event exists then it must come after "connect".
   0. "closed"; if this event exists then it must come after "connect", and after "disconnect" (if any).

* Note that not necessarily every connection in the database will have a "closed" event, because the Connector could abruptly terminate before the connection is cleanly closed and logged. Also, no event can have a higher sequence number than a "closed" event, since it makes no sense to send or receive data after a connection is closed.

* If `type` is 1 or 2, then `data` is the payload line (respectively) received from or sent to the remote host over the outbound socket connection. `data` is zero or more bytes long, and must not contain '\0', '\n', or '\r'. The string is not necessarily UTF-8, since it's allowable for the IRC protocol to use other character encodings such as ISO 8859-1, Shift JIS, EUC, etc. The Connector faithfully preserves the raw bytes from the stream (without interpreting it as UTF-8) and lets the Processor decide how to handle the character encoding of the text.

* If the database is manipulated with an external tool, it is okay to leave gaps in `connectionId` values. Whenever the Connector is restarted, it finds the maximum `connectionId` in the database, and uses this value plus one as the next `connectionId`. (It will not reuse a lower ID in a gap.)

* If the database is manipulated with an external tool while a Connector is running, it is okay to manipulate events on any `connectionId` that is not a current active connection. It is not okay to manipulate events on active `connectionId` values because if the Processor is restarted, it learns of the current IDs and needs to read the database to get all the events that happened in these current connections.

* Beware of concurrent access to a MamIRC database. Only one Connector instance can use a particular database file at any given time; it is wrong to run two or more Connectors on the same database file because it will cause crashes and data corruption. Also when using an external program to read/write a database currently used by a MamIRC Connector, be sure to avoid locking the database for more than ~10 seconds, or else the Connector will exceed the maximum write timeout, and will terminate itself (along with all your IRC connections).


Connector-to-Processor protocol
-------------------------------

The Connector and Processor communicate with each other over a single socket, using a line-oriented text protocol. In a sense, the Connector multiplexes all its external connections into one internal connection.

* A line is a finite sequence of bytes, of length at least 0, that does not contain any NUL, CR, or LF characters. It does not need to conform to the UTF-8 character encoding.

* Both sides of the connection must use universal newline parsing (accepting LF, CR+LF, and CR).

* No action is allowed to be taken on the prefix of an incomplete line. For example, it's unacceptable to see a prefix like "send 10 PRIVM" and start relaying bytes to the IRC connection. The newline character/sequence must be seen before the line is handled. Also, an incomplete line at the end of stream is malformed and must be discarded.

* The Connector interprets the ASCII command at the beginning of each line, but thereafter it handles arbitrary 8-bit data verbatim (subject to the NUL/CR/LF restriction). This allows the Connector to support a variety of character encodings (excluding poorly behaved encodings like UTF-16 which generates NUL).


### Example session

For those of you curious about how to implement a Processor, here is what a typical connection looks like from the perspective of the Processor. Annotations are preceded by `#`, and no blank lines are in the actual protocol.

    # Initial handshake
    <-- MyPassword123         # Send password and newline.
    <-- attach                # Send initial action.
    --> active-connections    # Static string.
    --> 1 2308                # Connection ID = 1, next sequence = 2308.
    --> 7 459                 # Connection ID = 7, next sequence = 459.
    --> end-list              # Static string.
    --> live-events           # Static string.
    # From this point on, every line is an event with the format:
    # connectionId, sequence, timestamp, type, data.
    
    # Receive some lines from an IRC connection.
    --> 1 2308 1449104543985 1 :Alice PRIVMSG #London :Hello, world!
    --> 1 2309 1449104546870 1 :Alice PRIVMSG Bob :Can we talk?
    
    # Send a line; echoed back with sequence and timestamp.
    <-- send 1 PRIVMSG Alice :Yes I'm here
    --> 1 2310 1449104552109 2 PRIVMSG Alice :Yes I'm here
    
    # Request to open new connection: Hostname, port, SSL, profile name.
    <-- connect irc.foobarqux.com 6697 true FBQ IRC Network
    # Connection status change, new connectionId = 8.
    --> 8 0 1449105037216 0 connect irc.foobarbaz.com 6697 true FBQ IRC Network
    --> 8 1 1449105037245 0 opened 139.62.177.78
    <-- send 8 NICK John
    <-- send 8 USER John 0 * :John Smith

The set of commands that a Connector can accept from a Processor is documented fully in [ProcessorReaderThread.java](../src/io/nayuki/mamirc/connector/ProcessorReaderThread.java). For curious developers out there, it is indeed possible to converse with a MamIRC Connector using raw telnet; it is a good way to learn and debug the protocol.


Project links
-------------

* Home page: [https://www.nayuki.io/page/mamirc-the-headless-irc-client](https://www.nayuki.io/page/mamirc-the-headless-irc-client)

* GitHub repository: [https://github.com/nayuki/MamIRC](https://github.com/nayuki/MamIRC)

* Online documentation: [https://github.com/nayuki/MamIRC/tree/master/doc](https://github.com/nayuki/MamIRC/tree/master/doc)

Copyright © [Project Nayuki](https://www.nayuki.io/)
