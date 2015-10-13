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

// All variables must be null before getState() returns successfully. Thereafter, most of them are non-null.

// Type tuple<str profile, str party, str concatenated>.
// Is null if windowNames is empty, otherwise this[2] equals an entry in windowNames.
var activeWindow = null;

// Type list<str>. Length 0 or more. Each element is of the form (profile+"\n"+party).
// Elements can be in any order, and it determines the order rendered on screen.
var windowNames = null;

// Type map<str,window>. Key is an entry in windowNames. Each window has the properties
// "lines" as list<tuple<int seq, int timestamp, str line, int flags>>, "markedReadUntil" as int, "numNewMessages" as int.
var windowData = null;

// Type map<str,object>. Key is the network profile name.
var connectionData = null;

// Type int. At least 0.
var nextUpdateId = null;

// Type str. Gets set by submitting the login form, and remains unchanged after a successful getState().
var password = null;

// In milliseconds.
var retryTimeout = 1000;

const MAX_MESSAGES_PER_WINDOW = 3000;

const Flags = {
	OUTGOING: 0x1,
	NICKFLAG: 0x2,
};


/*---- Major functions ----*/

function init() {
	document.getElementById("login").getElementsByTagName("form")[0].onsubmit = authenticate;
	document.getElementById("main" ).getElementsByTagName("form")[0].onsubmit = handleInputLine;
	htmlElem.onmousedown = closeContextMenu;
	backgroundImageCss = window.getComputedStyle(htmlElem).backgroundImage;  // str: 'url("foo.png")'
	inputBoxElem.oninput = function() {
		var text = inputBoxElem.value;
		inputBoxElem.className = text.startsWith("/") && !text.startsWith("//") ? "is-command" : "";
	};
	inputBoxElem.value = "";
	passwordElem.oninput = function() {
		removeChildren(document.getElementById("login-status"));
	};
	passwordElem.focus();
}


function authenticate() {
	password = passwordElem.value;
	getState();
	return false;  // To prevent the form submitting
}


