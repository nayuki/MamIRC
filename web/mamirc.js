"use strict";


var windowListElem = document.getElementById("window-list");
var messageListElem = document.getElementById("message-list");
var inputBoxElem = document.getElementById("input-box");

var activeWindow = null;
var windowNames = [];
var messageData = new Object();
var connectionSequences = new Object();


function init() {
	removeChildren(windowListElem);
	removeChildren(messageListElem);
	document.getElementsByTagName("form")[0].onsubmit = sendMessage;
	
	var xhr = new XMLHttpRequest();
	xhr.onload = function() {
		ingestMessages(JSON.parse(xhr.response));
		setActiveWindow(windowNames[0]);
		pollNewMessages();
	};
	xhr.ontimeout = xhr.onerror = function() {
		var li = document.createElement("li");
		li.appendChild(document.createTextNode("(Unable to connect to data provider)"));
		windowListElem.appendChild(li);
	};
	xhr.open("GET", "get-messages.json", true);
	xhr.responseType = "text";
	xhr.timeout = 5000;
	xhr.send();
}


function setActiveWindow(name) {
	activeWindow = name;
	var windowLis = windowListElem.childNodes;
	for (var i = 0; i < windowLis.length; i++)
		windowLis[i].className = (windowLis[i].firstChild.firstChild.nodeValue == name) ? "selected" : "";
	
	removeChildren(messageListElem);
	var data = messageData[name];
	for (var i = 0; i < data.length; i++) {
		var tr = document.createElement("tr");
		var td = document.createElement("td");
		td.appendChild(document.createTextNode(formatDate(data[i][0])));
		tr.appendChild(td);
		td = document.createElement("td");
		td.appendChild(document.createTextNode(data[i][1]));
		tr.appendChild(td);
		td = document.createElement("td");
		var s = data[i][2];
		while (s != "") {
			var match = /(^|.*?\()(https?:\/\/[^ )]+)(.*)/.exec(s);
			if (match == null)
				match = /(^|.*? )(https?:\/\/[^ ]+)(.*)/.exec(s);
			if (match == null) {
				td.appendChild(document.createTextNode(s));
				break;
			} else {
				if (match[1].length > 0)
					td.appendChild(document.createTextNode(match[1]));
				var a = document.createElement("a");
				a.href = match[2];
				a.target = "_blank";
				a.appendChild(document.createTextNode(match[2]));
				td.appendChild(a);
				s = match[3];
			}
		}
		tr.appendChild(td);
		messageListElem.appendChild(tr);
	}
}


function pollNewMessages() {
	var xhr = new XMLHttpRequest();
	xhr.onload = function() {
		if (xhr.status != 200)
			xhr.onerror();
		else {
			var data = JSON.parse(xhr.response);
			ingestMessages(data);
			setActiveWindow(activeWindow);
			pollNewMessages();
		}
	};
	xhr.ontimeout = xhr.onerror = function() {
		setTimeout(pollNewMessages, 60000);
	};
	xhr.open("POST", "get-new-messages.json", true);
	xhr.responseType = "text";
	xhr.timeout = 600000;
	xhr.send(JSON.stringify(connectionSequences));
}


function ingestMessages(data) {
	var windowsChanged = false;
	for (var profile in data) {
		for (var party in data[profile].windows) {
			var windowName = party + ":" + profile;
			if (windowNames.indexOf(windowName) == -1) {
				windowNames.push(windowName);
				messageData[windowName] = [];
				windowsChanged = true;
			}
			var messages = messageData[windowName];
			var msgs = data[profile].windows[party];
			for (var i = 0; i < msgs.length; i++) {
				var s = msgs[i][1];
				var match = /^PRIVMSG ([^ ]+) (.*)$/.exec(s);
				if (match != null)
					messages.push([msgs[i][0], match[1], match[2]]);
			}
		}
		connectionSequences[profile] = [data[profile]["connection-id"], data[profile]["max-sequence"]];
	}
	
	if (windowsChanged) {
		removeChildren(windowListElem);
		for (var i = 0; i < windowNames.length; i++) {
			var li = document.createElement("li");
			var a = document.createElement("a");
			a.href = "#";
			a.onclick = (function(name) {
				return function() {
					setActiveWindow(name);
					return false;
				}; })(windowNames[i]);
			a.appendChild(document.createTextNode(windowNames[i]));
			li.appendChild(a);
			windowListElem.appendChild(li);
		}
	}
}


function sendMessage() {
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
	xhr.send(JSON.stringify([activeWindow, inputBoxElem.value]));
	
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


init();
