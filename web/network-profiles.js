/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

"use strict";


function initialize() {
	var xhr = new XMLHttpRequest();
	xhr.onload = function() {
		showNetworkProfiles(xhr.response);
	};
	xhr.open("POST", "get-network-profiles.json", true);
	xhr.responseType = "json";
	xhr.send();
	
	function showNetworkProfiles(data) {
		initButtons();
		
		var containerElem = document.getElementById("network-profiles-container");
		var templateElem = document.getElementById("network-profile-template");
		
		var profNames = Object.keys(data);
		profNames.sort(compareStrings);
		profNames.forEach(function(profName, i) {
			var profData = data[profName];
			var rootElem = templateElem.cloneNode(true);
			rootElem.removeAttribute("id");
			
			rootElem.querySelector(".profile-name-display-row td").appendChild(document.createTextNode(profName));
			var nameInputRowElem = rootElem.querySelector(".profile-name-input-row");
			nameInputRowElem.parentNode.removeChild(nameInputRowElem);
			
			var connectRowElem = rootElem.querySelector(".connect-row");
			connectRowElem.querySelector("input").checked = profData.connect;
			var elemId = "connect-" + i + "-checkbox";
			connectRowElem.querySelector("input").id = elemId;
			connectRowElem.querySelector("label").htmlFor = elemId;
			
			var serverTemplateElem = rootElem.querySelector(".servers-row li:first-child");
			var serverContainerElem = serverTemplateElem.parentNode;
			serverContainerElem.removeChild(serverTemplateElem);
			var addServerElem = rootElem.querySelector(".servers-row li:last-child");
			addServerElem.onclick = function() {
				var serverElem = serverTemplateElem.cloneNode(true);
				var elemId = "servers-" + i + "-" + (serverContainerElem.getElementsByTagName("li").length - 1) + "-ssl";
				serverElem.querySelector("input[type=checkbox]").id = elemId;
				serverElem.querySelector("label").htmlFor = elemId;
				serverContainerElem.insertBefore(serverElem, addServerElem);
			};
			profData.servers.forEach(function(servData, j) {
				var serverElem = serverTemplateElem.cloneNode(true);
				serverElem.querySelector(".hostname-input").value = servData.hostname;
				serverElem.querySelector(".port-input").value = servData.port;
				serverElem.querySelector("input[type=checkbox]").checked = servData.ssl;
				var elemId = "servers-" + i + "-" + j + "-ssl";
				serverElem.querySelector("input[type=checkbox]").id = elemId;
				serverElem.querySelector("label").htmlFor = elemId;
				serverContainerElem.insertBefore(serverElem, addServerElem);
			});
			
			rootElem.querySelector(".nicknames-row input").value = profData.nicknames.join(", ");
			rootElem.querySelector(".username-row  input").value = profData.username;
			rootElem.querySelector(".realname-row  input").value = profData.realname;
			rootElem.querySelector(".channels-row  input").value = profData.channels.join(", ");
			containerElem.appendChild(rootElem);
		});
	}
}


function initButtons() {
	document.getElementById("add-network-button").onclick = function() {
		var containerElem = document.getElementById("network-profiles-container");
		var i = containerElem.getElementsByTagName("table").length;
		var templateElem = document.getElementById("network-profile-template");
		var rootElem = templateElem.cloneNode(true);
		rootElem.removeAttribute("id");
		
		var nameDisplayRowElem = rootElem.querySelector(".profile-name-display-row");
		nameDisplayRowElem.parentNode.removeChild(nameDisplayRowElem);
		
		var connectRowElem = rootElem.querySelector(".connect-row");
		connectRowElem.querySelector("input").checked = true;
		var elemId = "connect-" + i + "-checkbox";
		connectRowElem.querySelector("input").id = elemId;
		connectRowElem.querySelector("label").htmlFor = elemId;
		
		var serverTemplateElem = rootElem.querySelector(".servers-row li:first-child");
		var serverContainerElem = serverTemplateElem.parentNode;
		serverContainerElem.removeChild(serverTemplateElem);
		var addServerElem = rootElem.querySelector(".servers-row li:last-child");
		addServerElem.onclick = function() {
			var serverElem = serverTemplateElem.cloneNode(true);
			var elemId = "servers-" + i + "-" + (serverContainerElem.getElementsByTagName("li").length - 1) + "-ssl";
			serverElem.querySelector("input[type=checkbox]").id = elemId;
			serverElem.querySelector("label").htmlFor = elemId;
			serverContainerElem.insertBefore(serverElem, addServerElem);
		};
		addServerElem.onclick();
		containerElem.appendChild(rootElem);
	};
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