function getState() {
	var xhr = new XMLHttpRequest();
	xhr.onload = function() {
		var data = JSON.parse(xhr.response);
		if (typeof data == "string") {
			setElementText(document.getElementById("login-status"), data);
		} else {
			passwordElem.blur();
			document.getElementById("login").style.display = "none";
			document.getElementById("main").style.removeProperty("display");
			htmlElem.style.backgroundImage = "linear-gradient(rgba(255,255,255,0.97),rgba(255,255,255,0.97)), " + backgroundImageCss;
			loadState(data);
			updateState();
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


function loadState(inData) {
	nextUpdateId = inData.nextUpdateId;
	connectionData = inData.connections;
	
	windowNames = [];
	windowData = {};
	inData.windows.forEach(function(inWindow) {
		// 'inWindow' has type tuple<str profile, str party, window state>
		var windowName = inWindow[0] + "\n" + inWindow[1];
		if (windowNames.indexOf(windowName) != -1)
			throw "Duplicate window";
		windowNames.push(windowName);
		var inState = inWindow[2];
		var outState = {
			lines: inState.lines,
			markedReadUntil: inState.markedReadUntil,
			numNewMessages: 0,
		};
		var numPrefixDel = outState.lines.length - MAX_MESSAGES_PER_WINDOW;
		if (numPrefixDel > 0)
			outState.lines.splice(0, numPrefixDel);
		windowData[windowName] = outState;
	});
	
	activeWindow = null;
	windowNames.sort();
	redrawWindowList();
	if (windowNames.length > 0)
		setActiveWindow(windowNames[0]);
}


function redrawWindowList() {
	removeChildren(windowListElem);
	var prevProfile = null;
	windowNames.forEach(function(windowName) {
		// windowName has type str, and is of the form (profile+"\n"+party)
		var parts = windowName.split("\n");
		var profile = parts[0];
		var party = parts[1];
		
		if (prevProfile == null || profile != prevProfile) {
			var extrali = document.createElement("li");
			setElementText(extrali, profile);
			extrali.className = "profile";
			windowListElem.appendChild(extrali);
			prevProfile = profile;
		}
		
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


function refreshWindowSelection() {
	var windowLis = windowListElem.getElementsByTagName("li");
	for (var i = 0, j = 0; i < windowLis.length; i++) {
		if (windowLis[i].className != "profile") {
			windowLis[i].className = (activeWindow != null && windowNames[j] == activeWindow[2]) ? "selected" : "";
			j++;
		}
	}
}


function redrawChannelMembers() {
	if (activeWindow[1] in connectionData[activeWindow[0]].channels) {
		var members = connectionData[activeWindow[0]].channels[activeWindow[1]].members;
		members.sort(function(s, t) {  // Safe mutation; case-insensitive ordering
			return s.toLowerCase().localeCompare(t.toLowerCase()); });
		setElementText(memberListElem, "Channel members: " + members.join(", "));
	} else
		setElementText(memberListElem, "");
}


function setActiveWindow(name) {
	windowData[name].numNewMessages = 0;
	if ((activeWindow != null && activeWindow[2] == name) || windowNames.indexOf(name) == -1) {
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


// 'line' is a tuple; this returns a <tr> element.
function lineDataToRowElem(line) {
	// Input variables
	const sequence = line[0];
	const timestamp = line[1];
	const payload = line[2];
	const flags = line[3];
	const payloadparts = split2(payload);
	const type = payloadparts[0];
	
	// Output variables
	var who = "RAW";
	var lineElems = [];  // list<domnode>
	var quoteText = null;
	var tr = document.createElement("tr");
	
	// Take action depending on head of payload
	if (type == "PRIVMSG") {
		var subparts = split2(payloadparts[1]);
		who = subparts[0];
		var s = subparts[1];
		var mematch = ME_INCOMING_REGEX.exec(s);
		if (mematch != null)
			s = mematch[1];
		
		if ((flags & Flags.OUTGOING) != 0)
			tr.classList.add("outgoing");
		if ((flags & Flags.NICKFLAG) != 0)
			tr.classList.add("nickflag");
		quoteText = s.replace(/\t/g, " ").replace(/[\u0000-\u001F]/g, "");  // Sanitize formatting control characters
		
		if (s == "")
			lineElems.push(document.createTextNode(s));
		while (s != "") {
			var linkmatch = /(^|.*?\()(https?:\/\/[^ )]+)(.*)/.exec(s);
			if (linkmatch == null)
				linkmatch = /(^|.*? )(https?:\/\/[^ ]+)(.*)/.exec(s);
			if (linkmatch == null) {
				lineElems.push(document.createTextNode(s));
				break;
			} else {
				if (linkmatch[1].length > 0)
					lineElems.push(document.createTextNode(linkmatch[1]));
				var a = document.createElement("a");
				a.href = linkmatch[2];
				a.target = "_blank";
				a.appendChild(document.createTextNode(linkmatch[2]));
				lineElems.push(a);
				s = linkmatch[3];
			}
		}
		if (mematch != null) {
			var em = document.createElement("em");
			lineElems.forEach(function(elem) {
				em.appendChild(elem);
			});
			lineElems = [em];
			quoteText = "* " + who + " " + quoteText;
		} else {
			quoteText = "<" + who + "> " + quoteText;
		}
		
	} else if (type == "NOTICE") {
		var subparts = split2(payloadparts[1]);
		who = "(" + subparts[0] + ")";
		lineElems.push(document.createTextNode(subparts[1]));
	} else if (type == "NICK") {
		who = "*";
		var subparts = split2(payloadparts[1]);
		lineElems.push(document.createTextNode(subparts[0] + " changed their name to " + subparts[1]));
	} else if (type == "JOIN") {
		who = "*";
		lineElems.push(document.createTextNode(payloadparts[1] + " joined the channel"));
	} else if (type == "PART") {
		who = "*";
		lineElems.push(document.createTextNode(payloadparts[1] + " left the channel"));
	} else if (type == "QUIT") {
		who = "*";
		var subparts = split2(payloadparts[1]);
		lineElems.push(document.createTextNode(subparts[0] + " has quit: " + subparts[1]));
	}
	
	// Make timestamp cell
	var td = document.createElement("td");
	td.appendChild(document.createTextNode(formatDate(timestamp)));
	tr.appendChild(td);
	
	// Make nickname cell
	td = document.createElement("td");
	td.appendChild(document.createTextNode(who));
	if (who != "*" && who != "RAW")
		td.oncontextmenu = makeContextMenuOpener([["Open PM window", function() { openPrivateMessagingWindow(who); }]]);
	tr.appendChild(td);
	
	// Make message cell
	td = document.createElement("td");
	if (lineElems.length == 0)
		lineElems.push(document.createTextNode(payload));
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
	menuItems.push(["Mark read to here", function() { sendAction([["mark-read"  , activeWindow[0], activeWindow[1], sequence + 1]], null, null); }]);
	menuItems.push(["Clear to here"    , function() {
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

var ME_INCOMING_REGEX = /^\u0001ACTION (.*)\u0001$/;


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


function loadUpdates(inData) {
	nextUpdateId = inData.nextUpdateId;
	
	const scrollToBottom = inputBoxElem.getBoundingClientRect().bottom < document.documentElement.clientHeight;
	const scrollPosition = document.documentElement.scrollTop;
	var activeWindowUpdated = false;
	inData.updates.forEach(function(payload) {
		// 'payload' has type str, and has a variable number of parts delimited by '\n'
		var payloadparts = payload;
		var type = payloadparts[0];
		
		if (type == "APPEND") {
			var windowName = payloadparts[1] + "\n" + payloadparts[2];
			if (windowNames.indexOf(windowName) == -1) {
				windowNames.push(windowName);
				windowNames.sort();
				windowData[windowName] = {
					lines: [],
					markedReadUntil: 0,
					numNewMessages: 0,
				};
				redrawWindowList();
			}
			var line = payloadparts.slice(3);
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
			var subtype = split2(line[2])[0];
			if (subtype == "PRIVMSG") {
				if (windowName == activeWindow[2] && (line[3] & Flags.OUTGOING) != 0)
					windowData[windowName].numNewMessages = 0;
				else
					windowData[windowName].numNewMessages++;
				redrawWindowList();
			} else if (subtype == "JOIN" || subtype == "PART" || subtype == "QUIT" || subtype == "NICK") {
				var members = connectionData[activeWindow[0]].channels[activeWindow[1]].members;
				var name = split2(line[2])[1];
				if (windowName == activeWindow[2]) {
					if (subtype == "JOIN" && members.indexOf(name) == -1) {
						members.push(name);
						redrawChannelMembers();
					} else if (subtype == "PART" && members.indexOf(name) != -1) {
						members.splice(members.indexOf(name), 1);
						redrawChannelMembers();
					} else if (subtype == "QUIT" && members.indexOf(split2(name)[0]) != -1) {
						members.splice(members.indexOf(split2(name)[0]), 1);
						redrawChannelMembers();
					} else if (subtype == "NICK") {
						name = split2(name);
						if (members.indexOf(name[0]) != -1)
							members.splice(members.indexOf(name[0]), 1);
						if (members.indexOf(name[1]) == -1)
							members.push(name[1]);
						redrawChannelMembers();
					}
				}
			}
		} else if (type == "MYNICK") {
			var profile = payloadparts[1];
			var name = payloadparts[2];
			connectionData[profile].currentNickname = name;
			if (activeWindow[0] == profile) {
				setElementText(nicknameElem, name);
				activeWindowUpdated = true;
			}
		} else if (type == "OPENWIN") {
			var windowName = payloadparts[1] + "\n" + payloadparts[2];
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
			var windowName = payloadparts[1] + "\n" + payloadparts[2];
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
			var windowName = payloadparts[1] + "\n" + payloadparts[2];
			var seq = payloadparts[3];
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
			var windowName = payloadparts[1] + "\n" + payloadparts[2];
			var seq = payloadparts[3];
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


function handleInputLine() {
	var text = inputBoxElem.value;
	if (text.startsWith("//")) {  // Ordinary message beginning with slash
		sendMessage(activeWindow[0], activeWindow[1], text.substring(1));
		
	} else if (text.startsWith("/")) {  // Command or special message
		var i = text.indexOf(" ");
		if (i == -1)
			i = text.length;
		var cmd = text.substring(1, i).toLowerCase();
		
		if (cmd == "me" && text.length - i >= 2) {
			var text = "\u0001ACTION " + text.substring(4) + "\u0001";
			sendMessage(activeWindow[0], activeWindow[1], text);
			
		} else if (cmd == "query" && /^\/query [^ ]+$/i.test(text)) {
			openPrivateMessagingWindow(text.substring(7));
			
		} else if (cmd == "msg" && text.split(" ").length >= 3) {
			var parts = split2(text.substring(5));
			var target = parts[0];
			var text = parts[1];
			var profile = activeWindow[0];
			var windowName = profile + "\n" + target;
			if (windowNames.indexOf(windowName) == -1) {
				sendAction([["open-window", profile, target], ["send-line", profile, "PRIVMSG " + target + " :" + text]],
					function() {
						inputBoxElem.value = "";
						inputBoxElem.disabled = false;
					},
					function() {
						inputBoxElem.disabled = false;
					});
			} else {
				setActiveWindow(windowName);
				sendMessage(profile, target, text);
			}
			
		} else if (cmd == "part" && text.length == 5) {
			sendAction([["send-line", activeWindow[0], "PART " + activeWindow[1]]], clearAndEnableInput, enableInput);
			
		} else if ((cmd == "nick" || cmd == "join" || cmd == "part") && /^\/[a-z]+ [^ ]+$/i.test(text)) {
			sendAction([["send-line", activeWindow[0], text.substr(1, 5).toUpperCase() + text.substring(6)]], clearAndEnableInput, enableInput);
			
		} else
			alert("Invalid command");
	
	} else {  // Ordinary message
		sendMessage(activeWindow[0], activeWindow[1], text);
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


function sendMessage(profile, target, text) {
	inputBoxElem.disabled = true;
	sendAction([["send-line", profile, "PRIVMSG " + target + " :" + text]],
		function() {
			inputBoxElem.value = "";
			inputBoxElem.disabled = false;
		},
		function() {
			inputBoxElem.disabled = false;
		});
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


function closeContextMenu() {
	var elem = document.getElementById("menu");
	if (elem != null)
		elem.parentNode.removeChild(elem);
}


/*---- Simple utility functions ----*/

function formatDate(timestamp) {
	var d = new Date(timestamp);
	return twoDigits(d.getDate()) + "-" + DAYS_OF_WEEK[d.getDay()] + "\u00A0" +
		twoDigits(d.getHours()) + ":" + twoDigits(d.getMinutes()) + ":" + twoDigits(d.getSeconds());
}

var DAYS_OF_WEEK = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];


function twoDigits(n) {
	if (n < 10)
		return "0" + n;
	else
		return "" + n;
}


function removeChildren(elem) {
	while (elem.firstChild != null)
		elem.removeChild(elem.firstChild);
}


function setElementText(elem, str) {
	removeChildren(elem);
	elem.appendChild(document.createTextNode(str));
}


function split2(str) {
	var i = str.indexOf(" ");
	if (i == -1)
		throw "Cannot split";
	return [str.substr(0, i), str.substring(i + 1)];
}


/*---- Miscellaneous ----*/

// The call to init() must come last due to variables being declared and initialized.
init();
