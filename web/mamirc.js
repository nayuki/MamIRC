/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * http://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */


/*---- Window module ----*/

// Holds data for windows and connections, and handles the rendering/display of window data.
const windowModule = new function() {
	/* Constants */
	// Document nodes
	const windowListElem          = elemId("window-list");
	const messageListElem         = elemId("message-list");
	const memberListHeadingElem = elemId("member-list-heading");
	const memberListElem          = elemId("member-list");
	const showMoreMessagesElem    = elemId("show-more-messages");
	const channelIndicatorText    = textNode("");
	const nicknameText = textNode("");
	// Miscellaneous
	const self = this;  // Private functions and closures must use 'self', whereas public functions can use 'self' or 'this' interchangeably
	const DAYS_OF_WEEK = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
	
	/* Variables */
	// These variables are null before networkModule.getState() returns successfully. Thereafter, most of them are non-null.
	
	// Type tuple<profile:string, party:string, concatenated:string> / null.
	// Is null if windowNames is null or zero-length, otherwise activeWindow[2] equals an entry in windowNames.
	this.activeWindow = null;
	
	// Type list<string> / null. Length 0 or more. Each element is of the form (profile+"\n"+party).
	// Elements can be in any order, and it determines the order rendered on screen.
	this.windowNames = null;
	
	// Type map<string->Window> / null. Each key is an entry in windowNames. The type of each Window
	// is object{lines:list<list<seq:integer, flags:integer, timestamp:integer, payload:string...>>,
	// markedReadUntil:integer, numNewMessages:integer, isNickflagged:boolean, isMuted:boolean}.
	// (See createBlankWindow() for an example of all the fields.)
	var windowData = null;
	
	// Type map<string->Connection> / null. Each key is a network profile name. The type of each Connection is
	// object{currentNickname:string, channels:map<string->Channel>}, where Channel is object{members:list<string>, topic:string/null}.
	var connectionData = null;
	
	// Type map<string->integer> / null. It is a collection of integer constants, defined
	// in the Java code to avoid duplication. Values are set by networkModule.getState().
	var Flags = null;
	
	// Type integer / null.
	var curWindowMaxMessages = null;
	
	// Type integer / null.
	var dateBoundaryOffsetMs = null;
	
	
	/* Initialization */
	elemId("nickname").appendChild(nicknameText);
	elemId("channel-indicator").appendChild(channelIndicatorText);
	init();
	
	
	/* Exported functions */
	
	// Called only by networkModule.getState(). inData is an elaborate object parsed from JSON text.
	// Types: inData is object, result is void.
	this.loadState = function(inData) {
		// Set simple fields
		connectionData = inData.connections;
		Flags = inData.flagsConstants;
		dateBoundaryOffsetMs = userConfiguration["date-boundary-offset-seconds"] * 1000;
		
		// Handle the windows
		this.windowNames = [];
		windowData = {};
		inData.windows.forEach(function(inWindow) {
			// 'inWindow' has type tuple<profile:string, party:string, state:Window>
			var windowName = inWindow[0] + "\n" + inWindow[1];
			if (self.windowNames.indexOf(windowName) != -1)
				throw "Duplicate window";
			self.windowNames.push(windowName);
			
			// Preprocess the window's lines
			var inState = inWindow[2];
			var prevTimestamp = 0;
			inState.lines.forEach(function(line) {
				prevTimestamp += line[2];  // Delta decoding
				line[2] = prevTimestamp * 1000;
			});
			var outState = createBlankWindow();
			for (var key in inState)
				outState[key] = inState[key];
			windowData[windowName] = outState;
		});
		this.activeWindow = null;
		this.windowNames.sort();
		
		// Update UI elements
		redrawWindowList();
		if (this.windowNames.length > 0) {
			var winName = inData.initialWindow;
			if (winName != null)
				winName = winName[0] + "\n" + winName[1];
			if (winName == null || this.windowNames.indexOf(winName) == -1)
				winName = this.windowNames[0];
			this.setActiveWindow(winName);
		}
	};
	
	
	// Changes activeWindow and redraws the user interface. 'name' must exist in the array windowNames.
	// Note that for efficiency, switching to the already active window does not re-render the table of lines.
	// Thus all other logic must update the active window's lines incrementally whenever new updates arrive.
	// Types: name is string, result is void.
	this.setActiveWindow = function(name) {
		// activeWindow may be null at the start of this method, but will be non-null afterward
		windowData[name].numNewMessages = 0;
		windowData[name].isNickflagged = false;
		if (this.activeWindow != null && this.activeWindow[2] == name) {
			redrawWindowList();
			return;
		}
		
		// Set state, refresh text, refresh window selection
		this.activeWindow = name.split("\n").concat(name);
		var profile = this.activeWindow[0];
		var party = this.activeWindow[1];
		nicknameText.data = (profile in connectionData) ? connectionData[profile].currentNickname : "";
		inputBoxModule.setEnabled((profile in connectionData) && (!utilsModule.isChannelName(party) || party in connectionData[profile].channels));
		redrawWindowList();
		redrawChannelMembers();
		channelIndicatorText.data = utilsModule.isChannelName(party) ? party : "";
		
		// Redraw all message lines in this window
		curWindowMaxMessages = 300;
		redrawMessagesTable();
		var messagesElem = elemId("messages");
		messagesElem.scrollTop = messagesElem.scrollHeight;
		
		// Tell the processor that this window was selected
		networkModule.setInitialWindowDelayed(profile, party, 10000);
	};
	
	
	// Called by networkModule.updateState(). inData is an elaborate object parsed from JSON text.
	// Types: inData is object, result is void.
	this.loadUpdates = function(inData) {
		const messagesElem = elemId("messages");
		const scrollPosition = messagesElem.scrollTop;
		const scrollToBottom = scrollPosition + messagesElem.clientHeight > messagesElem.scrollHeight - 30;
		var activeWindowUpdated = false;
		inData.updates.forEach(function(payload) {
			var type = payload[0];
			
			if (type == "APPEND") {
				var windowName = payload[1] + "\n" + payload[2];
				var newWindow = false;
				if (self.windowNames.indexOf(windowName) == -1) {
					self.windowNames.push(windowName);
					self.windowNames.sort();
					windowData[windowName] = createBlankWindow();
					redrawWindowList();
					newWindow = true;
				}
				var line = payload.slice(3);
				line[2] *= 1000;
				var lines = windowData[windowName].lines;
				lines.push(line);
				var numPrefixDel = Math.max(lines.length - maxMessagesPerWindow, 0);
				lines.splice(0, numPrefixDel);
				if (self.activeWindow != null && windowName == self.activeWindow[2]) {
					var msgRow = lineDataToTableRow(line);
					if (messageListElem.firstChild != null && lines.length >= 2 && areDatesDifferent(line[2], lines[lines.length - 2][2])) {
						var dateRow = dateToTableRow(line[2]);
						if (msgRow.classList.contains("unread"))
							dateRow.classList.add("unread");
						if (msgRow.classList.contains("read"))
							dateRow.classList.add("read");
						messageListElem.appendChild(dateRow);
					}
					messageListElem.appendChild(msgRow);
					activeWindowUpdated = true;
				}
				var subtype = line[1] & Flags.TYPE_MASK;
				if (subtype == Flags.PRIVMSG) {
					if (self.activeWindow != null && windowName == self.activeWindow[2] && (line[1] & Flags.OUTGOING) != 0) {
						windowData[windowName].numNewMessages = 0;
						windowData[windowName].isNickflagged = false;
					} else if (!windowData[windowName].isMuted) {
						windowData[windowName].numNewMessages++;
						if ((line[1] & Flags.NICKFLAG) != 0)
							windowData[windowName].isNickflagged = true;
					}
					redrawWindowList();
					if (!windowData[windowName].isMuted) {
						var notiftext = null;
						if (!utilsModule.isChannelName(payload[2]) && (newWindow || (line[1] & Flags.NICKFLAG) != 0)) {
							// New private messaging window popped open, or nickflagged in one
							notificationModule.notifyMessage(windowName, null, line[3], line[4]);
						} else if ((line[1] & Flags.NICKFLAG) != 0)
							notificationModule.notifyMessage(windowName, payload[2], line[3], line[4]);
					}
				} else if (subtype == Flags.JOIN || subtype == Flags.PART || subtype == Flags.QUIT || subtype == Flags.KICK || subtype == Flags.NICK) {
					var members = connectionData[payload[1]].channels[payload[2]].members;
					var name = line[3];
					if (subtype == Flags.JOIN && members.indexOf(name) == -1)
						members.push(name);
					else if (subtype == Flags.PART && members.indexOf(name) != -1)
						members.splice(members.indexOf(name), 1);
					else if ((subtype == Flags.QUIT || subtype == Flags.KICK) && members.indexOf(name) != -1)
						members.splice(members.indexOf(name), 1);
					else if (subtype == Flags.NICK) {
						if (members.indexOf(name) != -1)
							members.splice(members.indexOf(name), 1);
						if (members.indexOf(line[4]) == -1)
							members.push(line[4]);
					}
					if (self.activeWindow != null && windowName == self.activeWindow[2])
						redrawChannelMembers();
				} else if (subtype == Flags.TOPIC) {
					connectionData[payload[1]].channels[payload[2]].topic = line[4];
				} else if (subtype == Flags.INITNOTOPIC) {
					connectionData[payload[1]].channels[payload[2]].topic = null;
				} else if (subtype == Flags.INITTOPIC) {
					connectionData[payload[1]].channels[payload[2]].topic = line[3];
				} else if (subtype == Flags.NOTICE || subtype == Flags.SERVERREPLY) {
					if (!windowData[windowName].isMuted) {
						windowData[windowName].numNewMessages++;
						redrawWindowList();
					}
				} else if (subtype == Flags.NAMES) {
					connectionData[payload[1]].channels[payload[2]].members = line.slice(3);
					if (self.activeWindow != null && payload[1] == self.activeWindow[0] && payload[2] == self.activeWindow[1])
						redrawChannelMembers();
				} else if (subtype == Flags.DISCONNECTED && payload[2] == "") {
					delete connectionData[payload[1]];
				}
			} else if (type == "MYNICK") {
				var profile = payload[1];
				var name = payload[2];
				connectionData[profile].currentNickname = name;
				if (self.activeWindow != null && self.activeWindow[0] == profile) {
					nicknameText.data = name;
					activeWindowUpdated = true;
				}
			} else if (type == "JOINED") {
				connectionData[payload[1]].channels[payload[2]] = {
					members: [],
					topic: null,
				};
			} else if (type == "PARTED" || type == "KICKED") {
				delete connectionData[payload[1]].channels[payload[2]];
				if (self.activeWindow != null && self.activeWindow[0] == payload[1] && self.activeWindow[1] == payload[2]) {
					redrawChannelMembers();
					inputBoxModule.setEnabled(false);
				}
				if (type == "KICKED")
					notificationModule.notifyRaw(windowName, "You were kicked from " + payload[2] + " by " + payload[3] + ": " + payload[4]);
			} else if (type == "OPENWIN") {
				var windowName = payload[1] + "\n" + payload[2];
				var index = self.windowNames.indexOf(windowName);
				if (index == -1) {
					self.windowNames.push(windowName);
					self.windowNames.sort();
					windowData[windowName] = createBlankWindow();
					redrawWindowList();
					inputBoxModule.putText("");
					self.setActiveWindow(windowName);
				}
			} else if (type == "CLOSEWIN") {
				var windowName = payload[1] + "\n" + payload[2];
				var index = self.windowNames.indexOf(windowName);
				if (index != -1) {
					self.windowNames.splice(index, 1);
					delete windowData[windowName];
					redrawWindowList();
					if (self.activeWindow != null && windowName == self.activeWindow[2]) {
						inputBoxModule.clearText();
						if (self.windowNames.length > 0)
							self.setActiveWindow(self.windowNames[Math.min(index, self.windowNames.length - 1)]);
						else
							utilsModule.clearChildren(messageListElem);
					}
				}
			} else if (type == "MARKREAD") {
				var windowName = payload[1] + "\n" + payload[2];
				var seq = payload[3];
				windowData[windowName].markedReadUntil = seq;
				if (self.activeWindow != null && windowName == self.activeWindow[2]) {
					var lines = windowData[windowName].lines;
					var rows = messageListElem.children;
					for (var i = rows.length - 1, j = lines.length - 1; i >= 0; i--) {
						var row = rows[i];
						var lineseq;
						if (row.firstChild.colSpan == 1) {  // Ordinary message row
							lineseq = lines[j][0];
							j--;
						} else  // colSpan == 3
							lineseq = lines[j + 1][0];
						utilsModule.setClasslistItem(row, "read"  , lineseq <  seq);
						utilsModule.setClasslistItem(row, "unread", lineseq >= seq);
					}
					activeWindowUpdated = true;
				}
			} else if (type == "CLEARLINES") {
				var windowName = payload[1] + "\n" + payload[2];
				var seq = payload[3];
				var lines = windowData[windowName].lines;
				var i;
				for (i = 0; i < lines.length && lines[i][0] < seq; i++);
				lines.splice(0, i);
				if (self.activeWindow != null && windowName == self.activeWindow[2]) {
					var rows = messageListElem.children;
					i = lines.length - 1;
					var j;
					for (j = rows.length - 1; j >= 0; j--) {
						var row = rows[j];
						if (i < 0)
							messageListElem.removeChild(row);
						else if (row.firstChild.colSpan == 1)  // Ordinary message row
							i--;
					}
					utilsModule.setClasslistItem(showMoreMessagesElem, "hide", i < 0);
					activeWindowUpdated = true;
				}
			} else if (type == "CONNECTED") {
				connectionData[payload[1]] = {
					currentNickname: null,
					channels: {},
				};
			}
		});
		
		if (activeWindowUpdated) {
			var rows = messageListElem.children;
			for (var i = rows.length - 1, j = 0; i >= 0; i--) {
				if (j >= curWindowMaxMessages)
					messageListElem.removeChild(rows[i]);
				else if (rows[i].firstChild.colSpan == 1)  // Ordinary message row
					j++;
			}
			reflowMessagesTable();
			messagesElem.scrollTop = scrollToBottom ? messagesElem.scrollHeight : scrollPosition;
		}
	};
	
	
	// Either switches to an existing private messaging window of the given name on
	// the current profile, or sends a command to the server to open a new PM window.
	// Types: party is string, onerror is function()->void / null, result is void.
	this.openPrivateMessagingWindow = function(party, onerror) {
		var profile = this.activeWindow[0];
		var windowName = profile + "\n" + party;
		if (this.windowNames.indexOf(windowName) == -1)
			networkModule.sendAction([["open-window", profile, party]], onerror);
		else {
			this.setActiveWindow(windowName);
			inputBoxModule.putText("");
		}
	};
	
	
	// Returns a new list of channel member names for the given profile and channel,
	// or null if not currently connected to the profile or joined in the channel.
	// Types: profile is string, channel is string, result is list<string> / null.
	this.getChannelMembers = function(profile, channel) {
		if (!(profile in connectionData))
			return null;
		var data = connectionData[profile].channels;
		if (!(channel in data))
			return null;
		return data[channel].members.slice(0);  // Defensive copy
	};
	
	
	/* Private functions */
	
	// Performs module initialization. Types: result is void.
	function init() {
		document.documentElement.addEventListener("keydown", function(ev) {
			if (ev.keyCode == 38 && ev.ctrlKey) {  // Up arrow
				changeWindow(-1);
				ev.preventDefault();
			} else if (ev.keyCode == 40 && ev.ctrlKey) {  // Down arrow
				changeWindow(+1);
				ev.preventDefault();
			}
		});
		showMoreMessagesElem.querySelector("a").onclick = function() {
			if (self.activeWindow == null)
				return;
			var temp = Math.sqrt(curWindowMaxMessages / 300) + 0.5;
			temp = Math.round(temp * temp * 300);
			curWindowMaxMessages = Math.min(temp, 10000);
			redrawMessagesTable();
			return false;
		};
	}
	
	
	// Clears the window list HTML container element and rebuilds it from scratch based on the current states
	// of windowNames, windowData[windowName].newMessages, and activeWindow. Types: result is void.
	function redrawWindowList() {
		utilsModule.clearChildren(windowListElem);
		self.windowNames.forEach(function(windowName) {
			// windowName has type str, and is of the form (profile+"\n"+party)
			var parts = windowName.split("\n");
			var profile = parts[0];
			var party = parts[1];
			var window = windowData[windowName];
			
			// Create the anchor element
			var a = utilsModule.createElementWithText("a", party != "" ? party : profile);
			var n = window.numNewMessages;
			if (n > 0) {
				[" (", n.toString(), ")"].forEach(function(s) {
					a.appendChild(utilsModule.createElementWithText("span", s));
				});
			}
			utilsModule.setClasslistItem(a, "nickflag", window.isNickflagged);
			a.onclick = function() {
				self.setActiveWindow(windowName);
				return false;
			};
			a.oncontextmenu = function(ev) {
				var menuItems = [];
				if (window.isMuted)
					menuItems.push(["Unmute window", function() { window.isMuted = false; }]);
				else {
					menuItems.push(["Mute window", function() {
						var win = window;
						win.isMuted = true;
						win.numNewMessages = 0;
						win.isNickflagged = false;
						redrawWindowList();
					}]);
				}
				var closable = !(profile in connectionData) || (party != "" && !(party in connectionData[profile].channels));
				var func = function() { networkModule.sendAction([["close-window", profile, party]], null); };
				menuItems.push(["Close window", closable ? func : null]);
				if (utilsModule.isChannelName(party) && profile in connectionData) {
					var mode = party in connectionData[profile].channels;
					var func = function() {
						networkModule.sendAction([["send-line", profile, (mode ? "PART " : "JOIN ") + party]], null);
					};
					menuItems.push([(mode ? "Part" : "Join") + " channel", func]);
				}
				menuModule.openMenu(ev, menuItems);
			};
			
			var li = document.createElement("li");
			li.appendChild(a);
			if (party == "")
				li.className = "profile";
			windowListElem.appendChild(li);
		});
		refreshWindowSelection();
		
		var totalNewMsg = 0;
		for (var key in windowData)
			totalNewMsg += windowData[key].numNewMessages;
		var activeWin = self.activeWindow
		if (activeWin != null)
			document.title = (totalNewMsg > 0 ? "(" + totalNewMsg + ") " : "") + (activeWin[1] != "" ? activeWin[1] + " - " : "") + activeWin[0] + " - MamIRC";
	}
	
	
	// Refreshes the selection class of each window <li> element based on the states of windowNames and activeWindow.
	// This assumes that the list of HTML elements is already synchronized with windowNames. Types: result is void.
	function refreshWindowSelection() {
		if (self.activeWindow == null)
			return;
		var windowLis = windowListElem.getElementsByTagName("li");
		self.windowNames.forEach(function(name, i) {
			utilsModule.setClasslistItem(windowLis[i], "selected", name == self.activeWindow[2]);
		});
	}
	
	
	// Refreshes the channel members text element based on the states of
	// connectionData[profileName].channels[channelName].members and activeWindow.
	// Types: Result is void.
	function redrawChannelMembers() {
		utilsModule.clearChildren(memberListElem);
		var profile = self.activeWindow[0];
		var party = self.activeWindow[1];
		var show = profile in connectionData && party in connectionData[profile].channels;
		if (show) {
			var members = connectionData[profile].channels[party].members;
			members.sort(function(s, t) {  // Safe mutation; case-insensitive ordering
				return s.toLowerCase().localeCompare(t.toLowerCase());
			});
			members.forEach(function(name) {
				var li = utilsModule.createElementWithText("li", name);
				li.oncontextmenu = menuModule.makeOpener([["Open PM window", function() { self.openPrivateMessagingWindow(name, null); }]]);
				memberListElem.appendChild(li);
			});
		}
		utilsModule.setClasslistItem(memberListHeadingElem, "hide", !show);
	}
	
	
	// Clears and rerenders the entire table of messages for the current window. Types: result is void.
	function redrawMessagesTable() {
		utilsModule.clearChildren(messageListElem);
		var lines = windowData[self.activeWindow[2]].lines;
		for (var i = Math.max(lines.length - curWindowMaxMessages, 0), head = true; i < lines.length; i++, head = false) {
			// 'line' has type tuple<int seq, int timestamp, str line, int flags>
			var line = lines[i];
			var msgRow = lineDataToTableRow(line);
			if (!head && areDatesDifferent(line[2], lines[i - 1][2])) {
				var dateRow = dateToTableRow(line[2]);
				if (msgRow.classList.contains("unread"))
					dateRow.classList.add("unread");
				if (msgRow.classList.contains("read"))
					dateRow.classList.add("read");
				messageListElem.appendChild(dateRow);
			}
			messageListElem.appendChild(msgRow);
		}
		reflowMessagesTable();
		utilsModule.setClasslistItem(showMoreMessagesElem, "hide", lines.length <= curWindowMaxMessages);
	}
	
	
	// Calculates and apply column widths in the main table, and changes the table to the fixed layout. Types: result is void.
	function reflowMessagesTable() {
		var tableElem = messageListElem.parentNode;
		tableElem.style.tableLayout = "auto";
		if (messageListElem.children.length > 0) {
			var cols = messageListElem.firstChild.children;
			var widths = [cols[0].clientWidth, cols[1].clientWidth];
			tableElem.style.tableLayout = "fixed";
			cols[0].style.width = widths[0] + "px";
			cols[1].style.width = widths[1] + "px";
		}
	}
	
	
	// Converts a window line (which is a tuple of str/int) into a <tr> element for the main messages table.
	// The window line comes from windowData[windowName].lines[i] (which can be from loadState() or loadUpdates()).
	// This function returns valid data only when it is called on lines in the active window; it must not be used for off-screen windows.
	// Types: line is list<sequence:integer, flags:integer, timestamp:integer, payload:string...>, result is HTMLElement.
	function lineDataToTableRow(line) {
		// Input variables
		const sequence = line[0];
		const flags = line[1];
		const timestamp = line[2];
		const payload = line.slice(3);
		const type = flags & Flags.TYPE_MASK;
		
		// Output variables
		var who = "\u25CF";    // Type string
		var nameColor = null;  // Type string/null
		var lineElems = [];    // Type list<HTMLElement>
		var quoteText = null;  // Type string/null
		var tr = document.createElement("tr");
		
		// Take action depending on head of payload
		if (type == Flags.PRIVMSG) {
			who = payload[0];
			nameColor = nickColorModule.getNickColor(who);
			var s = payload[1];
			var mematch = formatTextModule.matchMeMessage(s);
			if (mematch != null)
				s = mematch[1];
			
			if ((flags & Flags.OUTGOING) != 0)
				tr.classList.add("outgoing");
			if ((flags & Flags.NICKFLAG) != 0)
				tr.classList.add("nickflag");
			quoteText = formatTextModule.fancyToPlainText(s.replace(/\t/g, " "));
			lineElems = formatTextModule.fancyTextToElems(s);
			if (mematch != null) {
				tr.classList.add("me-action");
				quoteText = "* " + who + " " + quoteText;
			} else {
				quoteText = "<" + who + "> " + quoteText;
			}
			
		} else if (type == Flags.NOTICE) {
			who = "(" + payload[0] + ")";
			lineElems = formatTextModule.fancyTextToElems(payload[1]);
		} else if (type == Flags.NICK) {
			lineElems.push(textNode(payload[0] + " changed their name to " + payload[1]));
			tr.classList.add("nick-change");
		} else if (type == Flags.JOIN) {
			who = "\u2192";  // Rightwards arrow
			lineElems.push(textNode(payload[0] + " joined the channel"));
			tr.classList.add("user-enter");
		} else if (type == Flags.PART) {
			who = "\u2190";  // Leftwards arrow
			lineElems.push(textNode(payload[0] + " left the channel"));
			tr.classList.add("user-exit");
		} else if (type == Flags.QUIT) {
			who = "\u2190";  // Leftwards arrow
			lineElems = formatTextModule.fancyTextToElems(payload[1]);
			lineElems.splice(0, 0, textNode(payload[0] + " has quit: "));
			tr.classList.add("user-exit");
		} else if (type == Flags.KICK) {
			who = "\u2190";  // Leftwards arrow
			lineElems = formatTextModule.fancyTextToElems(payload[2]);
			lineElems.splice(0, 0, textNode(payload[0] + " was kicked by " + payload[1] + ": "));
			tr.classList.add("user-exit");
		} else if (type == Flags.TOPIC) {
			lineElems = formatTextModule.fancyTextToElems(payload[1]);
			lineElems.splice(0, 0, textNode(payload[0] + " set the channel topic to: "));
		} else if (type == Flags.INITNOTOPIC) {
			lineElems.push(textNode("No channel topic is set"));
		} else if (type == Flags.INITTOPIC) {
			lineElems = formatTextModule.fancyTextToElems(payload[0]);
			lineElems.splice(0, 0, textNode("The channel topic is: "));
		} else if (type == Flags.SERVERREPLY) {
			lineElems = formatTextModule.fancyTextToElems(payload[1]);
		} else if (type == Flags.NAMES) {
			lineElems.push(textNode("Users in channel: " + payload.join(", ")));
			tr.classList.add("user-list");
		} else if (type == Flags.MODE) {
			lineElems.push(textNode(payload[0] + " set mode " + payload[1]));
			tr.classList.add("mode-change");
		} else if (type == Flags.CONNECTING) {
			var str = "Connecting to server at " + payload[0] + ", port " + payload[1] + ", " + (payload[2] ? "SSL" : "no SSL") + "...";
			lineElems.push(textNode(str));
		} else if (type == Flags.CONNECTED) {
			lineElems.push(textNode("Socket opened to IP address " + payload[0]));
		} else if (type == Flags.DISCONNECTED) {
			lineElems.push(textNode("Disconnected from server"));
		} else {
			who = "RAW";
			lineElems.push(textNode("flags=" + flags + " " + payload.join(" ")));
		}
		
		// Make timestamp cell
		var td = utilsModule.createElementWithText("td", formatDate(timestamp));
		tr.appendChild(td);
		
		// Make nickname cell
		td = utilsModule.createElementWithText("td", who);
		if (who != "\u25CF" && who != "\u2190" && who != "\u2192" && who != "RAW")
			td.oncontextmenu = menuModule.makeOpener([["Open PM window", function() { self.openPrivateMessagingWindow(who, null); }]]);
		if (nameColor != null)
			td.style.color = nameColor;
		tr.appendChild(td);
		
		// Make message cell and its sophisticated context menu
		td = document.createElement("td");
		lineElems.forEach(function(elem) {
			td.appendChild(elem);
		});
		var menuItems = [["Quote text", null]];
		if (quoteText != null) {
			menuItems[0][1] = function() {
				inputBoxModule.putText(quoteText);
			};
		}
		menuItems.push(["Mark read to here", function() {
			if (tr.classList.contains("read") && !confirm("Do you want to move mark upward?"))
				return;
			networkModule.sendAction([["mark-read", self.activeWindow[0], self.activeWindow[1], sequence + 1]], null);
		}]);
		menuItems.push(["Clear to here", function() {
			if (confirm("Do you want to clear text?"))
				networkModule.sendAction([["clear-lines", self.activeWindow[0], self.activeWindow[1], sequence + 1]], null);
		}]);
		td.oncontextmenu = menuModule.makeOpener(menuItems);
		tr.appendChild(td);
		
		// Finishing touches
		var isRead = sequence < windowData[self.activeWindow[2]].markedReadUntil;
		tr.classList.add(isRead ? "read" : "unread");
		return tr;
	}
	
	
	// Changes the active window index to this plus step, modulo the number of windows.
	// Types: step is integer, result is void.
	function changeWindow(step) {
		if (self.windowNames == null)
			return;
		var i = self.windowNames.indexOf(self.activeWindow[2]);
		var n = self.windowNames.length;
		i = ((i + step) % n + n) % n;
		self.setActiveWindow(self.windowNames[i]);
	}
	
	
	// Returns a new window object with fields set to initial values.
	// Types: result is Window. Pure function.
	function createBlankWindow() {
		return {
			lines: [],
			markedReadUntil: 0,
			numNewMessages: 0,
			isNickflagged: false,
			isMuted: false,
		};
	}
	
	// Given a timestamp in Unix milliseconds, this returns a new full row element for the messages table.
	// Types: timestamp is int, result is HTMLElement.
	function dateToTableRow(timestamp) {
		var tr = document.createElement("tr");
		var td = document.createElement("td");
		td.colSpan = 3;
		var d = new Date(timestamp - dateBoundaryOffsetMs);
		var text = d.getFullYear() + "\u2012" + utilsModule.twoDigits(d.getMonth() + 1) + "\u2012" + utilsModule.twoDigits(d.getDate()) + "\u2012" + DAYS_OF_WEEK[d.getDay()];
		var span = utilsModule.createElementWithText("span", text);
		td.appendChild(span);
		tr.appendChild(td);
		return tr;
	}
	
	// Tests whether the two given timestamps (in Unix milliseconds) fall on different dates.
	// Types: ts0 is integer, ts1 is integer, result is boolean.
	function areDatesDifferent(ts0, ts1) {
		var d0 = new Date(ts0 - dateBoundaryOffsetMs);
		var d1 = new Date(ts1 - dateBoundaryOffsetMs);
		return d0.getFullYear() != d1.getFullYear() || d0.getMonth() != d1.getMonth() || d0.getDate() != d1.getDate();
	}
	
	// Converts the given timestamp in Unix milliseconds to a string in the preferred format for lineDataToTableRow().
	// Types: timestamp is integer, result is string. Pure function.
	function formatDate(timestamp) {
		var d = new Date(timestamp);
		var two = utilsModule.twoDigits;
		if (!optimizeMobile) {
			return two(d.getDate()) + "-" + DAYS_OF_WEEK[d.getDay()] + " " +
				two(d.getHours()) + ":" + two(d.getMinutes()) + ":" + two(d.getSeconds());
		} else {
			return DAYS_OF_WEEK[d.getDay()] + " " + two(d.getHours()) + ":" + two(d.getMinutes());
		}
	}
};



