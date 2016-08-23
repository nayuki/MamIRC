/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */


function initialize() {
	document.getElementById("messages-back-to-windows").onclick = function() {
		clearMessagesScreen();
		showScreen("windows-section");
		return false;
	};
	
	getAndShowWindowList();
}


function getAndShowWindowList() {
	var xhr = new XMLHttpRequest();
	xhr.onload = function() {
		showWindowList(xhr.response);
	};
	xhr.open("POST", "get-window-list.json", true);
	xhr.responseType = "json";
	xhr.send();
	
	function showWindowList(data) {
		var tableElem = document.getElementById("windows-table");
		while (tableElem.firstChild != null)
			tableElem.removeChild(tableElem.firstChild);
		
		data.sort(function(x, y) {
			var result = compareStrings(x[0], y[0]);
			if (result == 0)
				result = compareStrings(x[1], y[1]);
			return result;
		});
		
		data.forEach(function(row) {
			var profile = row[0];
			var party = row[1];
			var numMsg = row[2];
			var timestamp = new Date(row[3]);
			
			var tr = document.createElement("tr");
			var td = document.createElement("td");
			td.appendChild(document.createTextNode(profile));
			tr.appendChild(td);
			td = document.createElement("td");
			if (party != "")
				td.appendChild(document.createTextNode(party));
			else {
				td.appendChild(document.createTextNode("(server messages)"));
				td.classList.add("server-messages");
			}
			tr.appendChild(td);
			td = document.createElement("td");
			td.appendChild(document.createTextNode(formatDatetime(timestamp)));
			tr.appendChild(td);
			td = document.createElement("td");
			td.appendChild(document.createTextNode(numMsg.toString()));
			tr.appendChild(td);
			tr.onclick = function() {
				getAndShowMessages(profile, party, Math.max(numMsg - 1000, 0), numMsg);
				return false;
			};
			tableElem.appendChild(tr);
		});
		showScreen("windows-section");
	}
}


function getAndShowMessages(profile, party, start, end, scroll) {
	var xhr = new XMLHttpRequest();
	xhr.onload = function() {
		showMessages(xhr.response);
	};
	xhr.open("POST", "get-window-messages.json", true);
	xhr.responseType = "json";
	xhr.send(JSON.stringify({profile:profile, party:party, start:start, end:end}));
	
	function showMessages(data) {
		clearMessagesScreen();
		
		var elem = document.getElementById("messages-start");
		elem.appendChild(document.createTextNode(start.toString()));
		
		elem = document.getElementById("messages-end");
		elem.appendChild(document.createTextNode((start + data.length - 1).toString()));
		
		var tableElem = document.getElementById("messages-table");
		data.forEach(function(row) {
			var timestamp = new Date(row[0]);
			var command = row[1];
			var args = row.slice(2);
			
			var tr = document.createElement("tr");
			var td = document.createElement("td");
			td.appendChild(document.createTextNode(formatDatetime(timestamp)));
			tr.appendChild(td);
			td = document.createElement("td");
			td.appendChild(document.createTextNode(command));
			tr.appendChild(td);
			td = document.createElement("td");
			td.appendChild(document.createTextNode(args.join(" ")));
			tr.appendChild(td);
			tableElem.appendChild(tr);
		});
		
		if (scroll === "top")
			window.scrollTo(0, 0);
		else if (scroll === "bottom")
			window.scrollTo(0, document.documentElement.scrollHeight);
		// Else ignore if scroll === undefined
		
		if (start > 0) {
			document.getElementById("messages-previous-page").onclick = function() {
				var prevStart = Math.max(start - 1000, 0);
				getAndShowMessages(profile, party, prevStart, prevStart + 1000, "bottom");
				return false;
			};
		}
		if (data.length == 1000) {
			document.getElementById("messages-next-page").onclick = function() {
				getAndShowMessages(profile, party, start + 1000, end + 1000, "top");
				return false;
			};
		}
		showScreen("messages-section");
	}
}


function clearMessagesScreen() {
	var tableElem = document.getElementById("messages-table");
	while (tableElem.firstChild != null)
		tableElem.removeChild(tableElem.firstChild);
	
	var elem = document.getElementById("messages-start");
	while (elem.firstChild != null)
		elem.removeChild(elem.firstChild);
	
	elem = document.getElementById("messages-end");
	while (elem.firstChild != null)
		elem.removeChild(elem.firstChild);
	
	document.getElementById("messages-previous-page").onclick = null;
	document.getElementById("messages-next-page").onclick = null;
}


function showScreen(elemId) {
	var elems = document.querySelectorAll("section");
	for (var i = 0; i < elems.length; i++) {
		var elem = elems[i];
		if (elem.id == elemId)
			elem.style.removeProperty("display");
		else
			elem.style.display = "none";
	}
}


var DAYS_OF_WEEK = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

function formatDatetime(d) {
	return d.getFullYear() + "-" +
		(d.getMonth() + 1 < 10 ? "0" : "") + (d.getMonth() + 1) + "-" +
		(d.getDate() < 10 ? "0" : "") + d.getDate() + "-" +
		DAYS_OF_WEEK[d.getDay()] + " " +
		(d.getHours() < 10 ? "0" : "") + d.getHours() + ":" +
		(d.getMinutes() < 10 ? "0" : "") + d.getMinutes();
}


function compareStrings(s, t) {
	if (s < t)
		return -1;
	else if (s > t)
		return 1;
	else
		return 0;
}



initialize();
