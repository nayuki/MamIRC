"use strict";


/*---- Global variables ----*/

// Document nodes (elements)
var windowListElem  = document.getElementById("window-list");
var messageListElem = document.getElementById("message-list");
var inputBoxElem    = document.getElementById("input-box");
var channelElem     = document.getElementById("channel");
var nicknameElem    = document.getElementById("nickname");
var passwordElem    = document.getElementById("password");

// Main state
var activeWindow     = null;  // Tuple<String, String, String>
var windowNames      = null;  // List<String>
var windowMessages   = null;  // Map<String, List<Tuple<Integer, String>>>
var windowMarkedRead = null;  // Map<String, Integer>
var currentNicknames = null;  // Map<String, String>
var nextUpdateId     = null;  // Integer
var password         = null;  // String

var MAX_MESSAGES_PER_WINDOW = 3000;


/*---- Major functions ----*/

function init() {
	document.getElementById("login").getElementsByTagName("form")[0].onsubmit = authenticate;
	document.getElementById("main" ).getElementsByTagName("form")[0].onsubmit = handleInputLine;
	document.documentElement.onmousedown = closeContextMenu;
	inputBoxElem.oninput = function() {
		var text = inputBoxElem.value;
		inputBoxElem.className = text.startsWith("/") && !text.startsWith("//") ? "is-command" : "";
	};
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
	currentNicknames = {};
	for (var profileName in inData.connections)
		currentNicknames[profileName] = inData.connections[profileName].currentNickname;
	
	windowNames = [];
	windowMessages = {};
	windowMarkedRead = {};
	inData.windows.forEach(function(winTuple) {
		var windowName = winTuple[0] + "\n" + winTuple[1];
		if (windowNames.indexOf(windowName) != -1)
			throw "Duplicate window";
		windowNames.push(windowName);
		var winState = winTuple[2];
		var lines = winState.lines;
		if (lines.length > MAX_MESSAGES_PER_WINDOW)
			lines = lines.slice(lines.length - MAX_MESSAGES_PER_WINDOW);  // Take suffix
		windowMessages[windowName] = lines;
		windowMarkedRead[windowName] = winState.markedReadUntil;
	});
	
	activeWindow = null;
	windowNames.sort();
	redrawWindowList();
	if (windowNames.length > 0) {
		setActiveWindow(windowNames[0]);
		window.scrollTo(0, document.documentElement.scrollHeight);
	}
}


function redrawWindowList() {
	removeChildren(windowListElem);
	windowNames.forEach(function(windowName) {
		// windowName has type str, and is of the form (profile+"\n"+party)
		var a = document.createElement("a");
		var parts = windowName.split("\n");
		setElementText(a, parts[1] + " (" + parts[0] + ")");
		a.href = "#";
		a.onclick = (function(name) {
			return function() {
				setActiveWindow(name);
				return false;
			};
		})(windowName);
		a.oncontextmenu = (function(profile, target) {
			return function(ev) {
				openContextMenu(ev.pageX, ev.pageY, [["Close window", function() { closeWindow(profile, target); }]]);
				return false;
			};
		})(parts[0], parts[1]);
		
		var li = document.createElement("li");
		li.className = (activeWindow != null && windowName == activeWindow[2]) ? "selected" : "";
		li.appendChild(a);
		windowListElem.appendChild(li);
	});
}


