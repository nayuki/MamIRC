"use strict";


var windowListElem = document.getElementById("window-list");
var messageListElem = document.getElementById("message-list");
var inputBoxElem = document.getElementById("input-box");
var nicknameElem = document.getElementById("nickname");

var activeWindowName = null;  // String
var windowNames      = null;  // List<String>
var windowMessages   = null;  // Map<String, List<Tuple<Integer, String>>>
var currentNicknames = null;  // Map<String, String>
var nextUpdateId     = null;  // Integer
var password         = null;  // String

var MAX_MESSAGES_PER_WINDOW = 3000;


function init() {
	document.getElementsByTagName("form")[0].onsubmit = authenticate;
	document.getElementsByTagName("form")[1].onsubmit = sendMessage;
}


function authenticate() {
	password = document.getElementById("password").value;
	getState();
	return false;  // To prevent the form submitting
}


function getState() {
	var xhr = new XMLHttpRequest();
	xhr.onload = function() {
		var data = JSON.parse(xhr.response);
		if (typeof data == "string") {
			var elem = document.getElementById("login-status");
			removeChildren(elem);
			elem.appendChild(document.createTextNode(data));
		} else {
			document.getElementById("login").style.display = "none";
			document.getElementById("main").style.display = "block";
			loadState(data);
			updateState();
		}
	};
	xhr.ontimeout = xhr.onerror = function() {
		var li = document.createElement("li");
		li.appendChild(document.createTextNode("(Unable to connect to data provider)"));
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
	currentNicknames = {};
	nextUpdateId = data.nextUpdateId;
	
	for (var profileName in data.messages) {
		for (var targetName in data.messages[profileName]) {
			var windowName = profileName + "\n" + targetName;
			windowNames.push(windowName);
			var messages = data.messages[profileName][targetName];
			if (messages.length > MAX_MESSAGES_PER_WINDOW)
				messages = messages.slice(messages.length - MAX_MESSAGES_PER_WINDOW);  // Take suffix
			windowMessages[windowName] = messages;
		}
	}
	
	for (var profileName in data.connections)
		currentNicknames[profileName] = data.connections[profileName].currentNickname;
	
	windowNames.sort();
	redrawWindowList();
	if (windowNames.length > 0)
		setActiveWindow(windowNames[0]);
}


function redrawWindowList() {
	removeChildren(windowListElem);
	for (var i = 0; i < windowNames.length; i++) {
		var li = document.createElement("li");
		var a = document.createElement("a");
		var windowName = windowNames[i];
		var parts = windowName.split("\n");
		a.appendChild(document.createTextNode(parts[1] + " (" + parts[0] + ")"));
		a.href = "#";
		a.onclick = (function(name) {
			return function() {
				setActiveWindow(name);
				return false;
			};
		})(windowName);
		li.appendChild(a);
		windowListElem.appendChild(li);
	}
}


function setActiveWindow(name) {
	if (activeWindowName == name)
		return;
	
	activeWindowName = name;
	var windowLis = windowListElem.getElementsByTagName("li");
	for (var i = 0; i < windowLis.length; i++)
		windowLis[i].className = windowNames[i] == name ? "selected" : "";
	
	removeChildren(nicknameElem);
	nicknameElem.appendChild(document.createTextNode(currentNicknames[name.split("\n")[0]]));
	
	removeChildren(messageListElem);
	var messages = windowMessages[name];
	for (var i = 0; i < messages.length; i++)
		messageListElem.appendChild(messageToRow(messages[i]));
	if (messages.length > 0) {
		messageListElem.parentNode.style.tableLayout = "auto";
		var a = messageListElem.firstChild.children[0].clientWidth;
		var b = messageListElem.firstChild.children[1].clientWidth;
		messageListElem.parentNode.style.tableLayout = "fixed";
		messageListElem.firstChild.children[0].style.width = a + "px";
		messageListElem.firstChild.children[1].style.width = b + "px";
	}
}


// 'msg' is a length-2 array of int timestamp, string line.
function messageToRow(msg) {
	var who = "RAW";  // String
	var lineElems = null;  // Array of DOM nodes
	var parts = split2(msg[1]);
	
	if (parts[0] == "PRIVMSG") {
		var subparts = split2(parts[1]);
		who = subparts[0];
		lineElems = [];
		var s = subparts[1];
		var mematch = ME_INCOMING_REGEX.exec(s);
		if (mematch != null)
			s = mematch[1];
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
			for (var i = 0; i < lineElems.length; i++)
				em.appendChild(lineElems[i]);
			lineElems = [em];
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
	
	var tr = document.createElement("tr");
	var td = document.createElement("td");
	td.appendChild(document.createTextNode(formatDate(msg[0])));
	tr.appendChild(td);
	td = document.createElement("td");
	td.appendChild(document.createTextNode(who));
	tr.appendChild(td);
	td = document.createElement("td");
	if (lineElems == null)
		lineElems = [document.createTextNode(msg[1])];
	for (var i = 0; i < lineElems.length; i++)
		td.appendChild(lineElems[i]);
	tr.appendChild(td);
	return tr;
}

var ME_INCOMING_REGEX = /^\u0001ACTION (.*)\u0001$/;
var ME_OUTGOING_REGEX = /^\/me (.*)$/i;


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
	var updates = data.updates;
	for (var i = 0; i < updates.length; i++) {
		var parts = updates[i].split("\n");
		if (parts[0] == "APPEND") {
			var windowName = parts[1] + "\n" + parts[2];
			if (windowNames.indexOf(windowName) == -1) {
				windowMessages[windowName] = [];
				windowNames.push(windowName);
				windowNames.sort();
				redrawWindowList();
			}
			var msg = [parseInt(parts[3], 10), parts[4]];
			var messages = windowMessages[windowName];
			messages.push(msg);
			if (messages.length > MAX_MESSAGES_PER_WINDOW)
				windowMessages[windowName] = messages.slice(messages.length - MAX_MESSAGES_PER_WINDOW);
			if (windowName == activeWindowName) {
				messageListElem.appendChild(messageToRow(msg));
				while (messageListElem.childNodes.length > MAX_MESSAGES_PER_WINDOW)
					messageListElem.removeChild(messageListElem.firstChild);
				activeWindowUpdated = true;
			}
		} else if (parts[0] == "MYNICK") {
			currentNicknames[parts[1]] = parts[2];
			if (activeWindowName.split("\n")[0] == parts[1]) {
				removeChildren(nicknameElem);
				nicknameElem.appendChild(document.createTextNode(parts[2]));
				activeWindowUpdated = true;
			}
		}
	}
	
	if (activeWindowUpdated) {
		messageListElem.parentNode.style.tableLayout = "auto";
		var a = messageListElem.firstChild.children[0].clientWidth;
		var b = messageListElem.firstChild.children[1].clientWidth;
		messageListElem.parentNode.style.tableLayout = "fixed";
		messageListElem.firstChild.children[0].style.width = a + "px";
		messageListElem.firstChild.children[1].style.width = b + "px";
		window.scrollTo(0, scrollToBottom ? document.documentElement.scrollHeight : scrollPosition);
	}
}


function sendMessage() {
	var text = inputBoxElem.value;
	if ((/^\/query [^ ]+$/i).test(text)) {
		// Open and switch to window
		var windowName = activeWindowName.split("\n")[0] + "\n" + text.substring(7);
		if (windowNames.indexOf(windowName) == -1) {
			windowNames.push(windowName);
			windowNames.sort();
			redrawWindowList();
			windowMessages[windowName] = [];
		}
		setActiveWindow(windowName);
		inputBoxElem.value = "";
		
	} else {
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
		var match = ME_OUTGOING_REGEX.exec(text);
		if (match != null)
			text = "\u0001ACTION " + match[1] + "\u0001";
		var parts = activeWindowName.split("\n");
		xhr.send(JSON.stringify({"password":password, "payload":[parts[0], parts[1], text]}));
	}
	return false;  // To prevent the form submitting
}


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


function split2(str) {
	var i = str.indexOf(" ");
	if (i == -1)
		throw "Cannot split";
	return [str.substr(0, i), str.substring(i + 1)];
}


init();
