"use strict";


/*---- Global variables ----*/

// Document nodes (elements)
const windowListElem  = document.getElementById("window-list");
const messageListElem = document.getElementById("message-list");
const memberListElem  = document.getElementById("member-list");
const inputBoxElem    = document.getElementById("input-box");
const channelElem     = document.getElementById("channel");
const nicknameElem    = document.getElementById("nickname");
const passwordElem    = document.getElementById("password");
const htmlElem        = document.documentElement;

var backgroundImageCss;  // Assigned only once at init()


/* Main state */

// Most variables are null before getState() returns successfully. Thereafter, most of them are non-null.

// Type tuple<str profile, str party, str concatenated>.
// Is null if windowNames is null or zero-length, otherwise this[2] equals an entry in windowNames.
var activeWindow = null;

// Type list<str>. Length 0 or more. Each element is of the form (profile+"\n"+party).
// Elements can be in any order, and it determines the order rendered on screen.
var windowNames = null;

// Type map<str,window>. Key is an entry in windowNames. Each window has these properties:
// - list<list<int seq, int flags, int timestamp, str... payload>> lines
// - int markedReadUntil
// - int numNewMessages
var windowData = null;

// Type map<str,object>. Key is the network profile name. Each object has these properties:
// - str currentNickname
// - map<str,object> channels, with values having {"members" -> list<str>, "topic" -> str or null}
var connectionData = null;

// Type int. At least 0.
var nextUpdateId = null;

// Type str. Value is set by submitting the login form, and remains unchanged after a successful getState().
var password = null;

// In milliseconds. This value changes during execution depending on successful/failed requests.
var retryTimeout = 1000;


/* Miscellaneous values */

// Configurable parameter.
const MAX_MESSAGES_PER_WINDOW = 3000;

// Type map<str,int>. It is a collection of integer constants, defined in Java code to avoid duplication. Values are set by getState().
var Flags = null;



/*---- User interface functions ----*/

// Called once after the script and page are loaded.
function init() {
	document.getElementById("login").getElementsByTagName("form")[0].onsubmit = authenticate;
	document.getElementById("main" ).getElementsByTagName("form")[0].onsubmit = handleInputLine;
	backgroundImageCss = window.getComputedStyle(htmlElem).backgroundImage;  // str: 'url("foo.png")'
	Notification.requestPermission();
	
	htmlElem.onmousedown = closeContextMenu;
	inputBoxElem.oninput = function() {
		// Change style of text box based if a '/command' is being typed
		var text = inputBoxElem.value;
		inputBoxElem.className = text.startsWith("/") && !text.startsWith("//") ? "is-command" : "";
	};
	inputBoxElem.value = "";
	passwordElem.oninput = function() {
		removeChildren(document.getElementById("login-status"));
	};
	passwordElem.focus();
}


// Called only by submitting the login form.
function authenticate() {
	password = passwordElem.value;
	getState();
	return false;  // To prevent the form submitting
}


// Called only by getState(). inData is a object parsed from JSON text.
function loadState(inData) {
	// Set simple fields
	nextUpdateId = inData.nextUpdateId;
	connectionData = inData.connections;
	Flags = inData.flagsConstants;
	
	// Handle the windows
	windowNames = [];
	windowData = {};
	inData.windows.forEach(function(inWindow) {
		// 'inWindow' has type tuple<str profile, str party, window state>
		var windowName = inWindow[0] + "\n" + inWindow[1];
		if (windowNames.indexOf(windowName) != -1)
			throw "Duplicate window";
		windowNames.push(windowName);
		
		// Preprocess the window's lines
		var inState = inWindow[2];
		var lines = inState.lines;
		var prevTimestamp = 0;
		lines.forEach(function(line) {
			line[2] += prevTimestamp;  // Delta decoding
			prevTimestamp = line[2];
		});
		if (lines.length > MAX_MESSAGES_PER_WINDOW)
			lines.splice(0, lines.length - MAX_MESSAGES_PER_WINDOW);
		windowData[windowName] = {
			lines: lines,
			markedReadUntil: inState.markedReadUntil,
			numNewMessages: 0,
		};
	});
	activeWindow = null;
	windowNames.sort();
	
	// Update UI elements
	passwordElem.blur();
	document.getElementById("login").style.display = "none";
	document.getElementById("main").style.removeProperty("display");
	htmlElem.style.backgroundImage = "linear-gradient(rgba(255,255,255,0.97),rgba(255,255,255,0.97)), " + backgroundImageCss;
	redrawWindowList();
	if (windowNames.length > 0)
		setActiveWindow(windowNames[0]);
}


