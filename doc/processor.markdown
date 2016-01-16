MamIRC Processor
================

The Processor is another headless Java program, and most of the MamIRC backend functionality and complexity is concentrated here. This program:

* Attaches to a MamIRC Connector to receive current connections and new events; also reads from Connector's database file to catch up to all events that occurred in current connections.

* Executes logic to connect to IRC servers, authenticate with NickServ, and join channels based on the user's configuration file.

* Automatically reconnects to IRC servers upon connection loss, with exponential backoff.

* Tracks and updates the session state for each IRC connection - data such as nickname, channels, channel members, etc.

* Holds numerous "windows" of chat message history in memory, to be served to web clients.

* Hosts a web server to serve static files (web UI code) and provide a query API (to view/send IRC messages).

Caveats of the Processor implementation:

* Only implements the basic commands for chatting on IRC channels; does not implement things like modes, CTCP, DCC, various commands.

* Not as stable as the Connector. It might unexpectedly run out of memory, hang, or crash on various inputs.

* Not hardened against deliberate attacks from IRC servers or web clients. 

Fortunately, the MamIRC architecture is forgiving toward a less-than-perfect Processor. As long as the Connector stays up and running, the Processor can crash, be upgraded, be restarted, or even not be running - without affecting the user's IRC connections. Generally speaking, other IRC users cannot see how the software sitting behind the MamIRC Connector is restarted or manipulated.


State data
----------

The Processor holds a lot of state data and updates it frequently. Some parts of the state control the operation and behavior of the software. Other parts are data that is shown to the end user, such as IRC messages. The state can be broadly classified into these groups:

* Per-connection data: This exists for every active connection (but not past connections). The data includes items such as the current nickname, whether the user has completed the initial IRC connection registration, list of current joined channels, the topic per channel, and the list of user names per channel. Whenever any of this connection-related data is created, updated, or destroyed, an update is posted to the list of updates for the web UI to pick up.

* Per-window data: Each window is a list of message/event lines, with new lines appended at the end. These windows exist independently of connections; a window does not need to correspond to a current channel in a current connection. Updates are generated whenever a new line is appended, the read mark position is moved, lines are cleared, a window is closed, or a window is explicitly opened. Note that a window is implicitly opened on the first append update that arrives.

* Miscellaneous data: Various items such as the next update ID, timeouts for connection attempts, mappings between connection IDs and profile names, et cetera. Most of this data is internal to the Processor and not relevant to the web UI.


HTTP API
--------