/*---- Text formatting module ----*/

// Handles formatting codes and URLs in raw IRC message strings. Stateless module.
const formatTextModule = new function() {
	/* Constants */
	const DETECTION_REGEX = /[\u0002\u0003\u000F\u0016\u001D\u001F]|https?:\/\//;
	const FORMAT_CODE_REGEX = /^(.*?)(?:[\u0002\u000F\u0016\u001D\u001F]|\u0003(?:(\d{1,2})(?:,(\d{1,2}))?)?)/;
	const REMOVE_FORMATTING_REGEX = /[\u0002\u000F\u0016\u001D\u001F]|\u0003(?:\d{1,2}(?:,\d{1,2})?)?/g;
	const URL_REGEX0 = /^(|.*? )(https?:\/\/[^ ]+)/;    // Includes parentheses
	const URL_REGEX1 = /^(.*?\()(https?:\/\/[^ ()]+)/;  // Excludes parentheses
	const ME_ACTION_REGEX = /^\u0001ACTION (.*)\u0001$/;
	const TEXT_COLORS = [
		// The 16 mIRC colors: http://www.mirc.com/colors.html ; http://en.wikichip.org/wiki/irc/colors
		"#FFFFFF", "#000000", "#00007F", "#009300", "#FF0000", "#7F0000", "#9C009C", "#FC7F00",
		"#FFFF00", "#00FC00", "#009393", "#00FFFF", "#0000FC", "#FF00FF", "#7F7F7F", "#D2D2D2",
	];
	const DEFAULT_BACKGROUND = 0;  // An index in TEXT_COLORS
	const DEFAULT_FOREGROUND = 1;  // An index in TEXT_COLORS
	
	/* Exported functions */
	
	// Given a string with possible IRC formatting control codes and plain text URLs,
	// this returns an array of DOM nodes representing text with formatting and anchor links.
	// Types: str is string, result is list<HTMLElement/Text>. Pure function.
	this.fancyTextToElems = function(str) {
		// Take fast path if string contains no formatting or potential URLs
		if (!DETECTION_REGEX.test(str))
			return [textNode(str)];
		
		// Current formatting state
		var bold = false;
		var italic = false;
		var underline = false;
		var background = DEFAULT_BACKGROUND;  // An index in TEXT_COLORS
		var foreground = DEFAULT_FOREGROUND;  // An index in TEXT_COLORS
		
		// Process formatting commands and chunks of text
		var result = [];
		while (str != "") {
			var formatMatch = FORMAT_CODE_REGEX.exec(str);
			var strPartEnd = formatMatch != null ? formatMatch[1].length : str.length;
			if (strPartEnd > 0) {
				// Process text
				var chunk = str.substr(0, strPartEnd);
				var elems = [];
				while (chunk != "") {
					var urlMatch = URL_REGEX0.exec(chunk);
					if (urlMatch == null)
						urlMatch = URL_REGEX1.exec(chunk);
					var chunkPartEnd = urlMatch != null ? urlMatch[1].length : chunk.length;
					if (chunkPartEnd > 0)
						elems.push(textNode(chunk.substr(0, chunkPartEnd)));
					if (urlMatch == null)
						break;
					var a = utilsModule.createElementWithText("a", urlMatch[2]);
					a.href = urlMatch[2];
					a.target = "_blank";
					a.oncontextmenu = function(ev) { ev.stopPropagation(); };  // Show system context menu instead of custom menu
					elems.push(a);
					chunk = chunk.substring(urlMatch[0].length);
				}
				
				if (background != DEFAULT_BACKGROUND || foreground != DEFAULT_FOREGROUND) {
					var elem = document.createElement("span");
					if (background != DEFAULT_BACKGROUND)
						elem.style.backgroundColor = TEXT_COLORS[background];
					if (foreground != DEFAULT_FOREGROUND)
						elem.style.color = TEXT_COLORS[foreground];
					elems.forEach(function(e) {
						elem.appendChild(e);
					});
					elems = [elem];
				}
				var temp = [[bold, "b"], [italic, "i"], [underline, "u"]];
				temp.forEach(function(pair) {
					if (pair[0]) {
						var elem = document.createElement(pair[1]);
						elems.forEach(function(e) {
							elem.appendChild(e);
						});
						elems = [elem];
					}
				});
				elems.forEach(function(e) {
					result.push(e);
				});
			}
			if (formatMatch == null)
				break;
			
			// Update state based on format code
			switch (str.charCodeAt(strPartEnd)) {
				case 0x02:
					bold = !bold;
					break;
				case 0x1D:
					italic = !italic;
					break;
				case 0x1F:
					underline = !underline;
					break;
				case 0x16:  // Reverse
					var temp = foreground;
					foreground = background;
					background = temp;
					break;
				case 0x0F:  // Plain
					bold = false;
					italic = false;
					underline = false;
					background = DEFAULT_BACKGROUND;
					foreground = DEFAULT_FOREGROUND;
					break;
				case 0x03:  // Color
					var fore = formatMatch[2] != undefined ? parseInt(formatMatch[2], 10) : DEFAULT_FOREGROUND;
					var back = formatMatch[3] != undefined ? parseInt(formatMatch[3], 10) : DEFAULT_BACKGROUND;
					if (fore < TEXT_COLORS.length) foreground = fore;
					if (back < TEXT_COLORS.length) background = back;
					break;
				default:
					throw "Assertion error";
			}
			str = str.substring(formatMatch[0].length);
		}
		
		// Epilog
		if (result.length == 0)  // Prevent having an empty <td> to avoid style/display problems
			result.push(textNode(""));
		return result;
	}
	
	// Attempts to match the given string agaist the '/me' action regex, returning
	// an array of capture group strings if successful or null if there is no match.
	// Types: str is string, result is (list<string> with extra properties due to RegExp.exec()) / null. Pure function.
	this.matchMeMessage = function(str) {
		return ME_ACTION_REGEX.exec(str);
	};
	
	// Returns a new string representing the given string with all IRC formatting codes removed.
	// Types: str is string, result is string. Pure function.
	this.fancyToPlainText = function(str) {
		return str.replace(REMOVE_FORMATTING_REGEX, "");
	};
};



/*---- Input text box module ----*/

// Handles the input text box - command parsing, tab completion, and text setting.
const inputBoxModule = new function() {
	/* Constants */
	const inputBoxElem = elemId("input-box");
	// The default of 400 is a safe number to use, because an IRC protocol line
	// is generally limited to 512 bytes, including prefix and parameters and newline
	const maxBytesPerLine = 400;  // Type integer
	// For grabbing the prefix to perform tab completion
	const TAB_COMPLETION_REGEX = /^(|.* )([^ ]*)$/;
	// A table of commands with regular structures (does not include all commands, such as /msg). Format per entry:
	// key is command name with slash, value is {minimum number of parameters, maximum number of parameters}.
	const OUTGOING_COMMAND_PARAM_COUNTS = {
		"/info"   : [0, 1],
		"/invite" : [2, 2],
		"/join"   : [1, 2],
		"/links"  : [0, 2],
		"/list"   : [0, 2],
		"/nick"   : [1, 1],
		"/part"   : [1, 1],
		"/stats"  : [0, 2],
		"/time"   : [0, 1],
		"/users"  : [0, 1],
		"/version": [0, 1],
		"/who"    : [0, 2],
		"/whois"  : [1, 2],
		"/whowas" : [1, 3],
	};
	
	/* Variables */
	var prevTabCompletion = null;  // Type tuple<begin:integer, end:integer, prefix:string, name:string> / null.
	
	/* Initialization */
	init();
	
	/* Exported functions */
	
	// Sets the text box to the given string, gives input focus, and puts the caret at the end.
	// Types: str is string, result is void.
	this.putText = function(str) {
		inputBoxElem.value = str;
		inputBoxElem.focus();
		inputBoxElem.selectionStart = inputBoxElem.selectionEnd = str.length;
	};
	
	// Clears the text in the text box. Returns nothing.
	// Types: result is void.
	this.clearText = function() {
		inputBoxElem.value = "";
	};
	
	// Sets whether the input text box is enabled or disabled.
	// Types: enable is boolean, result is void.
	this.setEnabled = function(enable) {
		inputBoxElem.disabled = !enable;
	};
	
	/* Private functions */
	
	function init() {
		document.querySelector("footer form").onsubmit = handleLine;
		inputBoxElem.oninput = colorizeLine;
		inputBoxElem.onblur = clearTabCompletion;
		inputBoxElem.onkeydown = function(ev) {
			if (ev.keyCode == 9) {
				doTabCompletion();
				return false;
			} else {
				clearTabCompletion();
				return true;
			}
		};
		inputBoxElem.value = "";
	}
	
	function handleLine() {
		var inputStr = inputBoxElem.value;
		var activeWindow = windowModule.activeWindow;
		if (activeWindow == null || inputStr == "")
			return false;
		if (isLineOverlong(inputStr)) {
			alert("Line is too long");
			return false;
		}
		
		var onerror = function(reason) {
			errorMsgModule.addMessage("Sending line failed (" + reason + "): " + inputStr);
		};
		
		if (!inputStr.startsWith("/") || inputStr.startsWith("//")) {  // Ordinary message
			if (inputStr.startsWith("//"))  // Ordinary message beginning with slash
				inputStr = inputStr.substring(1);
			networkModule.sendMessage(activeWindow[0], activeWindow[1], inputStr, onerror);
			
		} else {  // Command or special message
			// The user input command is case-insensitive. The command sent to the server will be in uppercase.
			var parts = inputStr.split(" ");
			var cmd = parts[0].toLowerCase();
			var profile = activeWindow[0];
			
			// Irregular commands
			if (cmd == "/msg" && parts.length >= 3) {
				var party = parts[1];
				var windowName = profile + "\n" + party;
				var text = utilsModule.nthRemainingPart(inputStr, 2);
				if (windowModule.windowNames.indexOf(windowName) == -1) {
					networkModule.sendAction([["open-window", profile, party], ["send-line", profile, "PRIVMSG " + party + " :" + text]], onerror);
				} else {
					windowModule.setActiveWindow(windowName);
					networkModule.sendMessage(profile, party, text, onerror);
				}
			} else if (cmd == "/me" && parts.length >= 2) {
				networkModule.sendMessage(profile, activeWindow[1], "\u0001ACTION " + utilsModule.nthRemainingPart(inputStr, 1) + "\u0001", onerror);
			} else if (cmd == "/notice" && parts.length >= 3) {
				networkModule.sendAction([["send-line", profile, "NOTICE " + parts[1] + " :" + utilsModule.nthRemainingPart(inputStr, 2)]], onerror);
			} else if (cmd == "/part" && parts.length == 1) {
				networkModule.sendAction([["send-line", profile, "PART " + activeWindow[1]]], onerror);
			} else if (cmd == "/query" && parts.length == 2) {
				windowModule.openPrivateMessagingWindow(parts[1], onerror);
			} else if (cmd == "/topic" && parts.length >= 2) {
				networkModule.sendAction([["send-line", profile, "TOPIC " + activeWindow[1] + " :" + utilsModule.nthRemainingPart(inputStr, 1)]], onerror);
			} else if (cmd == "/kick" && parts.length >= 2) {
				var reason = parts.length == 2 ? "" : utilsModule.nthRemainingPart(inputStr, 2);
				networkModule.sendAction([["send-line", profile, "KICK " + activeWindow[1] + " " + parts[1] + " :" + reason]], onerror);
			} else if (cmd == "/names" && parts.length == 1) {
				var params = activeWindow[1] != "" ? " " + activeWindow[1] : "";
				networkModule.sendAction([["send-line", profile, "NAMES" + params]], onerror);
			} else if (cmd in OUTGOING_COMMAND_PARAM_COUNTS) {
				// Regular commands
				var minMaxParams = OUTGOING_COMMAND_PARAM_COUNTS[cmd];
				var numParams = parts.length - 1;
				if (numParams >= minMaxParams[0] && numParams <= minMaxParams[1]) {
					var params = numParams > 0 ? " " + parts.slice(1).join(" ") : "";
					networkModule.sendAction([["send-line", profile, cmd.substring(1).toUpperCase() + params]], onerror);
				} else {
					alert("Invalid command");
					return false;  // Don't clear the text box
				}
			} else {
				alert("Invalid command");
				return false;  // Don't clear the text box
			}
		}
		inputBoxElem.value = "";
		return false;  // To prevent the form submitting
	}
	
	// Change classes of text box based on '/commands' and overlong text
	function colorizeLine() {
		var text = inputBoxElem.value;
		utilsModule.setClasslistItem(inputBoxElem, "is-command", text.startsWith("/") && !text.startsWith("//"));
		utilsModule.setClasslistItem(inputBoxElem, "is-overlong", isLineOverlong(text));
	}
	
	// Tests whether the given input box text line is too long.
	// Types: text is string, result is boolean. Pure function.
	function isLineOverlong(text) {
		var checktext;
		if (text.startsWith("//"))  // Message beginning with slash
			checktext = text.substring(1);
		else if (!text.startsWith("/"))  // Ordinary message
			checktext = text;
		else {  // Slash-command
			var parts = text.split(" ");
			var cmd = parts[0].toLowerCase();
			if ((cmd == "/kick" || cmd == "/msg") && parts.length >= 3)
				checktext = utilsModule.nthRemainingPart(text, 2);
			else if ((cmd == "/me" || cmd == "/topic") && parts.length >= 2)
				checktext = utilsModule.nthRemainingPart(text, 1);
			else
				checktext = text;
		}
		return utilsModule.countUtf8Bytes(checktext) > maxBytesPerLine;
	}
	
	function doTabCompletion() {
		do {  // Simulate goto
			if (document.activeElement != inputBoxElem)
				break;
			var index = inputBoxElem.selectionStart;
			if (index != inputBoxElem.selectionEnd)
				break;
			if (windowModule.activeWindow == null)
				break;
			var profile = windowModule.activeWindow[0];
			var party = windowModule.activeWindow[1];
			var candidates = windowModule.getChannelMembers(profile, party);
			if (candidates == null)
				break;
			
			var text = inputBoxElem.value;
			var match;
			var prefix;
			if (prevTabCompletion == null) {
				match = TAB_COMPLETION_REGEX.exec(text.substr(0, index));
				prefix = match[2].toLowerCase();
				if (prefix.length == 0)
					break;
			} else {
				match = null;
				prefix = prevTabCompletion[2];
			}
			
			candidates = candidates.filter(function(name) {
				return name.toLowerCase().startsWith(prefix); });
			if (candidates.length == 0)
				break;
			candidates.sort(function(s, t) {
				return s.toLowerCase().localeCompare(t.toLowerCase()); });
			
			var candidate;
			var beginning;
			if (prevTabCompletion == null) {
				candidate = candidates[0];
				beginning = match[1];
			} else {
				var oldcandidate = prevTabCompletion[3].toLowerCase();
				var i;  // Skip elements until one is strictly larger
				for (i = 0; i < candidates.length && candidates[i].toLowerCase() <= oldcandidate; i++);
				candidates.push(candidates[0]);  // Wrap-around
				candidate = candidates[i];
				beginning = text.substr(0, prevTabCompletion[0]);
			}
			var tabcomp = candidate;
			if (beginning.length == 0)
				tabcomp += ": ";
			else if (index < text.length)
				tabcomp += " ";
			inputBoxElem.value = beginning + tabcomp + text.substring(index);
			prevTabCompletion = [beginning.length, beginning.length + tabcomp.length, prefix, candidate];
			inputBoxElem.selectionStart = inputBoxElem.selectionEnd = prevTabCompletion[1];
			return;  // Don't clear the current tab completion
			
		} while (false);
		clearTabCompletion();
	}
	
	function clearTabCompletion() {
		prevTabCompletion = null;
	}
};



/*---- Context menu module ----*/

// Manages a singleton context menu that can be shown with specific menu items or hidden.
const menuModule = new function() {
	/* Initialization */
	const self = this;
	const htmlElem = document.documentElement;
	const bodyElem = document.querySelector("body");
	htmlElem.onmousedown = closeMenu;
	htmlElem.addEventListener("keydown", function(ev) {
		if (ev.keyCode == 27)  // Escape
			closeMenu();
	});
	
	/* Exported functions */
	
	// Based on the given list of menu items, this returns an event handler function to pop open the context menu.
	// Types: items is list<pair<text:string, handler:(function(Event)->void)/null>>, result is function(ev:Event)->void.
	this.makeOpener = function(items) {
		return function(ev) {
			// We use 'self' because in the event handler, 'this' is set to the element that fired the event
			self.openMenu(ev, items);
		};
	};
	
	// Immediately opens a context menu based on the coordinates in the given event and the given list of menu items.
	// Also prevents the event's default handler (such as popping up the system context menu) from running. Returns nothing.
	// Types: ev is Event, items is list<pair<text:string, handler:(function(Event)->void)/null>>, result is void.
	this.openMenu = function(ev, items) {
		// If text is currently selected, show the native context menu instead -
		// this allows the user to copy the highlight text, search the web, etc.
		if (window.getSelection().toString() != "")
			return;
		closeMenu();
		var div = document.createElement("div");
		div.id = "menu";
		
		var ul = document.createElement("ul");
		items.forEach(function(item) {
			var li = document.createElement("li");
			var child;
			if (item[1] == null) {
				child = utilsModule.createElementWithText("span", item[0]);
				child.className = "disabled";
			} else {
				child = utilsModule.createElementWithText("a", item[0]);
				child.onclick = function() {
					closeMenu();
					item[1]();
					return false;
				};
			}
			li.appendChild(child);
			ul.appendChild(li);
		});
		div.appendChild(ul);
		
		div.onmousedown = function(ev) { ev.stopPropagation(); };  // Prevent entire-document event handler from dismissing menu
		var bodyRect = bodyElem.getBoundingClientRect();
		bodyElem.appendChild(div);
		var left = ev.clientX - bodyRect.left;
		var top = ev.clientY;
		if (bodyRect.width - left < div.offsetWidth)
			left -= div.offsetWidth;
		if (bodyRect.height - top < div.offsetHeight)
			top -= div.offsetHeight;
		div.style.left = left + "px";
		div.style.top  = top + "px";
		ev.preventDefault();
	};
	
	/* Private functions */
	// Deletes the single global context menu <div> element if one is present.
	// Returns nothing. Types: result is void.
	function closeMenu() {
		var elem = elemId("menu");
		if (elem != null)
			elem.parentNode.removeChild(elem);
	}
};



/*---- Nickname colorization module ----*/

// Associates each nickname with a color. The mapping is based on hashing, and thus is stateless and consistent.
const nickColorModule = new function() {
	/* Constants */
	const COLOR_TABLE = [
		// 8 hand-tuned colors that are fairly perceptually uniform
		"DC7979", "E1A056", "C6CA34", "5EA34D", "62B5C6", "7274CF", "B97DC2", "949494",
		// 56 averages of pairs of the colors above, blended in sRGB
		"DF8E69", "D1A85E", "AC9066", "AD9BA5", "B177AB", "CB7BA3", "BD8787", "DF8E69",
		"D4B747", "B0A252", "B1AB9B", "B58CA1", "CE9099", "C09A7A", "D1A85E", "D4B747",
		"9DB842", "9EC095", "A3A69B", "C0A992", "AFB271", "AC9066", "B0A252", "9DB842",
		"60AC99", "698E9F", "959296", "7D9C77", "AD9BA5", "B1AB9B", "9EC095", "60AC99",
		"6A99CB", "969CC4", "7EA6AF", "B177AB", "B58CA1", "A3A69B", "698E9F", "6A99CB",
		"9B79C9", "8485B5", "CB7BA3", "CE9099", "C0A992", "959296", "969CC4", "9B79C9",
		"A889AD", "BD8787", "C09A7A", "AFB271", "7D9C77", "7EA6AF", "8485B5", "A889AD",
	];
	
	/* Variables */
	var nickColorCache = {};
	var nickColorCacheSize = 0;
	
	/* Exported functions */
	// Returns the color associated with the given nickname, based on a hashing algorithm.
	// 'name' is an arbitrary string, and the result is a CSS hexadecimal color in the format "#ABC012".
	// Types: name is string, result is string. Pure function.
	this.getNickColor = function(name) {
		if (!(name in nickColorCache)) {
			var hash = 1;  // Signed 32-bit integer
			for (var i = 0; i < name.length; i++) {
				for (var j = 0; j < 128; j++) {  // LFSR based on CRC-32
					if (j % 19 == 0)
						hash = (hash + name.charCodeAt(i)) | 0;
					hash = (hash >>> 1) ^ (-(hash & 1) & 0xEDB88320);
				}
			}
			if (nickColorCacheSize > 100) {
				nickColorCache = {};
				nickColorCacheSize = 0;
			}
			nickColorCache[name] = "#" + COLOR_TABLE[(hash >>> 0) % COLOR_TABLE.length];
			nickColorCacheSize++;
		}
		return nickColorCache[name];
	};
};



/*---- Toast notifications module ----*/

// Manages desktop toast notifications and allows new ones to be posted.
const notificationModule = new function() {
	/* Variables */
	var enabled = "Notification" in window;
	
	/* Initialization */
	if (enabled)
		Notification.requestPermission();
	
	/* Exported functions */
	
	// Posts a notification of the given message text in the given window on the given channel by the given user.
	// 'windowName' is in the format 'profile+"\n"+party'. 'message' has '/me' auto-detected and formatting codes automatically stripped.
	// Types: windowName is string, channel is string/null, user is string, message is string, result is void.
	this.notifyMessage = function(windowName, channel, user, message) {
		var s = (channel != null) ? (channel + " ") : "";
		var match = formatTextModule.matchMeMessage(message);
		s += (match == null) ? ("<" + user + ">") : ("* " + user);
		s += " " + formatTextModule.fancyToPlainText((match == null) ? message : match[1]);
		this.notifyRaw(windowName, s);
	};
	
	// Posts a notification of the given raw text in the given window. 'windowName' is in the format 'profile+"\n"+party'.
	// Types: windowName is string, text is string, result is void.
	this.notifyRaw = function(windowName, text) {
		if (enabled) {
			var opts = {icon: "tomoe-mami-icon-text.png"};
			var notif = new Notification(truncateLongText(text), opts);
			notif.onclick = function() {
				windowModule.setActiveWindow(windowName);
			};
			setTimeout(function() { notif.close(); }, 10000);  // Hide the notification sooner than Google Chrome's ~20-second timeout
		}
	};
	
	/* Private functions */
	
	// Returns either str if short enough, or some prefix of str with "..." appended.
	// The function is needed because Mozilla Firefox allows ridiculously long notification lines to be displayed.
	// Types: str is string, result is string. Pure function.
	function truncateLongText(str) {
		var LIMIT = 150;
		var i = 0;
		// count is the number of Unicode code points seen, not UTF-16 code units
		for (var count = 0; i < str.length && count < LIMIT; i++) {
			var cc = str.charCodeAt(i);
			if (cc < 0xD800 || cc >= 0xDC00)  // Increment if ordinary character or low surrogate, but not high surrogate
				count++;
		}
		if (i == str.length)
			return str;
		else
			return str.substr(0, i) + "...";
	}
};



/*---- Utilities module ----*/

// A set of functions that are somewhat general, not too specific to the problem domain of MamIRC.
// This module only contains public, stateless functions. These functions may return a new
// value or change an argument's state. They never read/write global state or perform I/O.
const utilsModule = new function() {
	/* Exported functions */
	
	// Returns the rest of the string after exactly n spaces. For example: nthRemainingPart("a b c", 0) -> "a b c";
	// nthRemainingPart("a b c", 1) -> "b c"; nthRemainingPart("a b c", 3) -> throws exception.
	// Types: str is string, n is integer, result is string. Pure function.
	this.nthRemainingPart = function(str, n) {
		var j = 0;
		for (var i = 0; i < n; i++) {
			j = str.indexOf(" ", j) + 1;
			if (j == 0)
				throw "Space not found";
		}
		return str.substring(j);
	};
	
	// Returns the number of bytes in the UTF-8 encoded representation of the given string. Handles paired
	// and unpaired UTF-16 surrogates correctly. Types: str is string, result is integer. Pure function.
	this.countUtf8Bytes = function(str) {
		var result = 0;
		for (var i = 0; i < str.length; i++) {
			var c = str.charCodeAt(i);
			if (c < 0x80)
				result += 1;
			else if (c < 0x800)
				result += 2;
			else if (0xD800 <= c && c < 0xDC00 && i + 1 < str.length  // Check for properly paired UTF-16 high and low surrogates
					&& 0xDC00 <= str.charCodeAt(i + 1) && str.charCodeAt(i + 1) < 0xE000) {
				result += 4;
				i++;
			} else
				result += 3;
		}
		return result;
	};
	
	// Tests whether the given string is the name of a channel.
	// Types: name is string, result is boolean. Pure function.
	this.isChannelName = function(name) {
		return name.startsWith("#") || name.startsWith("&");
	}
	
	// Converts the given integer to a two-digit string. For example, 0 -> "00", 9 -> "09", 23 -> "23".
	// Types: n is integer, result is string. Pure function.
	this.twoDigits = function(n) {
		return (n < 10 ? "0" : "") + n;
	};
	
	// Removes all the children of the given DOM element. Returns nothing.
	// Types: elem is HTMLElement (mutable), result is void.
	this.clearChildren = function(elem) {
		while (elem.firstChild != null)
			elem.removeChild(elem.firstChild);
	};
	
	// Returns a new DOM element with the given tag name, with a text node of the given content
	// as its only child. Types: tagName is string, text is string, result is HTMLElement. Pure function.
	this.createElementWithText = function(tagName, text) {
		var result = document.createElement(tagName);
		result.appendChild(textNode(text));
		return result;
	};
	
	// Modifies the given element's class list so that it contains / not contain the given token name. Returns nothing.
	// Types: elem is HTMLElement (mutable), name is string, enable is boolean, result is void.
	this.setClasslistItem = function(elem, name, enable) {
		var clslst = elem.classList;
		if (clslst.contains(name) != enable)
			clslst.toggle(name);
	};
};



/*---- Alert messages module ----*/

// Manages the panel of error messages and allows new lines to be added.
const errorMsgModule = new function() {
	/* Constants */
	const errorMsgContainerElem = elemId("error-msg-container");
	const errorMsgElem          = elemId("error-msg");
	
	/* Initialization */
	utilsModule.clearChildren(errorMsgElem);
	errorMsgContainerElem.querySelector("a").onclick = function() {
		utilsModule.setClasslistItem(errorMsgContainerElem, "hide", true);
		utilsModule.clearChildren(errorMsgElem);
		return false;
	};
	
	/* Exported functions */
	// Appends the given text to the list of error messages, showing the panel if hidden.
	// Types: str is string, result is void.
	this.addMessage = function(str) {
		utilsModule.setClasslistItem(errorMsgContainerElem, "hide", false);
		var li = utilsModule.createElementWithText("li", str);
		errorMsgElem.appendChild(li);
	};
};



/*---- Network communication module ----*/

const networkModule = new function() {
	/* Variables */
	const self = this;
	// Type integer/null. At least 0.
	var nextUpdateId = null;
	// Type integer. This value, in milliseconds, changes during execution depending on successful/failed requests.
	var retryTimeout = 1000;
	// Type string/null.
	var csrfToken = null;
	// Type integer / null (returned by window.setTimeout()).
	var setInitialWindowTimeout = null;
	
	/* Exported functions */
	
	// Initializes this module. Must not be called more than once.
	this.init = function() {
		getState();
		checkTimeSkew();
	};
	
	// Sends the given payload of commands to the MamIRC processor. If an error occurs, the onerror callback is called.
	// Types: payload is list<list<object>>, onerror is function(reason:string)->void / null, result is void.
	this.sendAction = function(payload, onerror) {
		var xhr = new XMLHttpRequest();
		if (onerror != null) {
			xhr.onload = function() {
				var data = JSON.parse(xhr.response);
				if (data != "OK")
					onerror(data.toString());
			};
			xhr.ontimeout = function() {
				onerror("Connection timeout");
			};
			xhr.error = function() {
				onerror("Network error");
			};
		}
		xhr.open("POST", "do-actions.json", true);
		xhr.responseType = "text";
		xhr.timeout = 5000;
		xhr.send(JSON.stringify({"payload":payload, "csrfToken":csrfToken, "nextUpdateId":nextUpdateId}));
	};
	
	// Sends a request to the MamIRC processor to send an IRC PRIVMSG to the given party.
	// Note that the value (profile+"\n"+party) need not currently exist in windowNames.
	// Types: profile is string, party is string, text is string, onerror is function(reason:string)->void / null, result is void.
	this.sendMessage = function(profile, party, text, onerror) {
		this.sendAction([["send-line", profile, "PRIVMSG " + party + " :" + text]], onerror);
	};
	
	// Sends a request to the Processor to set the initial window to the given window name, but only after
	// the given delay in milliseconds. If another setInitialWindowDelayed() request is called before
	// the delay expires, the previous request is cancelled and a new delayed request starts counting down.
	// Types: profile is string, party is string, delay is integer, result is void.
	this.setInitialWindowDelayed = function(profile, party, delay) {
		if (setInitialWindowTimeout != null)
			clearTimeout(setInitialWindowTimeout);
		setInitialWindowTimeout = setTimeout(function() {
			networkModule.sendAction([["set-initial-window", profile, party]], null);
			setInitialWindowTimeout = null;
		}, delay);
	};
	
	/* Private functions */
	
	// Called by init(), or from updateState() after a severe state desynchronization. Returns nothing.
	function getState() {
		var xhr = new XMLHttpRequest();
		xhr.onload = function() {
			var data = JSON.parse(xhr.response);
			if (typeof data != "string") {  // Good data
				nextUpdateId = data.nextUpdateId;
				csrfToken = data.csrfToken;
				userConfiguration = data.userConfiguration;
				windowModule.loadState(data);  // Process data and update UI
				updateState();  // Start polling
			}
		};
		xhr.ontimeout = xhr.onerror = function() {
			var li = utilsModule.createElementWithText("li", "(Unable to connect to data provider)");
			windowListElem.appendChild(li);
		};
		xhr.open("POST", "get-state.json", true);
		xhr.responseType = "text";
		xhr.timeout = 10000;
		xhr.send(JSON.stringify({"maxMessagesPerWindow":maxMessagesPerWindow}));
	}
	
	// Called by only getState() or updateState(). Returns nothing.
	function updateState() {
		var xhr = new XMLHttpRequest();
		xhr.onload = function() {
			if (xhr.status != 200)
				xhr.onerror();
			else {
				var data = JSON.parse(xhr.response);
				if (data != null) {  // Success
					nextUpdateId = data.nextUpdateId;
					windowModule.loadUpdates(data);
					retryTimeout = 1000;
					updateState();
				} else {  // Lost synchronization or fell behind too much; do full update and re-render text
					setTimeout(getState, retryTimeout);
					if (retryTimeout < 300000)
						retryTimeout *= 2;
				}
			}
		};
		xhr.ontimeout = xhr.onerror = function() {
			setTimeout(updateState, retryTimeout);
			if (retryTimeout < 300000)
				retryTimeout *= 2;
		};
		var maxWait = 60000;
		xhr.open("POST", "get-updates.json", true);
		xhr.responseType = "text";
		xhr.timeout = maxWait + 20000;
		xhr.send(JSON.stringify({"nextUpdateId":nextUpdateId, "maxWait":maxWait}));
	}
	
	// Called by only init() or checkTimeSkew(). Returns nothing.
	function checkTimeSkew() {
		var xhr = new XMLHttpRequest();
		xhr.onload = function() {
			var skew = Date.now() - JSON.parse(xhr.response);
			if (Math.abs(skew) > 10000)
				errorMsgModule.addMessage("Warning: Client time is " + Math.abs(skew / 1000) + " seconds " + (skew > 0 ? "ahead" : "behind") + " server time");
		};
		xhr.open("POST", "get-time.json", true);
		xhr.responseType = "text";
		xhr.send(JSON.stringify(""));
		setTimeout(checkTimeSkew, 100000000);  // About once a day
	}
};



/*---- Network profile configuration UI module ----*/

const profileConfigModule = new function() {
	/* Constants */
	const screenElem = elemId("network-profiles");
	const containerElem = elemId("network-profiles-container");
	const exampleFullnames = ["Alice Margatroid", "Bob Kovsky", "Carol Hong Zhou", "Dave M. Smith"];
	const blankProfile = {
		connect: true,
		servers: [],
		nicknames: [],
		username: "",
		realname: "",
		nickservPassword: null,
		channels: [],
	};
	
	/* Initialization */
	init();
	
	/* Private functions */
	
	// Sets click event handlers on HTML elements. Types: result is void.
	function init() {
		elemId("configure-profiles").onclick = function() {
			if (!screenElem.classList.contains("hide"))
				return;
			var xhr = new XMLHttpRequest();
			xhr.onload = function() {
				var data = JSON.parse(xhr.response);
				showDialog(data);
			};
			xhr.open("POST", "get-profiles.json", true);
			xhr.responseType = "text";
			xhr.send(JSON.stringify(""));
		};
		elemId("add-irc-network").onclick = function() {
			containerElem.appendChild(createProfileForm(
				containerElem.getElementsByTagName("form").length, null, blankProfile));
		};
		elemId("save-network-profiles").onclick = saveProfiles;
		elemId("close-network-profiles").onclick = closeDialog;
	}
	
	// Types: profileData is object, result is void.
	function showDialog(profileData) {
		var profileNames = Object.keys(profileData);
		profileNames.sort();
		profileNames.forEach(function(name, i) {
			containerElem.appendChild(createProfileForm(i, name, profileData[name]));
		});
		if (profileNames.length == 0)
			elemId("add-irc-network").onclick();
		utilsModule.setClasslistItem(screenElem, "hide", false);
	}
	
	// Types: parentElem is HTMLElement, labelText is string, textBoxId is string, inputType is string,
	// initValue is string, placeholderText is string/null, commentText is string, result is void.
	function appendTextBoxRow(parentElem, labelText, textBoxId, inputType, initValue, placeholderText, commentText) {
		var tr = document.createElement("tr");
		var td = document.createElement("td");
		var label = utilsModule.createElementWithText("label", labelText);
		label.htmlFor = textBoxId;
		td.appendChild(label);
		tr.appendChild(td);
		
		var td = document.createElement("td");
		var input = document.createElement("input");
		input.type = inputType;
		input.value = initValue;
		if (placeholderText != null)
			input.placeholder = placeholderText;
		input.id = textBoxId;
		td.appendChild(input);
		td.appendChild(utilsModule.createElementWithText("small", " (" + commentText + ")"));
		tr.appendChild(td);
		parentElem.appendChild(tr);
	}
	
	// Types: i is integer, name is string/null, profile is object{connect:boolean,
	// servers:list<object{hostname:string, port:integer, ssl:boolean}>, nicknames:list<string>,
	// username:string, realname:string, nickservPassword:string/null, channels:list<string>},
	// result is HTMLElement. Pure function.
	function createProfileForm(i, name, profile) {
		var form = document.createElement("form");
		var table = document.createElement("table");
		var tbody = document.createElement("tbody");
		appendTextBoxRow(tbody, "Profile name:", "profile" + i + "-name", "text",
			(name == null ? "" : name), "e.g. Abcd Net " + i, "unique, required");
		if (name != null) {
			form.appendChild(utilsModule.createElementWithText("h3", name));
			utilsModule.setClasslistItem(tbody.lastChild, "hide", true);
		}
		
		// "Connect" checkbox row
		var tr = document.createElement("tr");
		var td = document.createElement("td");
		td.colSpan = 2;
		var input = document.createElement("input");
		input.type = "checkbox";
		input.checked = profile.connect;
		input.id = "profile" + i + "-connect";
		td.appendChild(input);
		var label = utilsModule.createElementWithText("label", " Connect");
		label.htmlFor = input.id;
		td.appendChild(label);
		tr.appendChild(td);
		tbody.appendChild(tr);
		
		// "Servers" row and sub-rows
		tr = document.createElement("tr");
		tr.appendChild(utilsModule.createElementWithText("td", "IRC servers:"));
		td = document.createElement("td");
		var ul = document.createElement("ul");
		profile.servers.forEach(function(serverEntry, j) {
			ul.appendChild(createServerRow(i, j, serverEntry.hostname, serverEntry.port, serverEntry.ssl));
		});
		var li = document.createElement("li");
		var a = utilsModule.createElementWithText("a", "+ Add another server");
		a.onclick = function() {
			ul.insertBefore(createServerRow(i, ul.children.length - 1, "", -1, false), li);
		};
		li.appendChild(a);
		ul.appendChild(li);
		if (name == null)
			a.onclick();
		td.appendChild(ul);
		tr.appendChild(td);
		tbody.appendChild(tr);
		
		// Five rows that have a pattern
		var exampleFullname = exampleFullnames[Math.floor(Math.random() * exampleFullnames.length)];
		var exampleName = /^([^ ]+)/.exec(exampleFullname)[1];
		appendTextBoxRow(tbody, "Nicknames:", "profile" + i + "-nicknames", "text",
			profile.nicknames.join(", "), "e.g. " + exampleName + ", " + exampleName + "_, " + exampleName + "2", "at least one");
		appendTextBoxRow(tbody, "Username", "profile" + i + "-username", "text",
			profile.username, "e.g. " + exampleName, "required");
		appendTextBoxRow(tbody, "Real name:", "profile" + i + "-realname", "text",
			profile.realname, "e.g. " + exampleFullname, "required");
		appendTextBoxRow(tbody, "NickServ password:", "profile" + i + "-nickservpassword", "password",
			(profile.nickservPassword != null ? profile.nickservPassword : ""), null, "optional");
		appendTextBoxRow(tbody, "Channels to join:", "profile" + i + "-channelstojoin", "text",
			profile.channels.join(", "), "e.g. #alpha, #beta, #delta key, &gamma", "any");
		
		// Prevent overzealous password auto-fill
		if (profile.nickservPassword == null) {
			for (var j = 0; j < 50; j += 5) {
				setTimeout(function() {
					elemId("profile" + i + "-nickservpassword").value = "";
				}, j);
			}
		}
		
		table.appendChild(tbody);
		form.appendChild(table);
		return form;
	}
	
	// Types: i is integer, j is integer, hostname is string, port is integer, ssl is boolean, result is HTMLElement. Pure function.
	function createServerRow(i, j, hostname, port, ssl) {
		var li = document.createElement("li");
		var input = document.createElement("input");
		input.type = "text";
		input.value = hostname;
		input.placeholder = "irc.example.com (hostname)";
		li.appendChild(input);
		li.appendChild(textNode(" "));
		input = document.createElement("input");
		input.type = "number";
		input.min = 0;
		input.max = 65535;
		input.value = port != -1 ? port.toString() : "";
		input.placeholder = "port";
		li.appendChild(input);
		li.appendChild(textNode(" "));
		input = document.createElement("input");
		input.type = "checkbox";
		input.checked = ssl;
		input.id = "profile" + i + "-server" + j + "-" + "ssl";
		li.appendChild(input);
		var label = utilsModule.createElementWithText("label", " SSL");
		label.htmlFor = input.id;
		li.appendChild(label);
		return li;
	}
	
	function saveProfiles() {
		var profiles = {};
		try {
			var formElems = containerElem.getElementsByTagName("form");
			for (var i = 0; i < formElems.length; i++) {  // Parse each profile form
				var form = formElems[i];
				var inputs = form.getElementsByTagName("input");  // Raw list of all input fields
				var end = inputs.length;
				if (end % 3 != 1)
					throw "Assertion error";
				
				// Parse list of servers
				var servers = [];
				for (var j = 2; j < end - 5; j += 3) {
					var server = {
						hostname: inputs[j + 0].value.trim(),
						port: parseInt(inputs[j + 1].value, 10),
						ssl: inputs[j + 2].checked,
					};
					if (server.hostname != "" && server.port >= 0 && server.port <= 0xFFFF)
						servers.push(server);  // Add if info is filled, otherwise drop the entry
				}
				
				// Parse all other fields
				var name = inputs[0].value.trim();
				var profile = {
					connect: inputs[1].checked,
					servers: servers,
					nicknames: splitByComma(inputs[end - 5].value.trim()),
					username: inputs[end - 4].value.trim(),
					realname: inputs[end - 3].value.trim(),
					nickservPassword: inputs[end - 2].value,
					channels: splitByComma(inputs[end - 1].value.trim()),
				};
				if (profile.nickservPassword == "")  // A bit of postprocessing
					profile.nickservPassword = null;
				
				// Check basic validity
				if (name == "")
					continue;  // Drop this profile entirely
				if (name in profiles)
					throw "Duplicate profile name: " + name;
				profile.nicknames.forEach(function(nick) {
					if (nick == "")
						throw "Nickname is blank";
					if (nick.indexOf(" ") != -1)
						throw "Nickname cannot contain spaces";
				});
				profile.channels.forEach(function(chan) {
					if (chan.indexOf(" ") != chan.lastIndexOf(" "))  // If contains 2 or more spaces
						throw "Invalid channel name: \"" + chan + "\"";
				});
				
				// Check completeness
				if (profile.connect) {
					if (profile.servers.length == 0)
						throw "Cannot connect to profile " + name + ": No server specified";
					if (profile.nicknames.length == 0)
						throw "Cannot connect to profile " + name + ": No nickname specified";
					if (profile.username.length == 0)
						throw "Cannot connect to profile " + name + ": No username specified";
					if (profile.username.indexOf(" ") != -1)
						throw "Username cannot contain spaces";
					if (profile.realname.length == 0)
						throw "Cannot connect to profile " + name + ": No real name specified";
				}
				profiles[name] = profile;
			}
		} catch (e) {
			alert(e.toString());
			return;
		}
		networkModule.sendAction([["set-profiles", profiles]],
			function() { errorMsgModule.addMessage("Setting profiles failed due to network error"); });
		closeDialog();
	}
	
	function closeDialog() {
		utilsModule.setClasslistItem(screenElem, "hide", true);
		utilsModule.clearChildren(containerElem);
	}
	
	// Splits a string by the separator sequence <any whitespace> <comma> <any whitespace>.
	// However, a zero-length array is returned if the argument is the empty string.
	// Types: str is string, result is list<string>. Pure function.
	function splitByComma(str) {
		if (str == "")
			return [];
		else
			return str.split(/\s*,\s*/);
	}
};



/*---- Miscellaneous ----*/

// This definition exists only for the purpose of abbreviation, because it is used so many times.
// Types: name is string, result is HTMLElement/null. Pure function.
function elemId(name) {
	return document.getElementById(name);
}


// This definition exists only for the purpose of abbreviation, because it is used so many times.
// Types: text is string, result is Text (Node). Pure function.
function textNode(text) {
	return document.createTextNode(text);
}


// Polyfill for Apple Safari and Microsoft Internet Explorer.
if (!("startsWith" in String.prototype)) {
	String.prototype.startsWith = function(text, pos) {
		if (pos == undefined)
			pos = 0;
		return this.length - pos >= text.length && this.substr(pos, text.length) == text;
	};
}


/* Global variables */

// Type boolean.
var optimizeMobile = false;

// Configurable parameter. Used by getState().
var maxMessagesPerWindow = 3000;

// JSON object.
var userConfiguration = null;


/* Initialization */

function init() {
	// Parse cookie for preferences
	var cookieParts = document.cookie.split(";");
	cookieParts.forEach(function(s) {
		s = s.trim();
		if (s.startsWith("optimize-mobile="))
			optimizeMobile = s.substring(16) == "true";
	});
	if (optimizeMobile)
		maxMessagesPerWindow = 500;
	
	// Fetch data
	networkModule.init();
}

// This initialization call must come last due to variables and modules being declared and initialized.
init();