// Clears the window list HTML container element and rebuilds it from scratch based on
// the current states of windowNames, windowData[windowName].newMessages, and activeWindow.
function redrawWindowList() {
	removeChildren(windowListElem);
	var prevProfile = null;
	windowNames.forEach(function(windowName) {
		// windowName has type str, and is of the form (profile+"\n"+party)
		var parts = windowName.split("\n");
		var profile = parts[0];
		var party = parts[1];
		
		// Add <li class="profile"> at the start of each new profile
		if (prevProfile == null || profile != prevProfile) {
			var extrali = document.createElement("li");
			setElementText(extrali, profile);
			extrali.className = "profile";
			windowListElem.appendChild(extrali);
			prevProfile = profile;
		}
		
		// Create the anchor element
		var a = document.createElement("a");
		var s = party;
		var n = windowData[windowName].numNewMessages;
		if (n > 0)
			s += " (" + n + ")";
		setElementText(a, s);
		a.href = "#";
		a.onclick = function() {
			setActiveWindow(windowName);
			return false;
		};
		a.oncontextmenu = makeContextMenuOpener([["Close window", function() { sendAction([["close-window", profile, party]], null, null); }]]);
		
		var li = document.createElement("li");
		li.appendChild(a);
		windowListElem.appendChild(li);
	});
	refreshWindowSelection();
	
	var totalNewMsg = 0;
	for (var key in windowData)
		totalNewMsg += windowData[key].numNewMessages;
	if (activeWindow != null)
		document.title = (totalNewMsg > 0 ? "(" + totalNewMsg + ") " : "") + activeWindow[1] + " - " + activeWindow[0] + " - MamIRC";
}


// Refreshes the selection class of each window <li> element based on the states of windowNames and activeWindow.
// This assumes that the list of HTML elements is already synchronized with windowNames.
function refreshWindowSelection() {
	if (activeWindow == null)
		return;
	var windowLis = windowListElem.getElementsByTagName("li");
	for (var i = 0, j = 0; i < windowLis.length; i++) {
		if (windowLis[i].className != "profile") {
			windowLis[i].className = windowNames[j] == activeWindow[2] ? "selected" : "";
			j++;
		}
	}
}


// Refreshes the channel members text element based on the states of
// connectionData[profileName].channels[channelName].members and activeWindow.
function redrawChannelMembers() {
	var profile = activeWindow[0], party = activeWindow[1];
	var str = "";
	if (profile in connectionData && party in connectionData[profile].channels) {
		var members = connectionData[profile].channels[party].members;
		members.sort(function(s, t) {  // Safe mutation; case-insensitive ordering
			return s.toLowerCase().localeCompare(t.toLowerCase());
		});
		str = "Channel members: " + members.join(", ");
	}
	setElementText(memberListElem, str);
}


