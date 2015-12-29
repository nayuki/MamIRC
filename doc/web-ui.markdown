MamIRC web UI
=============

The web user interface is how a human interacts with the MamIRC software. The web UI communicates with the MamIRC Processor's HTTP server - it downloads the UI code from there, receives IRC messages/data, and sends user messages/actions.


Login screen
------------

Enter your password in the text box to log in. (The password value is configured in backend-config.json.)

If you tick the "Optimize for mobile" checkbox, the user interface will act more suitably for devices with small screens, slower CPUs, and slower Internet connections. Currently, enabling this option reduces the number of messages loaded, and changes the message date format to display less information.


Main screen
-----------

<dl>
<dt>UI panes</dt>
<dd><p>The MamIRC screen is divided into the left sidebar and main pane. The left sidebar always shows the list of windows at the bottom of the pane. A window is either an IRC channel, private messaging session, or server messages. If the current window is a channel that you are currently joined in, then the left sidebar also shows the list of channel users, positioned above the list of windows. The main pane contains a large scrollable pane of IRC messages, and a slim input text box at the bottom.</p></dd>
<dt>Input text box</dt>
<dd><p>Type in here to send a message or an IRC slash-command. The text in the input box is normally colored black, but becomes blue for slash-commands, or red for an over-long line (~400 chars). Note that to send an ordinary message beginning with a slash, change it to a double slash. The text box accepts slash-commands such as "/msg Jane Text here", "/nick Bob", "/me slaps John with trout", "/join #channel", et cetera, implemented in <a href="../web/mamirc.js">mamirc.js</a> in <code>inputBoxModule handleLine()</code>.</p></dd>
<dt>Full Unicode text support</dt>
<dd>
<p>Arbitrary Unicode text can be sent and received. Feel free to use accented characters, symbols, Chinese ideographs, and even emoji. (Characters beyond the Basic Multilingual Plane are supported.)</p>
<p>The character encoding is currently hard-coded to UTF-8, but the Processor code can be changed to support other encodings. (Only the Processor is responsible for encoding/decoding strings; the Connector only deals with raw bytes and the web UI only deals with Unicode strings.)</p>
</dd>
<dt>Right-click menus</dt>
<dd>
<p>In the channel users list, right-click on a user name to open a context menu, containing a command to open a private messaging window with the user. In the messages pane, right-clicking on a nickname in the second column will open a menu with a PM command too.</p>
<p>Also in the messages pane, right-clicking on a line will open a menu with the commands "Quote text" (for message lines, not for status lines), "Mark read to here", and "Clear to here". "Quote text" puts the line's nickname and text into the input text box, in a format like this: "&lt;Alice> Hello world". "Mark read to here" will gray out the current line and all lines above, which can help you keep track of where to start reading next time. "Clear to here" removes the current line and all lines above, from both the web UI and the Processor. These lines will not be shown in the web UI again, although the data can still be laboriously retrieved from the database log file.</p>
</dd>
<dt>New messages count</dt>
<dd><p>When a new message arrives in a window (for a channel, private messaging, or server), a blue circled number will appear to the right of the window name, indicating the number of new unread messages. To clear this count, click on that window name (which will also switch to the window). The page title is in the format "(NewNum) Party - Profile - MamIRC", such as "(8) #news - Abcd Net - MamIRC", where NewNum is the total number of unread messages among all the windows.</p></dd>
<dt>Receiving nickflags</dt>
<dd><p>If you receive a message that contains your current nickname, you are nickflagged. This makes the message line be shown in a red background (instead of white), the new message count for the window becomes red (instead of blue), and a toast notification will pop up.</p></dd>
<dt>Toast notifications</dt>
<dd><p>If you are nickflagged in a message or you receive the first message that opens a private messaging window, then a toast notification is generated. Assuming that your web browser supports the <a href="https://developer.mozilla.org/en/docs/Web/API/notification">notification API</a>, the toast is visible even if your browser is switched to another tab or minimized. Clicking on the toast will show your web browser, switch to the MamIRC tab, and switch to the IRC window that generated the notification - a convenient way to jump into a conversation.</p></dd>
<dt>Muting windows</dt>
<dd><p>If you wish to not see the new message count for a window, right-click its entry in the window list and select "Mute window". This action clears the count for the selected window, causes new messages to not increment the count, and disables notifications from nickflagging. This is useful if you wish to prioritize some windows over others, or if a channel is too chatty.</p></dd>
<dt>Tab completion for nicknames</dt>
<dd><p>In a channel window, type the first few letters of a member's name in the text box and hit the Tab key to fill in the rest of the name. If multiple names match, hit Tab to cycle through them. For example, "a&lt;tab>" might become "Alice: " or "Anthony: ".</p></dd>
<dt>Nickname colorization</dt>
<dd><p>Each nickname is assigned a pseudo-random color based on the hash of the string. Note that your own nickname is colorized in the same way too - so your nickname color will look the same on another copy of MamIRC. (Some other IRC clients always color your own nickname in a fixed color instead of applying their colorization algorithm.)</p></dd>
<dt>Display of formatted text</dt>
<dd><p>MamIRC can display formatted and colored text, according to the <a href="http://en.wikichip.org/wiki/irc/colors">specification at WikiChip</a>. It supports bold, italic, underline, text color, background color, color reversal, and revert-to-plain. It only supports formatting on received message lines, and does not have keyboard or mouse commands to create and send out formatted text.</p></dd>
<dt>Clickable URL links</dt>
<dd><p>URLs in the text, in http:// and https:// formats, are turned into clickable links. When left-clicking a link, the target opens in a new tab that is immediately displayed. When middle-clicking a link, the target opens in a new tab in the background, keeping MamIRC still visible. In both cases, the browser does not navigate away from the MamIRC page.</p></dd>
<dt>Date headings</dt>
<dd><p>The messages pane inserts a small centered heading above the first message of each date. This can be helpful for skimming a large number of messages, as well watching the progression of days on low-traffic channels.</p></dd>
</dl>


Project links
-------------

* Home page: [http://www.nayuki.io/page/mamirc-the-headless-irc-client](http://www.nayuki.io/page/mamirc-the-headless-irc-client)

* GitHub repository: [https://github.com/nayuki/MamIRC](https://github.com/nayuki/MamIRC)

* Online documentation: [https://github.com/nayuki/MamIRC/tree/master/doc](https://github.com/nayuki/MamIRC/tree/master/doc)