function setActiveWindow(name) {
	if (activeWindow != null && activeWindow[2] == name)
		return;
	
	activeWindow = name.split("\n").concat(name);
	setElementText(nicknameElem, currentNicknames[activeWindow[0]]);
	setElementText(channelElem, activeWindow[1]);
	document.title = activeWindow[1] + " - " + activeWindow[0] + " - MamIRC";
	
	var windowLis = windowListElem.getElementsByTagName("li");
	for (var i = 0; i < windowLis.length; i++)
		windowLis[i].className = windowNames[i] == name ? "selected" : "";
	
	removeChildren(messageListElem);
	var messages = windowMessages[name];
	messages.forEach(function(msg) {
		messageListElem.appendChild(lineDataToRowElem(msg, name));
	});
	reflowMessagesTable();
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


// 'msg' is a length-2 array of int timestamp, string line.
function lineDataToRowElem(msg, windowName) {
	var who = "RAW";  // String
	var lineElems = [];  // Array of DOM nodes
	var parts = split2(msg[2]);
	var rowClass = "";
	var quoteText = null;
	var type = parts[0];
	
	// Take action depending on head of payload
	if (type == "PRIVMSG") {
		var subparts = split2(parts[1]);
		who = subparts[0];
		var s = subparts[1];
		var mematch = ME_INCOMING_REGEX.exec(s);
		if (mematch != null)
			s = mematch[1];
		
		var flags = msg[3];
		if ((flags & 0x1) != 0)
			rowClass += "outgoing ";
		if ((flags & 0x2) != 0)
			rowClass += "nickflag ";
		quoteText = s.replace(/\t/g, " ").replace(/[\u0000-\u001F]/g, "");  // Sanitize formatting control characters
		
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
		var subparts = split2(parts[1]);
		who = "(" + subparts[0] + ")";
		lineElems.push(document.createTextNode(subparts[1]));
	} else if (type == "NICK") {
		who = "*";
		var subparts = split2(parts[1]);
		lineElems.push(document.createTextNode(subparts[0] + " changed their name to " + subparts[1]));
	} else if (type == "JOIN") {
		who = "*";
		lineElems.push(document.createTextNode(parts[1] + " joined the channel"));
	} else if (type == "PART") {
		who = "*";
		lineElems.push(document.createTextNode(parts[1] + " left the channel"));
	} else if (type == "QUIT") {
		who = "*";
		var subparts = split2(parts[1]);
		lineElems.push(document.createTextNode(subparts[0] + " has quit: " + subparts[1]));
	}
	if (msg[0] < windowMarkedRead[windowName])
		rowClass += "read ";
	
	// Make timestamp cell
	var tr = document.createElement("tr");
	var td = document.createElement("td");
	td.appendChild(document.createTextNode(formatDate(msg[1])));
	tr.appendChild(td);
	
	// Make nickname cell
	td = document.createElement("td");
	td.appendChild(document.createTextNode(who));
	if (who != "*" && who != "RAW") {
		td.oncontextmenu = function(ev) {
			openContextMenu(ev.pageX, ev.pageY, [["Open PM window", function() { openPrivateMessagingWindow(who); }]]);
			return false;
		};
	}
	tr.appendChild(td);
	
	// Make message cell
	td = document.createElement("td");
	if (lineElems.length == 0)
		lineElems.push(document.createTextNode(msg[2]));
	lineElems.forEach(function(elem) {
		td.appendChild(elem);
	});
	var menuItems = [];
	if (quoteText != null) {
		menuItems.push(["Quote text", function() {
			inputBoxElem.value = quoteText;
			inputBoxElem.focus();
			inputBoxElem.selectionStart = inputBoxElem.selectionEnd = quoteText.length;
		}]);
	}
	menuItems.push(["Mark read to here", function() { markRead(msg[0] + 1); }]);
	menuItems.push(["Clear to here", function() { clearLines(msg[0] + 1); }]);
	td.oncontextmenu = function(ev) {
		openContextMenu(ev.pageX, ev.pageY, menuItems);
		return false;
	};
	tr.appendChild(td);
	
	// Finishing touches
	if (rowClass != "")
		tr.className = rowClass;
	return tr;
}

var ME_INCOMING_REGEX = /^\u0001ACTION (.*)\u0001$/;


function updateState() {
	var xhr = new XMLHttpRequest();
	xhr.onload = function() {
		if (xhr.status != 200)
			xhr.onerror();
		else {
			loadUpdates(JSON.parse(xhr.response));
			updateState();
		}
	};
	xhr.ontimeout = xhr.onerror = function() {
		setTimeout(updateState, 60000);
	};
	xhr.open("POST", "get-updates.json", true);
	xhr.responseType = "text";
	xhr.timeout = 80000;
	xhr.send(JSON.stringify({"password":password, "nextUpdateId":nextUpdateId}));
}


function loadUpdates(inData) {
	nextUpdateId = inData.nextUpdateId;
	
	var scrollToBottom = inputBoxElem.getBoundingClientRect().bottom < document.documentElement.clientHeight;
	var scrollPosition = document.documentElement.scrollTop;
	var activeWindowUpdated = false;
	inData.updates.forEach(function(payload) {
		var parts = payload.split("\n");
		var type = parts[0];
		if (type == "APPEND") {
			var windowName = parts[1] + "\n" + parts[2];
			if (windowNames.indexOf(windowName) == -1) {
				windowMessages[windowName] = [];
				windowNames.push(windowName);
				windowNames.sort();
				redrawWindowList();
			}
			var msg = [parseInt(parts[3], 10), parseInt(parts[4], 10), parts[5], parseInt(parts[6])];
			var messages = windowMessages[windowName];
			messages.push(msg);
			if (messages.length > MAX_MESSAGES_PER_WINDOW)
				windowMessages[windowName] = messages.slice(messages.length - MAX_MESSAGES_PER_WINDOW);
			if (windowName == activeWindow[2]) {
				messageListElem.appendChild(lineDataToRowElem(msg, windowName));
				while (messageListElem.childNodes.length > MAX_MESSAGES_PER_WINDOW)
					messageListElem.removeChild(messageListElem.firstChild);
				activeWindowUpdated = true;
			}
		} else if (type == "MYNICK") {
			currentNicknames[parts[1]] = parts[2];
			if (activeWindow[0] == parts[1]) {
				setElementText(nicknameElem, parts[2]);
				activeWindowUpdated = true;
			}
		} else if (type == "OPENWIN") {
			var windowName = parts[1] + "\n" + parts[2];
			var index = windowNames.indexOf(windowName);
			if (index == -1) {
				windowNames.push(windowName);
				windowMessages[windowName] = [];
				windowNames.sort();
				redrawWindowList();
				inputBoxElem.value = "";
				setActiveWindow(windowName);
			}
		} else if (type == "CLOSEWIN") {
			var windowName = parts[1] + "\n" + parts[2];
			var index = windowNames.indexOf(windowName);
			if (index != -1) {
				windowNames.splice(index, 1);
				delete windowMessages[windowName];
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
			var windowName = parts[1] + "\n" + parts[2];
			var seq = parseInt(parts[3], 10);
			windowMarkedRead[windowName] = seq;
			if (windowName == activeWindow[2]) {
				var lines = windowMessages[windowName];
				var rows = messageListElem.children;
				for (var i = 0; i < lines.length; i++) {
					var row = rows[i];
					var markread = lines[i][0] < seq;
					var classParts = row.className.split(" ");
					var j = classParts.indexOf("read");
					if (markread && j == -1)
						row.className += "read ";
					else if (!markread && j != -1) {
						classParts.splice(j, 1);
						row.className = classParts.join(" ");
					}
				}
				activeWindowUpdated = true;
			}
		} else if (type == "CLEARLINES") {
			var windowName = parts[1] + "\n" + parts[2];
			var seq = parseInt(parts[3], 10);
			var lines = windowMessages[windowName];
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
			if (windowNames.indexOf(windowName) == -1)
				openWindow(profile, target, function() { sendMessage(profile, target, text); });
			else {
				setActiveWindow(windowName);
				sendMessage(profile, target, text);
			}
			
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
		openWindow(profile, target, null);
	else {
		setActiveWindow(windowName);
		inputBoxElem.value = "";
	}
}


function sendMessage(profile, target, text) {
	inputBoxElem.disabled = true;
	var xhr = new XMLHttpRequest();
	xhr.onload = function() {
		inputBoxElem.value = "";
		inputBoxElem.disabled = false;
	};
	xhr.ontimeout = xhr.onerror = function() {
		inputBoxElem.disabled = false;
	};
	xhr.open("POST", "send-message.json", true);
	xhr.responseType = "text";
	xhr.timeout = 5000;
	xhr.send(JSON.stringify({"password":password, "payload":[profile, target, text]}));
}


function openWindow(profile, target, callback) {
	var xhr = new XMLHttpRequest();
	if (callback != null)
		xhr.onload = callback;
	xhr.open("POST", "open-window.json", true);
	xhr.responseType = "text";
	xhr.timeout = 5000;
	xhr.send(JSON.stringify({"password":password, "payload":[profile, target]}));
}


function closeWindow(profile, target) {
	var xhr = new XMLHttpRequest();
	xhr.open("POST", "close-window.json", true);
	xhr.responseType = "text";
	xhr.timeout = 5000;
	xhr.send(JSON.stringify({"password":password, "payload":[profile, target]}));
}


function markRead(sequence) {
	var xhr = new XMLHttpRequest();
	xhr.open("POST", "mark-read.json", true);
	xhr.responseType = "text";
	xhr.timeout = 5000;
	xhr.send(JSON.stringify({"password":password, "payload":[activeWindow[0], activeWindow[1], sequence]}));
}


function clearLines(sequence) {
	var xhr = new XMLHttpRequest();
	xhr.open("POST", "clear-lines.json", true);
	xhr.responseType = "text";
	xhr.timeout = 5000;
	xhr.send(JSON.stringify({"password":password, "payload":[activeWindow[0], activeWindow[1], sequence]}));
}


// x and y are numbers, items is a list of pairs {text string, onclick function}.
function openContextMenu(x, y, items) {
	closeContextMenu();
	var div = document.createElement("div");
	div.id = "menu";
	div.style.left = x + "px";
	div.style.top  = y + "px";
	var ul = document.createElement("ul");
	
	items.forEach(function(item) {
		var li = document.createElement("li");
		var a = document.createElement("a");
		setElementText(a, item[0]);
		a.href = "#";
		a.onclick = (function(func) {
			return function() {
				func();
				closeContextMenu();
				return false;
			};
		})(item[1]);
		li.appendChild(a);
		ul.appendChild(li);
	});
	
	div.appendChild(ul);
	div.onmousedown = function(ev) { ev.stopPropagation(); };
	document.getElementsByTagName("body")[0].appendChild(div);
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