// Changes activeWindow and redraws the user interface. 'name' must exist in the array windowNames.
// Note that for efficiency, switching to the already active window does not re-render the table of lines.
// Thus all other logic must update the active window's lines incrementally whenever new updates arrive.
function setActiveWindow(name) {
	// activeWindow may be null at the start of this method, but will be non-null afterward
	windowData[name].numNewMessages = 0;
	if (activeWindow != null && activeWindow[2] == name) {
		redrawWindowList();
		return;
	}
	
	// Set state, refresh text, refresh window selection
	activeWindow = name.split("\n").concat(name);
	setElementText(nicknameElem, connectionData[activeWindow[0]].currentNickname);
	setElementText(channelElem, activeWindow[1]);
	redrawWindowList();
	redrawChannelMembers();
	
	// Redraw all message lines in this window
	removeChildren(messageListElem);
	windowData[name].lines.forEach(function(line) {
		// 'line' has type tuple<int seq, int timestamp, str line, int flags>
		messageListElem.appendChild(lineDataToRowElem(line));
	});
	reflowMessagesTable();
	window.scrollTo(0, document.documentElement.scrollHeight);
}


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
// This function can only be called for lines in the active window; it must not be used for off-screen windows.
function lineDataToRowElem(line) {
	// Input variables
	const sequence = line[0];
	const flags = line[1];
	const timestamp = line[2];
	const payload = line.slice(3);
	const type = flags & Flags.TYPE_MASK;
	
	// Output variables
	var who = "*";         // Type str
	var lineElems = [];    // Type list<domnode>
	var quoteText = null;  // Type str or null
	var tr = document.createElement("tr");
	
	// Take action depending on head of payload
	if (type == Flags.PRIVMSG) {
		who = payload[0];
		var s = payload[1];
		var mematch = ME_INCOMING_REGEX.exec(s);
		if (mematch != null)
			s = mematch[1];
		
		if ((flags & Flags.OUTGOING) != 0)
			tr.classList.add("outgoing");
		if ((flags & Flags.NICKFLAG) != 0)
			tr.classList.add("nickflag");
		quoteText = s.replace(/\t/g, " ").replace(/[\u0000-\u001F]/g, "");  // Sanitize formatting control characters
		
		// Split the string into regular text and URL links
		do {
			// Try to grab the next URL
			var linkmatch = /(^|.*?\()(https?:\/\/[^ )]+)(.*)/.exec(s);
			if (linkmatch == null)
				linkmatch = /(^|.*? )(https?:\/\/[^ ]+)(.*)/.exec(s);
			if (linkmatch == null) {
				// No URL found
				lineElems.push(document.createTextNode(s));
				break;
			} else {
				// URL found
				if (linkmatch[1].length > 0)
					lineElems.push(document.createTextNode(linkmatch[1]));
				var a = document.createElement("a");
				a.href = linkmatch[2];
				a.target = "_blank";
				setElementText(a, linkmatch[2]);
				lineElems.push(a);
				s = linkmatch[3];
			}
		} while (s != "");
		if (mematch != null) {
			tr.classList.add("me-action");
			quoteText = "* " + who + " " + quoteText;
		} else {
			quoteText = "<" + who + "> " + quoteText;
		}
		
	} else if (type == Flags.NOTICE) {
		who = "(" + payload[0] + ")";
		lineElems.push(document.createTextNode(payload[1]));
	} else if (type == Flags.NICK) {
		lineElems.push(document.createTextNode(payload[0] + " changed their name to " + payload[1]));
	} else if (type == Flags.JOIN) {
		lineElems.push(document.createTextNode(payload[0] + " joined the channel"));
	} else if (type == Flags.PART) {
		lineElems.push(document.createTextNode(payload[0] + " left the channel"));
	} else if (type == Flags.QUIT) {
		lineElems.push(document.createTextNode(payload[0] + " has quit: " + payload[1]));
	} else if (type == Flags.KICK) {
		lineElems.push(document.createTextNode(payload[0] + " was kicked: " + payload[1]));
	} else if (type == Flags.TOPIC) {
		lineElems.push(document.createTextNode(payload[0] + " set the channel topic to: " + payload[1]));
	} else if (type == Flags.INITNOTOPIC) {
		lineElems.push(document.createTextNode("No channel topic is set"));
	} else if (type == Flags.INITTOPIC) {
		lineElems.push(document.createTextNode("The channel topic is: " + payload[0]));
	} else {
		who = "RAW";
		lineElems.push(document.createTextNode("flags=" + flags + " " + payload.join(" ")));
	}
	
	// Make timestamp cell
	var td = document.createElement("td");
	td.appendChild(document.createTextNode(formatDate(timestamp * 1000)));
	tr.appendChild(td);
	
	// Make nickname cell
	td = document.createElement("td");
	td.appendChild(document.createTextNode(who));
	if (who != "*" && who != "RAW")
		td.oncontextmenu = makeContextMenuOpener([["Open PM window", function() { openPrivateMessagingWindow(who); }]]);
	tr.appendChild(td);
	
	// Make message cell and its sophisticated context menu
	td = document.createElement("td");
	lineElems.forEach(function(elem) {
		td.appendChild(elem);
	});
	var menuItems = [["Quote text", null]];
	if (quoteText != null) {
		menuItems[0][1] = function() {
			inputBoxElem.value = quoteText;
			inputBoxElem.focus();
			inputBoxElem.selectionStart = inputBoxElem.selectionEnd = quoteText.length;
		};
	}
	menuItems.push(["Mark read to here", function() { sendAction([["mark-read", activeWindow[0], activeWindow[1], sequence + 1]], null, null); }]);
	menuItems.push(["Clear to here", function() {
		closeContextMenu();
		if (confirm("Do you want to clear text?"))
			sendAction([["clear-lines", activeWindow[0], activeWindow[1], sequence + 1]], null, null);
	}]);
	td.oncontextmenu = makeContextMenuOpener(menuItems);
	tr.appendChild(td);
	
	// Finishing touches
	if (sequence < windowData[activeWindow[2]].markedReadUntil)
		tr.classList.add("read");
	else
		tr.classList.add("unread");
	return tr;
}