<dl>
<dt>/ (GET, POST)</dt>
<dd><p>The root path is a dynamic page that either shows the login screen or main web UI screen, depending on the password cookie. GET is used to retrieve the page, and POST is used to submit the login form.</p></dd>
<dt>/&lt;name of static file> (GET)</dt>
<dd>
<p>Each file in the "web" directory (e.g. JavaScript, CSS, images) maps to a path. They are served with caching enabled.</p>
</dd>
<dt>/get-time.json (POST)</dt>
<dd><p>Returns the MamIRC processor's current time, in milliseconds since the Unix epoch, as a single JSON integer (e.g. <code>1449447299467</code>). This request is separate from get-state.json because get-state might return a large amount of data, which could take a long time to transfer and skew the timestamp. This request lets the web client detect a clock mismatch with the MamIRC backend.</p></dd>
<dt>/get-state.json (POST)</dt>
<dd>
<p>Reads a JSON request containing parameters, and returns an enormous JSON object that describes the state of this user's IRC sessions hosted on MamIRC. Must use POST method, because GET has unpredictable caching. An example request is <code>{maxMessagesPerWindow: 3000}</code>. An example response (with annotations preceded by <code>#</code>):</p>
<pre>{
    connections: {  # About a kilobyte of data
        "FoobarNet": {
            currentNickname: "Nayuki",
            channels: {
                "#chaser": {
                    members: ["Zach", "Alex", "Nayuki", "Brenda"],
                    topic: "Lorem ipsum today"},
                "#delta": {
                    members: ["Nayuki", "0v3rfLow"],
                    topic: null}}},
        "Acme IRC": {
            currentNickname: "Nayuki|test",
            channels: {}}},
    
    windows: [  # Data size can grow to hundreds of kilobytes
        ["FoobarNet", "#chaser", {
            lines: [
                ["APPEND", "FoobarNet", "#chaser", 0, 11, 1449448151, "Brenda", "so this is it."],
                ["APPEND", "FoobarNet", "#chaser", 1, 43, -1, "Alex", "what is it? Nayuki?"],
                ["APPEND", "FoobarNet", "#chaser", 2, 27, 2, "Nayuki", "Um..."],
                ["APPEND", "FoobarNet", "#chaser", 3, 11, 5, "Alex", "then it's decided!"]],
            markedReadUntil: 2}],
        ["FoobarNet", "#delta", {
            # ......
        }],
        ["Acme IRC", "Carol", {
            # ......
        }],
    
    flagsConstants: {
        NAMES: 7,
        PRIVMSG: 11,
        NICKFLAG: 32,
        NOTICE: 9,
        TYPE_MASK: 15,
        # ......
    },
    
    nextUpdateId: 123,
    initialWindow: ["FoobarNet", "#delta"],
    csrfToken: "sq2ZwsrEHOE37flI"
}</pre>
</dd>
<dt>/get-updates.json (POST)</dt>
<dd>
<p>Given some request parameters in JSON, this returns a JSON array of updates, which are state changes or window message lines. This request uses long-polling and either returns as soon as an update is available or when the given timeout expires (returning a zero-length array). As a rare case, <code>null</code> is returned if the next update ID is ahead of the Processor's next update ID or too far behind (because the Processor only keeps a recent history of updates). An example request is <code>{nextUpdateId:123, maxWait:60000}</code>. An example response:</p>
<pre>{
    updates: [
        ["APPEND", "Acme IRC", "", 100, 13, 1449521502299, "001", "Welcome to Acme IRC network!"],
        ["CLOSEWIN", "FoobarNet", "Eric"],
        ["CLEARLINES", "FoobarNet", "#chaser", 52],
        ["MYNICK", "Acme IRC", "Nayuki|test2"]],
    nextUpdateId: 126
}</pre>
<p>The list of all possible updates is not explicitly documented or explained, but the information can be gathered from <a href="https://github.com/nayuki/MamIRC/blob/master/src/io/nayuki/mamirc/processor/MamircProcessor.java">MamircProcessor.java</a> based on calls to <code>addUpdate()</code>.</p>
</dd>
<dt>/do-actions.json (POST)</dt>
<dd>
<p>Sends a JSON object with list of actions for the Processor to perform (such as send line, open window, etc.). The response is the JSON string "OK". An example request:</p>
<pre>{
    payload: [
        ["send-line", "FoobarNet", "PRIVMSG #alpha :Hello world"],
        ["send-line", "Acme IRC", "JOIN #bravo"],
        ["mark-read", "Acme IRC", "Charlie", 326],
        ["close-window", "FoobarNet", "#delta"]],
    csrfToken: "sq2ZwsrEHOE37flI",
    nextUpdateId: 126
}</pre>
<p>The list of all possible actions can be inferred from the code in <a href="https://github.com/nayuki/MamIRC/blob/master/src/io/nayuki/mamirc/processor/MessageHttpServer.java">MessageHttpServer.java</a>, in the handler for the path "/do-actions.json".</p>
</dd>
</dl>

All POST requests require the correct password to be presented in the cookie parameter named "password". Additionally, do-actions.json requires a CSRF token to be sent.

Note: As an analogy to video compression, the data received from get-state.json is like a key frame, and data from get-updates.json is like a delta frame that describes how to change the current state.


Project links
-------------

* Home page: [http://www.nayuki.io/page/mamirc-the-headless-irc-client](http://www.nayuki.io/page/mamirc-the-headless-irc-client)

* GitHub repository: [https://github.com/nayuki/MamIRC](https://github.com/nayuki/MamIRC)

* Online documentation: [https://github.com/nayuki/MamIRC/tree/master/doc](https://github.com/nayuki/MamIRC/tree/master/doc)

Copyright © [Project Nayuki](http://www.nayuki.io/)
