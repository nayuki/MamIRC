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
var activeWindowName = null;  // String
var windowNames      = null;  // List<String>
var windowMessages   = null;  // Map<String, List<Tuple<Integer, String>>>
var windowMarkedRead = null;  // Map<String, Integer>
var currentNicknames = null;  // Map<String, String>
var nextUpdateId     = null;  // Integer
var password         = null;  // String

var MAX_MESSAGES_PER_WINDOW = 3000;


/*---- Major functions ----*/

function init() {
	document.getElementsByTagName("form")[0].onsubmit = authenticate;
	document.getElementsByTagName("form")[1].onsubmit = handleInputLine;
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


function loadState(data) {
	windowNames = [];
	windowMessages = {};
	windowMarkedRead = {};
	currentNicknames = {};
	nextUpdateId = data.nextUpdateId;
	
	data.windows.forEach(function(winTuple) {
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
	
	for (var profileName in data.connections)
		currentNicknames[profileName] = data.connections[profileName].currentNickname;
	
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
		var li = document.createElement("li");
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
		li.className = windowName == activeWindowName ? "selected" : "";
		li.appendChild(a);
		windowListElem.appendChild(li);
	});
}


function setActiveWindow(name) {
	if (activeWindowName == name)
		return;
	
	activeWindowName = name;
	var windowLis = windowListElem.getElementsByTagName("li");
	for (var i = 0; i < windowLis.length; i++)
		windowLis[i].className = windowNames[i] == name ? "selected" : "";
	
	setElementText(nicknameElem, currentNicknames[name.split("\n")[0]]);
	
	removeChildren(messageListElem);
	var messages = windowMessages[name];
	messages.forEach(function(msg) {
		messageListElem.appendChild(messageToRow(msg, name));
	});
	messageListElem.parentNode.style.tableLayout = "auto";
	if (messages.length > 0) {
		var a = messageListElem.firstChild.children[0].clientWidth;
		var b = messageListElem.firstChild.children[1].clientWidth;
		messageListElem.parentNode.style.tableLayout = "fixed";
		messageListElem.firstChild.children[0].style.width = a + "px";
		messageListElem.firstChild.children[1].style.width = b + "px";
	}
	var parts = name.split("\n");
	setElementText(channelElem, parts[1]);
	document.title = parts[1] + " - " + parts[0] + " - MamIRC";
}


// 'msg' is a length-2 array of int timestamp, string line.
function messageToRow(msg, windowName) {
	var who = "RAW";  // String
	var lineElems = null;  // Array of DOM nodes
	var parts = split2(msg[2]);
	var rowClass = "";
	var quoteText = null;
	
	if (parts[0] == "PRIVMSG") {
		var subparts = split2(parts[1]);
		who = subparts[0];
		lineElems = [];
		var s = subparts[1];
		var mematch = ME_INCOMING_REGEX.exec(s);
		if (mematch != null)
			s = mematch[1];
		
		var flags = msg[3];
		if ((flags & 0x1) != 0)
			rowClass += "outgoing ";
		if ((flags & 0x2) != 0)
			rowClass += "nickflag ";
		quoteText = s.replace(/\t/g, " ").replace(/[\u0000-\u001F]/g, "");
		
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
	} else if (parts[0] == "NOTICE") {
		var subparts = split2(parts[1]);
		who = "(" + subparts[0] + ")";
		lineElems = [document.createTextNode(subparts[1])];
		
	} else if (parts[0] == "NICK") {
		var subparts = split2(parts[1]);
		who = "*";
		lineElems = [document.createTextNode(subparts[0] + " changed their name to " + subparts[1])];
	} else if (parts[0] == "JOIN") {
		who = "*";
		lineElems = [document.createTextNode(parts[1] + " joined the channel")];
	} else if (parts[0] == "PART") {
		who = "*";
		lineElems = [document.createTextNode(parts[1] + " left the channel")];
	} else if (parts[0] == "QUIT") {
		var subparts = split2(parts[1]);
		who = "*";
		lineElems = [document.createTextNode(subparts[0] + " has quit: " + subparts[1])];
	}
	if (msg[0] < windowMarkedRead[windowName])
		rowClass += "read ";
	
	var tr = document.createElement("tr");
	var td = document.createElement("td");
	td.appendChild(document.createTextNode(formatDate(msg[1])));
	tr.appendChild(td);
	
	td = document.createElement("td");
	td.appendChild(document.createTextNode(who));
	if (who != "*" && who != "RAW") {
		td.oncontextmenu = function(ev) {
			openContextMenu(ev.pageX, ev.pageY, [["Open PM window", function() { openPrivateMessagingWindow(who); }]]);
			return false;
		};
	}
	tr.appendChild(td);
	
	td = document.createElement("td");
	if (lineElems == null)
		lineElems = [document.createTextNode(msg[2])];
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
	
	if (rowClass != "");
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


function loadUpdates(data) {
	var scrollToBottom = inputBoxElem.getBoundingClientRect().bottom < document.documentElement.clientHeight;
	var scrollPosition = document.documentElement.scrollTop;
	var activeWindowUpdated = false;
	
	nextUpdateId = data.nextUpdateId;
	data.updates.forEach(function(payload) {
		var parts = payload.split("\n");
		if (parts[0] == "APPEND") {
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
			if (windowName == activeWindowName) {
				messageListElem.appendChild(messageToRow(msg, windowName));
				while (messageListElem.childNodes.length > MAX_MESSAGES_PER_WINDOW)
					messageListElem.removeChild(messageListElem.firstChild);
				activeWindowUpdated = true;
			}
		} else if (parts[0] == "MYNICK") {
			currentNicknames[parts[1]] = parts[2];
			if (activeWindowName.split("\n")[0] == parts[1]) {
				setElementText(nicknameElem, parts[2]);
				activeWindowUpdated = true;
			}
		} else if (parts[0] == "OPENWIN") {
			var windowName = parts[1] + "\n" + parts[2];
			var index = windowNames.indexOf(windowName);
			if (index == -1) {
				windowNames.push(windowName);
				windowMessages[windowName] = [];
				windowNames.sort();
				redrawWindowList();
				setActiveWindow(windowName);
				inputBoxElem.value = "";
			}
		} else if (parts[0] == "CLOSEWIN") {
			var windowName = parts[1] + "\n" + parts[2];
			var index = windowNames.indexOf(windowName);
			if (index != -1) {
				windowNames.splice(index, 1);
				delete windowMessages[windowName];
				redrawWindowList();
				if (windowName == activeWindowName) {
					inputBoxElem.value = "";
					if (windowNames.length > 0)
						setActiveWindow(windowNames[Math.min(index, windowNames.length - 1)]);
					else
						removeChildren(messageListElem);
				}
			}
		} else if (parts[0] == "MARKREAD") {
			var windowName = parts[1] + "\n" + parts[2];
			var seq = parseInt(parts[3], 10);
			windowMarkedRead[windowName] = seq;
			if (windowName == activeWindowName) {
				var msgs = windowMessages[windowName];
				var trs = messageListElem.children;
				for (var j = 0; j < msgs.length; j++) {
					var expect = msgs[j][0] < seq;
					var classParts = trs[j].className.split(" ");
					var k = classParts.indexOf("read");
					if (expect && k == -1)
						trs[j].className += "read ";
					else if (!expect && k != -1) {
						classParts.splice(k, 1);
						trs[j].className = classParts.join(" ");
					}
				}
				activeWindowUpdated = true;
			}
		} else if (parts[0] == "CLEARLINES") {
			var windowName = parts[1] + "\n" + parts[2];
			var seq = parseInt(parts[3], 10);
			var msgs = windowMessages[windowName];
			var j;
			for (j = 0; j < msgs.length && msgs[j][0] < seq; j++);
			msgs.splice(0, j);
			if (windowName == activeWindowName) {
				for (; j > 0; j--)
					messageListElem.removeChild(messageListElem.firstChild);
				activeWindowUpdated = true;
			}
		}
	});
	
	if (activeWindowUpdated) {
		messageListElem.parentNode.style.tableLayout = "auto";
		if (messageListElem.children.length > 0) {
			var a = messageListElem.firstChild.children[0].clientWidth;
			var b = messageListElem.firstChild.children[1].clientWidth;
			messageListElem.parentNode.style.tableLayout = "fixed";
			messageListElem.firstChild.children[0].style.width = a + "px";
			messageListElem.firstChild.children[1].style.width = b + "px";
		}
		window.scrollTo(0, scrollToBottom ? document.documentElement.scrollHeight : scrollPosition);
	}
}


function handleInputLine() {
	var text = inputBoxElem.value;
	if (text.startsWith("//")) {  // Ordinary message beginning with slash
		var parts = activeWindowName.split("\n");
		sendMessage(parts[0], parts[1], text.substring(1));
		
	} else if (text.startsWith("/")) {  // Command or special message
		var i = text.indexOf(" ");
		if (i == -1)
			i = text.length;
		var cmd = text.substring(1, i).toLowerCase();
		
		if (cmd == "me" && text.length - i >= 2) {
			var text = "\u0001ACTION " + text.substring(4) + "\u0001";
			var parts = activeWindowName.split("\n");
			sendMessage(parts[0], parts[1], text);
			
		} else if (cmd == "query" && /^\/query [^ ]+$/i.test(text)) {
			openPrivateMessagingWindow(text.substring(7));
			
		} else if (cmd == "msg" && text.split(" ").length >= 3) {
			var parts = split2(text.substring(5));
			var target = parts[0];
			var text = parts[1];
			var profile = activeWindowName.split("\n")[0];
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
		var parts = activeWindowName.split("\n");
		sendMessage(parts[0], parts[1], text);
	}
	return false;  // To prevent the form submitting
}


function openPrivateMessagingWindow(target) {
	var profile = activeWindowName.split("\n")[0];
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
	var profile = activeWindowName.split("\n")[0];
	var target = activeWindowName.split("\n")[1];
	var xhr = new XMLHttpRequest();
	xhr.open("POST", "mark-read.json", true);
	xhr.responseType = "text";
	xhr.timeout = 5000;
	xhr.send(JSON.stringify({"password":password, "payload":[profile, target, sequence]}));
}


function clearLines(sequence) {
	var profile = activeWindowName.split("\n")[0];
	var target = activeWindowName.split("\n")[1];
	var xhr = new XMLHttpRequest();
	xhr.open("POST", "clear-lines.json", true);
	xhr.responseType = "text";
	xhr.timeout = 5000;
	xhr.send(JSON.stringify({"password":password, "payload":[profile, target, sequence]}));
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
