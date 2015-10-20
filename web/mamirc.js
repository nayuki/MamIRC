"use strict";


/*---- Global variables ----*/

// Document nodes (elements)
const windowListElem  = document.getElementById("window-list");
const messageListElem = document.getElementById("message-list");
const memberListContainerElem = document.getElementById("member-list-container");
const memberListElem  = document.getElementById("member-list");
const failedCommandsContainerElem = document.getElementById("failed-commands-container");
const failedCommandsElem = document.getElementById("failed-commands");
const inputBoxElem    = document.getElementById("input-box");
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

// Type bool. Value is set by submitting the login form.
var optimizeMobile = null;

// In milliseconds. This value changes during execution depending on successful/failed requests.
var retryTimeout = 1000;


/* Miscellaneous values */

// Configurable parameter. Used by getState().
var maxMessagesPerWindow = 3000;

// Type map<str,int>. It is a collection of integer constants, defined in Java code to avoid duplication. Values are set by getState().
var Flags = null;

// Type tuple<int begin, int end, str prefix, str name> or null.
var prevTabCompletion = null;



/*---- User interface functions ----*/

// Called once after the script and page are loaded.
function init() {
	document.getElementById("login").getElementsByTagName("form")[0].onsubmit = authenticate;
	document.getElementById("main" ).getElementsByTagName("form")[0].onsubmit = handleInputLine;
	removeChildren(failedCommandsElem);
	failedCommandsContainerElem.getElementsByTagName("a")[0].onclick = function() {
		failedCommandsContainerElem.style.display = "none";
		removeChildren(failedCommandsElem);
		return false;
	};
	backgroundImageCss = window.getComputedStyle(htmlElem).backgroundImage;  // str: 'url("foo.png")'
	Notification.requestPermission();
	
	htmlElem.onmousedown = closeContextMenu;
	inputBoxElem.oninput = function() {
		// Change style of text box based if a '/command' is being typed
		var text = inputBoxElem.value;
		inputBoxElem.className = text.startsWith("/") && !text.startsWith("//") ? "is-command" : "";
	};
	inputBoxElem.onblur = clearTabCompletion;
	inputBoxElem.onkeypress = function(ev) {
		if (ev.keyCode == 9) {
			doInputTabCompletion();
			return false;
		} else {
			clearTabCompletion();
			return true;
		}
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
	optimizeMobile = document.getElementById("optimize-mobile").checked;
	if (optimizeMobile)
		maxMessagesPerWindow = 500;
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
		var prevTimestamp = 0;
		inState.lines.forEach(function(line) {
			line[2] += prevTimestamp;  // Delta decoding
			prevTimestamp = line[2];
		});
		var outState = createBlankWindow();
		for (var key in inState)
			outState[key] = inState[key];
		windowData[windowName] = outState;
	});
	activeWindow = null;
	windowNames.sort();
	
	// Update UI elements
	passwordElem.value = "";
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
	windowNames.forEach(function(windowName) {
		// windowName has type str, and is of the form (profile+"\n"+party)
		var parts = windowName.split("\n");
		var profile = parts[0];
		var party = parts[1];
		
		// Create the anchor element
		var a = document.createElement("a");
		var s = party != "" ? party : profile;
		var n = windowData[windowName].numNewMessages;
		if (n > 0)
			s += " (" + n + ")";
		setElementText(a, s);
		a.href = "#";
		a.onclick = function() {
			setActiveWindow(windowName);
			return false;
		};
		var menuItems = [];
		if (windowData[windowName].isMuted)
			menuItems.push(["Unmute window", function() { windowData[windowName].isMuted = false; }]);
		else {
			menuItems.push(["Mute window", function() {
				windowData[windowName].isMuted = true;
				windowData[windowName].numNewMessages = 0;
				redrawWindowList();
			}]);
		}
		menuItems.push(["Close window", function() { sendAction([["close-window", profile, party]], null, null); }]);
		a.oncontextmenu = makeContextMenuOpener(menuItems);
		
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
	if (activeWindow != null)
		document.title = (totalNewMsg > 0 ? "(" + totalNewMsg + ") " : "") + (activeWindow[1] != "" ? activeWindow[1] + " - " : "") + activeWindow[0] + " - MamIRC";
}


// Refreshes the selection class of each window <li> element based on the states of windowNames and activeWindow.
// This assumes that the list of HTML elements is already synchronized with windowNames.
function refreshWindowSelection() {
	if (activeWindow == null)
		return;
	var windowLis = windowListElem.getElementsByTagName("li");
	windowNames.forEach(function(name, i) {
		if (name == activeWindow[2])
			windowLis[i].classList.add("selected");
		else
			windowLis[i].classList.remove("selected");
	});
}


// Refreshes the channel members text element based on the states of
// connectionData[profileName].channels[channelName].members and activeWindow.
function redrawChannelMembers() {
	removeChildren(memberListElem);
	var profile = activeWindow[0], party = activeWindow[1];
	if (profile in connectionData && party in connectionData[profile].channels) {
		var members = connectionData[profile].channels[party].members;
		members.sort(function(s, t) {  // Safe mutation; case-insensitive ordering
			return s.toLowerCase().localeCompare(t.toLowerCase());
		});
		members.forEach(function(name) {
			var li = document.createElement("li");
			setElementText(li, name);
			li.oncontextmenu = makeContextMenuOpener([["Open PM window", function() { openPrivateMessagingWindow(name, null); }]]);
			memberListElem.appendChild(li);
		});
		memberListContainerElem.style.removeProperty("display");
	} else
		memberListContainerElem.style.display = "none";
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
		quoteText = s.replace(/\t/g, " ").replace(REMOVE_FORMATTING_REGEX, "");
		lineElems = fancyTextToElems(s);
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
		who = "\u2192";  // Rightwards arrow
		lineElems.push(document.createTextNode(payload[0] + " joined the channel"));
	} else if (type == Flags.PART) {
		who = "\u2190";  // Leftwards arrow
		lineElems.push(document.createTextNode(payload[0] + " left the channel"));
	} else if (type == Flags.QUIT) {
		who = "\u2190";  // Leftwards arrow
		lineElems.push(document.createTextNode(payload[0] + " has quit: "));
		fancyTextToElems(payload[1]).forEach(function(elem) {
			lineElems.push(elem);
		});
	} else if (type == Flags.KICK) {
		who = "\u2190";  // Leftwards arrow
		lineElems.push(document.createTextNode(payload[1] + " was kicked by " + payload[0] + ": "));
		fancyTextToElems(payload[2]).forEach(function(elem) {
			lineElems.push(elem);
		});
	} else if (type == Flags.TOPIC) {
		lineElems.push(document.createTextNode(payload[0] + " set the channel topic to: "));
		fancyTextToElems(payload[1]).forEach(function(elem) {
			lineElems.push(elem);
		});
	} else if (type == Flags.INITNOTOPIC) {
		lineElems.push(document.createTextNode("No channel topic is set"));
	} else if (type == Flags.INITTOPIC) {
		lineElems.push(document.createTextNode("The channel topic is: "));
		fancyTextToElems(payload[0]).forEach(function(elem) {
			lineElems.push(elem);
		});
	} else if (type == Flags.SERVERREPLY) {
		who = "*";
		lineElems.push(document.createTextNode(payload[1]));
	} else if (type == Flags.NAMES) {
		who = "*";
		var text = "Users in channel: ";
		for (var i = 0; i < payload.length; i++) {
			if (i > 0)
				text += ", ";
			text += payload[i];
		}
		lineElems.push(document.createTextNode(text));
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
		td.oncontextmenu = makeContextMenuOpener([["Open PM window", function() { openPrivateMessagingWindow(who, null); }]]);
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
const REMOVE_FORMATTING_REGEX = /[\u0002\u000F\u0016\u001D\u001F]|\u0003(?:\d{1,2}(?:,\d{1,2})?)?/g;


// Given a string with possible IRC formatting control codes and plain text URLs,
// this returns an array of DOM nodes representing text with formatting and anchor links.
function fancyTextToElems(str) {
	// Take fast path if string contains no formatting or potential URLs
	if (!SPECIAL_FORMATTING_REGEX.test(str))
		return [document.createTextNode(str)];
	
	// These formatting state variables are declared now, referenced in closures in the next section of code
	// (but not read or written), and mutated in the next next section (when the closures are called)
	var bold = false;
	var italic = false;
	var underline = false;
	var background = 0;
	var foreground = 1;
	
	// Split the string into literal text and formatting function objects
	var parts = [];  // Array of strings and functions
	var start = 0;
	var end = 0;
	while (end < str.length) {
		var ch = str.charCodeAt(end);
		var action;
		var newStart;
		if (ch == 0x02) {
			action = function() { bold = !bold; };
			newStart = end + 1;
		} else if (ch == 0x1D) {
			action = function() { italic = !italic; };
			newStart = end + 1;
		} else if (ch == 0x1F) {
			action = function() { underline = !underline; }
			newStart = end + 1;
		} else if (ch == 0x16) {  // Reverse
			action = function() {
				var temp = foreground;
				foreground = background;
				background = temp;
			};
			newStart = end + 1;
		} else if (ch == 0x0F) {  // Plain
			action = function() {
				bold = false;
				italic = false;
				underline = false;
				background = 0;
				foreground = 1;
			};
			newStart = end + 1;
		} else if (ch == 0x03) {  // Color code
			var match = COLOR_CODE_REGEX.exec(str.substring(end));
			var newfg = match[1] != undefined ? parseInt(match[1], 10) : 1;
			var newbg = match[2] != undefined ? parseInt(match[2], 10) : 0;
			action = (function(fg, bg) {
				return function() {
					if (fg < TEXT_COLORS.length) foreground = fg;
					if (bg < TEXT_COLORS.length) background = bg;
				};
			})(newfg, newbg);
			newStart = end + match[0].length;
		} else {
			action = null;
			newStart = -1;
			end++;
		}
		// Execute this iff the 'else' clause wasn't executed
		if (newStart != -1) {
			parts.push(str.substring(start, end));
			parts.push(action);  // action is a function, not null
			start = newStart;
			end = newStart;
		}
	}
	if (start < end)
		parts.push(str.substring(start, end));
	
	// Parse URLs in each literal string, and apply formatting functions
	var result = [];
	parts.forEach(function(part) {
		if (typeof part == "string") {
			while (part != "") {
				var match = URL_REGEX0.exec(part);
				if (match == null)
					match = URL_REGEX1.exec(part);
				var textEnd = match != null ? match[1].length : part.length;
				if (textEnd > 0) {
					var elem = document.createTextNode(part.substr(0, textEnd));
					if (bold || italic || underline || background != 0 || foreground != 1) {
						var span = document.createElement("span");
						if (bold) span.style.fontWeight = "bold";
						if (italic) span.style.fontStyle = "italic";
						if (underline) span.style.textDecoration = "underline";
						if (background != 0)
							span.style.backgroundColor = TEXT_COLORS[background];
						if (foreground != 1)
							span.style.color = TEXT_COLORS[foreground];
						span.appendChild(elem);
						elem = span;
					}
					result.push(elem);
				}
				if (match == null)
					break;
				var a = document.createElement("a");
				a.href = match[2];
				a.target = "_blank";
				setElementText(a, match[2]);
				result.push(a);
				part = part.substring(match[0].length);
			}
		} else if (typeof part == "function")
			part();
	});
	
	// Epilog
	if (result.length == 0)  // Prevent having an empty <td> to avoid style/display problems
		result.push(document.createTextNode(""));
	return result;
}

const SPECIAL_FORMATTING_REGEX = /[\u0002\u0003\u000F\u0016\u001D\u001F]|https?:\/\//;
const COLOR_CODE_REGEX = /^\u0003(?:(\d{1,2})(?:,(\d{1,2}))?)?/;
const URL_REGEX0 = /^(|.*? )(https?:\/\/[^ ]+)/;
const URL_REGEX1 = /^(.*?\()(https?:\/\/[^ ()]+)/;
const TEXT_COLORS = [
	"#FFFFFF", "#000000", "#00007F", "#009300",
	"#FF0000", "#7F0000", "#9C009C", "#FC7F00",
	"#FFFF00", "#00FC00", "#009393", "#00FFFF",
	"#0000FC", "#FF00FF", "#7F7F7F", "#D2D2D2",
];


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
				windowData[windowName] = createBlankWindow();
				redrawWindowList();
				newWindow = true;
			}
			var line = payload.slice(3);
			var lines = windowData[windowName].lines;
			lines.push(line);
			var numPrefixDel = Math.max(lines.length - maxMessagesPerWindow, 0);
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
				else if (!windowData[windowName].isMuted)
					windowData[windowName].numNewMessages++;
				redrawWindowList();
				if (!windowData[windowName].isMuted) {
					if (!payload[2].startsWith("#") && !payload[2].startsWith("&") && (newWindow || (line[1] & Flags.NICKFLAG) != 0)) {
						// New private messaging window popped open, or nickflagged in one
						var notif;
						var match = ME_INCOMING_REGEX.exec(line[4]);
						if (match == null)
							notif = new Notification("<" + line[3] + "> " + line[4].replace(REMOVE_FORMATTING_REGEX, ""));
						else
							notif = new Notification(line[3] + " " + match[1].replace(REMOVE_FORMATTING_REGEX, ""));
						notif.onclick = function() { setActiveWindow(windowName); };
					} else if ((line[1] & Flags.NICKFLAG) != 0) {
						var notif;
						var match = ME_INCOMING_REGEX.exec(line[4]);
						if (match == null)
							notif = new Notification(payload[2] + " <" + line[3] + "> " + line[4].replace(REMOVE_FORMATTING_REGEX, ""));
						else
							notif = new Notification(payload[2] + " " + line[3] + " " + match[1].replace(REMOVE_FORMATTING_REGEX, ""));
						notif.onclick = function() { setActiveWindow(windowName); };
					}
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
			} else if (subtype == Flags.SERVERREPLY) {
				if (!windowData[windowName].isMuted) {
					windowData[windowName].numNewMessages++;
					redrawWindowList();
				}
			}
		} else if (type == "MYNICK") {
			var profile = payload[1];
			var name = payload[2];
			connectionData[profile].currentNickname = name;
			if (activeWindow != null && activeWindow[0] == profile) {
				setElementText(nicknameElem, name);
				activeWindowUpdated = true;
			}
		} else if (type == "JOINED") {
			connectionData[payload[1]].channels[payload[2]] = {
				members: [],
				topic: null,
			};
		} else if (type == "PARTED" || type == "KICKED") {
			delete connectionData[payload[1]].channels[payload[2]];
			if (activeWindow[0] == payload[1] && activeWindow[1] == payload[2])
				redrawChannelMembers();
		} else if (type == "SETCHANNELMEMBERS") {
			connectionData[payload[1]].channels[payload[2]].members = payload[3];
			if (activeWindow[0] == payload[1] && activeWindow[1] == payload[2])
				redrawChannelMembers();
		} else if (type == "OPENWIN") {
			var windowName = payload[1] + "\n" + payload[2];
			var index = windowNames.indexOf(windowName);
			if (index == -1) {
				windowNames.push(windowName);
				windowNames.sort();
				windowData[windowName] = createBlankWindow();
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
	if (activeWindow == null || inputStr == "")
		return false;
	var onerror = function() {
		failedCommandsContainerElem.style.removeProperty("display");
		var li = document.createElement("li");
		setElementText(li, inputStr);
		failedCommandsElem.appendChild(li);
	};
	
	if (!inputStr.startsWith("/") || inputStr.startsWith("//")) {  // Ordinary message
		if (inputStr.startsWith("//"))  // Ordinary message beginning with slash
			inputStr = inputStr.substring(1);
		sendMessage(activeWindow[0], activeWindow[1], inputStr, onerror);
		
	} else {  // Command or special message
		// The user input command is case-insensitive. The command sent to the server will be in uppercase.
		var parts = inputStr.split(" ");
		var cmd = parts[0].toLowerCase();
		
		if (cmd == "/msg" && parts.length >= 3) {
			var profile = activeWindow[0];
			var party = parts[1];
			var windowName = profile + "\n" + party;
			var text = nthRemainingPart(inputStr, 2);
			if (windowNames.indexOf(windowName) == -1) {
				sendAction([["open-window", profile, party], ["send-line", profile, "PRIVMSG " + party + " :" + text]], null, onerror);
			} else {
				setActiveWindow(windowName);
				sendMessage(profile, party, text, onerror);
			}
		} else if (cmd == "/me" && parts.length >= 2) {
			sendMessage(activeWindow[0], activeWindow[1], "\u0001ACTION " + nthRemainingPart(inputStr, 1) + "\u0001", onerror);
		} else if (cmd == "/part" && parts.length == 1) {
			sendAction([["send-line", activeWindow[0], "PART " + activeWindow[1]]], null, onerror);
		} else if (cmd == "/query" && parts.length == 2) {
			openPrivateMessagingWindow(parts[1], onerror);
		} else if ((cmd == "/join" || cmd == "/nick" || cmd == "/part") && parts.length == 2) {
			sendAction([["send-line", activeWindow[0], cmd.substring(1).toUpperCase() + " " + parts[1]]], null, onerror);
		} else if (cmd == "/topic" && parts.length >= 2) {
			sendAction([["send-line", activeWindow[0], "TOPIC " + activeWindow[1] + " :" + nthRemainingPart(inputStr, 1)]], null, onerror);
		} else if (cmd == "/kick" && parts.length >= 2) {
			var reason = parts.length == 2 ? "" : nthRemainingPart(inputStr, 2);
			sendAction([["send-line", activeWindow[0], "KICK " + activeWindow[1] + " " + parts[1] + " :" + reason]], null, onerror);
		} else {
			alert("Invalid command");
			return false;  // Don't clear the text box
		}
	}
	inputBoxElem.value = "";
	return false;  // To prevent the form submitting
}


function openPrivateMessagingWindow(target, onerror) {
	var profile = activeWindow[0];
	var windowName = profile + "\n" + target;
	if (windowNames.indexOf(windowName) == -1)
		sendAction([["open-window", profile, target]], null, onerror);
	else {
		setActiveWindow(windowName);
		inputBoxElem.value = "";
	}
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


function doInputTabCompletion() {
	do {  // Simulate goto
		if (document.activeElement != inputBoxElem)
			break;
		var index = inputBoxElem.selectionStart;
		if (index != inputBoxElem.selectionEnd)
			break;
		if (activeWindow == null)
			break;
		var profile = activeWindow[0];
		var party = activeWindow[1];
		if (!(profile in connectionData) || !(party in connectionData[profile].channels))
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
		
		var candidates = connectionData[profile].channels[party].members.filter(function(name) {
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

const TAB_COMPLETION_REGEX = /^(|.* )([^ ]*)$/;


function clearTabCompletion() {
	prevTabCompletion = null;
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
	xhr.send(JSON.stringify({"maxMessagesPerWindow":maxMessagesPerWindow, "password":password}));
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
function sendMessage(profile, target, text, onerror) {
	sendAction([["send-line", profile, "PRIVMSG " + target + " :" + text]], null, onerror);
}


/*---- Simple utility functions ----*/

function createBlankWindow() {
	return {
		lines: [],
		markedReadUntil: 0,
		numNewMessages: 0,
		isMuted: false,
	};
}


// Converts a Unix millisecond timestamp to a string, in the preferred format for lineDataToRowElem().
function formatDate(timestamp) {
	var d = new Date(timestamp);
	if (!optimizeMobile) {
		return twoDigits(d.getDate()) + "-" + DAYS_OF_WEEK[d.getDay()] + "\u00A0" +
			twoDigits(d.getHours()) + ":" + twoDigits(d.getMinutes()) + ":" + twoDigits(d.getSeconds());
	} else {
		return DAYS_OF_WEEK[d.getDay()] + "\u00A0" + twoDigits(d.getHours()) + ":" + twoDigits(d.getMinutes());
	}
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


// Finds the first n spaces in the string and returns the rest of the string after the last space found.
// For example: nthRemainingPart("a b c", 0) -> "a b c"; nthRemainingPart("a b c", 1) -> "b c"; nthRemainingPart("a b c", 3) -> exception.
function nthRemainingPart(s, n) {
	var j = 0;
	for (var i = 0; i < n; i++) {
		j = s.indexOf(" ", j);
		if (j == -1)
			throw "Space not found";
		j++;
	}
	return s.substring(j);
}



/*---- Miscellaneous ----*/

// The call to init() must come last due to variables being declared and initialized.
init();
