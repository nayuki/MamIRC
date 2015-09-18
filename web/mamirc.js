"use strict";


var windowListElem = document.getElementById("window-list");
var messageListElem = document.getElementById("message-list");
var inputBoxElem = document.getElementById("input-box");

var activeWindow = null;
var windowNames = [];
var messageData = new Object();


function init() {
	removeChildren(windowListElem);
	removeChildren(messageListElem);
	document.getElementsByTagName("form")[0].onsubmit = function() { return false; };
	
	var xhr = new XMLHttpRequest();
	xhr.onload = function() {
		processInitialMessages(JSON.parse(xhr.response));
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


function processInitialMessages(data) {
	for (var profile in data) {
		for (var party in data[profile]) {
			var windowName = party + ":" + profile;
			var messages = [];
			var msgs = data[profile][party];
			for (var i = 0; i < msgs.length; i++) {
				var s = msgs[i][1];
				var match = /^PRIVMSG ([^ ]+) (.*)$/.exec(s);
				if (match != null)
					messages.push([msgs[i][0], match[1], match[2]]);
			}
			windowNames.push(windowName);
			messageData[windowName] = messages;
		}
	}
	
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
	setActiveWindow(windowNames[0]);
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
		td.appendChild(document.createTextNode(data[i][2]));
		tr.appendChild(td);
		messageListElem.appendChild(tr);
	}
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