const ME_INCOMING_REGEX = /^\u0001ACTION (.*)\u0001$/;


function loadUpdates(inData) {
	nextUpdateId = inData.nextUpdateId;
	
	const scrollToBottom = inputBoxElem.getBoundingClientRect().bottom < document.documentElement.clientHeight;
	const scrollPosition = document.documentElement.scrollTop;
	var activeWindowUpdated = false;
	inData.updates.forEach(function(payload) {
		var type = payload[0];
		
		if (type == "APPEND") {
			var windowName = payload[1] + "\n" + payload[2];
			var newWindow = false;
			if (windowNames.indexOf(windowName) == -1) {
				windowNames.push(windowName);
				windowNames.sort();
				windowData[windowName] = {
					lines: [],
					markedReadUntil: 0,
					numNewMessages: 0,
				};
				redrawWindowList();
				newWindow = true;
			}
			var line = payload.slice(3);
			var lines = windowData[windowName].lines;
			lines.push(line);
			var numPrefixDel = Math.max(lines.length - MAX_MESSAGES_PER_WINDOW, 0);
			lines.splice(0, numPrefixDel);
			if (windowName == activeWindow[2]) {
				messageListElem.appendChild(lineDataToRowElem(line));
				for (var i = 0; i < numPrefixDel; i++)
					messageListElem.removeChild(messageListElem.firstChild);
				activeWindowUpdated = true;
			}
			var subtype = line[1] & Flags.TYPE_MASK;
			if (subtype == Flags.PRIVMSG) {
				if (windowName == activeWindow[2] && (line[1] & Flags.OUTGOING) != 0)
					windowData[windowName].numNewMessages = 0;
				else
					windowData[windowName].numNewMessages++;
				redrawWindowList();
				if (!payload[2].startsWith("#") && !payload[2].startsWith("&") && (newWindow || (line[1] & Flags.NICKFLAG) != 0)) {
					// Is a private message instead of a channel
					new Notification("<" + line[3] + "> " + line[4]);
				} else if ((line[1] & Flags.NICKFLAG) != 0) {
					new Notification(payload[2] + " <" + line[3] + "> " + line[4]);
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
				if (windowName == activeWindow[2])
					redrawChannelMembers();
			} else if (subtype == Flags.TOPIC) {
				connectionData[payload[1]].channels[payload[2]].topic = line[4];
			} else if (subtype == Flags.INITNOTOPIC) {
				connectionData[payload[1]].channels[payload[2]].topic = null;
			} else if (subtype == Flags.INITTOPIC) {
				connectionData[payload[1]].channels[payload[2]].topic = line[3];
			}
		} else if (type == "MYNICK") {
			var profile = payload[1];
			var name = payload[2];
			connectionData[profile].currentNickname = name;
			if (activeWindow[0] == profile) {
				setElementText(nicknameElem, name);
				activeWindowUpdated = true;
			}
		} else if (type == "OPENWIN") {
			var windowName = payload[1] + "\n" + payload[2];
			var index = windowNames.indexOf(windowName);
			if (index == -1) {
				windowNames.push(windowName);
				windowNames.sort();
				windowData[windowName] = {
					lines: [],
					markedReadUntil: 0,
					numNewMessages: 0,
				};
				redrawWindowList();
				inputBoxElem.value = "";
				setActiveWindow(windowName);
			}
		} else if (type == "CLOSEWIN") {
			var windowName = payload[1] + "\n" + payload[2];
			var index = windowNames.indexOf(windowName);
			if (index != -1) {
				windowNames.splice(index, 1);
				delete windowData[windowName];
				redrawWindowList();
				if (windowName == activeWindow[2]) {
					inputBoxElem.value = "";
					if (windowNames.length > 0)
						setActiveWindow(windowNames[Math.min(index, windowNames.length - 1)]);
					else
						removeChildren(messageListElem);
				}
			}
		} else if (type == "MARKREAD") {
			var windowName = payload[1] + "\n" + payload[2];
			var seq = payload[3];
			windowData[windowName].markedReadUntil = seq;
			if (windowName == activeWindow[2]) {
				var lines = windowData[windowName].lines;
				var rows = messageListElem.children;
				for (var i = 0; i < lines.length; i++) {
					var row = rows[i];
					var cl = row.classList;
					if (lines[i][0] < seq) {
						cl.add("read");
						cl.remove("unread");
					} else {
						cl.add("unread");
						cl.remove("read");
					}
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
			if (windowName == activeWindow[2]) {
				for (var j = 0; j < i; j++)
					messageListElem.removeChild(messageListElem.firstChild);
				activeWindowUpdated = true;
			}
		}
	});
	
	if (activeWindowUpdated) {
		reflowMessagesTable();
		window.scrollTo(0, scrollToBottom ? document.documentElement.scrollHeight : scrollPosition);
	}
}


// Called only by submitting the input line text box.
function handleInputLine() {
	var inputStr = inputBoxElem.value;
	if (inputStr.startsWith("//")) {  // Ordinary message beginning with slash
		sendMessage(activeWindow[0], activeWindow[1], inputStr.substring(1));
		
	} else if (inputStr.startsWith("/")) {  // Command or special message
		var i = inputStr.indexOf(" ");
		if (i == -1)
			i = inputStr.length;
		var cmd = inputStr.substring(1, i).toLowerCase();
		
		if (cmd == "me" && inputStr.length - i >= 2) {
			var text = "\u0001ACTION " + inputStr.substring(4) + "\u0001";
			sendMessage(activeWindow[0], activeWindow[1], text);
			
		} else if (cmd == "query" && /^\/query [^ ]+$/i.test(inputStr)) {
			openPrivateMessagingWindow(inputStr.substring(7));
			
		} else if (cmd == "msg" && inputStr.split(" ").length >= 3) {
			var parts = split2(inputStr.substring(5));
			var target = parts[0];
			var text = parts[1];
			var profile = activeWindow[0];
			var windowName = profile + "\n" + target;
			if (windowNames.indexOf(windowName) == -1) {
				sendAction([["open-window", profile, target], ["send-line", profile, "PRIVMSG " + target + " :" + text]], clearAndEnableInput, enableInput);
			} else {
				setActiveWindow(windowName);
				sendMessage(profile, target, text);
			}
			
		} else if (cmd == "part" && inputStr.length == 5) {
			sendAction([["send-line", activeWindow[0], "PART " + activeWindow[1]]], clearAndEnableInput, enableInput);
			
		} else if ((cmd == "nick" || cmd == "join" || cmd == "part") && /^\/[a-z]+ [^ ]+$/i.test(inputStr)) {
			sendAction([["send-line", activeWindow[0], inputStr.substr(1, 5).toUpperCase() + inputStr.substring(6)]], clearAndEnableInput, enableInput);
			
		} else
			alert("Invalid command");
	
	} else {  // Ordinary message
		sendMessage(activeWindow[0], activeWindow[1], inputStr);
	}
	return false;  // To prevent the form submitting
}


function openPrivateMessagingWindow(target) {
	var profile = activeWindow[0];
	var windowName = profile + "\n" + target;
	if (windowNames.indexOf(windowName) == -1)
		sendAction([["open-window", profile, target]], null, null);
	else {
		setActiveWindow(windowName);
		inputBoxElem.value = "";
	}
}


function clearAndEnableInput() {
	inputBoxElem.value = "";
	enableInput();
}

function enableInput() {
	inputBoxElem.disabled = false;
}


// 'items' has type list<pair<str text, func onclick/null>>. Returns an event handler function.
function makeContextMenuOpener(items) {
	return function(ev) {
		closeContextMenu();
		var div = document.createElement("div");
		div.id = "menu";
		div.style.left = ev.pageX + "px";
		div.style.top  = ev.pageY + "px";
		var ul = document.createElement("ul");
		
		items.forEach(function(item) {
			var li = document.createElement("li");
			var child;
			if (item[1] == null) {
				child = document.createElement("span");
				child.className = "disabled";
			} else {
				child = document.createElement("a");
				child.href = "#";
				child.onclick = function() {
					item[1]();
					closeContextMenu();
					return false;
				};
			}
			setElementText(child, item[0]);
			li.appendChild(child);
			ul.appendChild(li);
		});
		
		div.appendChild(ul);
		div.onmousedown = function(evt) { evt.stopPropagation(); };
		document.getElementsByTagName("body")[0].appendChild(div);
		return false;
	};
}


// Deletes the context menu <div> element, if one is present.
function closeContextMenu() {
	var elem = document.getElementById("menu");
	if (elem != null)
		elem.parentNode.removeChild(elem);
}



/*---- Networking functions ----*/

// Called after login (from authenticate()) and after a severe state desynchronization (indirectly from updateState()).
// This performs an Ajax request, changes the page layout, and renders the data on screen.
function getState() {
	var xhr = new XMLHttpRequest();
	xhr.onload = function() {
		var data = JSON.parse(xhr.response);
		if (typeof data == "string") {  // Error message
			setElementText(document.getElementById("login-status"), data);
		} else {  // Good data
			loadState(data);  // Process data and update UI
			updateState();  // Start polling
		}
	};
	xhr.ontimeout = xhr.onerror = function() {
		var li = document.createElement("li");
		setElementText(li, "(Unable to connect to data provider)");
		windowListElem.appendChild(li);
	};
	xhr.open("POST", "get-state.json", true);
	xhr.responseType = "text";
	xhr.timeout = 10000;
	xhr.send(JSON.stringify({"password":password}));
}


function updateState() {
	var xhr = new XMLHttpRequest();
	xhr.onload = function() {
		if (xhr.status != 200)
			xhr.onerror();
		else {
			var data = JSON.parse(xhr.response);
			if (data != null) {  // Success
				loadUpdates(data);
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
	xhr.open("POST", "get-updates.json", true);
	xhr.responseType = "text";
	xhr.timeout = 80000;
	xhr.send(JSON.stringify({"password":password, "nextUpdateId":nextUpdateId}));
}


// Type signature: str path, list<list<val>> payload, func onload/null, func ontimeout/null. Returns nothing.
function sendAction(payload, onload, ontimeout) {
	var xhr = new XMLHttpRequest();
	if (onload != null)
		xhr.onload = onload;
	if (ontimeout != null)
		xhr.ontimeout = ontimeout;
	xhr.open("POST", "do-actions.json", true);
	xhr.responseType = "text";
	xhr.timeout = 5000;
	xhr.send(JSON.stringify({"password":password, "payload":payload}));
}


// Type signature: str profile, str target, str text. Returns nothing. The value (profile+"\n"+target) need not exist in windowNames.
function sendMessage(profile, target, text) {
	inputBoxElem.disabled = true;
	sendAction([["send-line", profile, "PRIVMSG " + target + " :" + text]], clearAndEnableInput, enableInput);
}


/*---- Simple utility functions ----*/

// Converts a Unix millisecond timestamp to a string, in the preferred format for lineDataToRowElem().
function formatDate(timestamp) {
	var d = new Date(timestamp);
	return twoDigits(d.getDate()) + "-" + DAYS_OF_WEEK[d.getDay()] + "\u00A0" +
		twoDigits(d.getHours()) + ":" + twoDigits(d.getMinutes()) + ":" + twoDigits(d.getSeconds());
}

var DAYS_OF_WEEK = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];


// Converts an integer to a two-digit string. For example, 0 -> "00", 9 -> "09", 23 -> "23".
function twoDigits(n) {
	return (n < 10 ? "0" : "") + n;
}


// Removes all children of the given DOM node.
function removeChildren(elem) {
	while (elem.firstChild != null)
		elem.removeChild(elem.firstChild);
}


// Removes all children of the given DOM node and adds a single text element containing the specified text.
function setElementText(elem, str) {
	removeChildren(elem);
	elem.appendChild(document.createTextNode(str));
}


// Splits a string by the first space into an array of two strings, or throws
// an exception if not possible. For example, split2("a b c") -> ["a", "b c"].
function split2(str) {
	var i = str.indexOf(" ");
	if (i == -1)
		throw "Cannot split";
	return [str.substr(0, i), str.substring(i + 1)];
}



/*---- Miscellaneous ----*/

// The call to init() must come last due to variables being declared and initialized.
init();
