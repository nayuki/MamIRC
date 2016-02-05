/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

"use strict";


(function() {
	/*---- Initialization ----*/
	
	const testMessagesElem = document.getElementById("test-messages");
	
	while (testMessagesElem.firstChild != null)
		testMessagesElem.removeChild(testMessagesElem.firstChild);
	if (!("utilsModule" in window)) {
		addMessage("Error: utilsModule not found.");
		addMessage("Please extract from mamirc.js the entire utilsModule declaration, and put it in a file named utils-module.js.");
		return;
	}
	
	
	/*---- Test suite definitions ----*/
	
	function testNthRemainingPart() {
		var f = utilsModule.nthRemainingPart;
		check(f, "a b c", "a b c", 0);
		check(f, "b c"  , "a b c", 1);
		check(f, "c"    , "a b c", 2);
		check(f, " yz", "x  yz", 1);
		check(f, "yz" , "x  yz", 2);
	}
	
	
	function testCountUtf8Bytes() {
		var f = utilsModule.countUtf8Bytes;
		check(f, 0, "");
		check(f, 1, "a");
		check(f, 4, " |#Z");
		check(f, 15, "\u3068\u3082\u3048\u30DE\u30DF");
		check(f, 4, "\uD83D\uDE04");
		check(f, 15, "\uD800xy\uDC00\uD900z\uDA00");
	}
	
	
	function testTruncateLongText() {
		var f = utilsModule.truncateLongText;
		check(f, "", "", 3);
		check(f, "", "", 4);
		check(f, "lorem", "lorem", 5);
		check(f, "lo...", "lorems", 5);
		check(f, "\uD83D\uDE04_+XZ *\uD83D\uDE05", "\uD83D\uDE04_+XZ *\uD83D\uDE05", 8);
		check(f, "abc\uD83D\uDE04v...", "abc\uD83D\uDE04vwxyz", 8);
		check(f, "abc\uD83D\uDE04...", "abc\uD83D\uDE04vwxyz", 7);
		check(f, "abc...", "abc\uD83D\uDE04vwxyz", 6);
	}
	
	
	function testIsChannelName() {
		var f = utilsModule.isChannelName;
		check(f, false, "Alice");
		check(f, true, "#bob");
		check(f, true, "##carol");
		check(f, true, "&dave");
		check(f, false, "eve#");
		check(f, false, " #fred");
	}
	
	
	function testTwoDigits() {
		var f = utilsModule.twoDigits;
		check(f, "00",  0);
		check(f, "01",  1);
		check(f, "09",  9);
		check(f, "10", 10);
		check(f, "37", 37);
		check(f, "99", 99);
	}
	
	
	const TEST_SUITE = [
		testNthRemainingPart,
		testCountUtf8Bytes,
		testTruncateLongText,
		testIsChannelName,
		testTwoDigits,
	];
	
	
	/*---- Main test runner ----*/
	
	TEST_SUITE.forEach(function(func) {
		try {
			func();
			addMessage(func.name + ": Pass");
		} catch (e) {
			addMessage(func.name + ": Fail (" + e + ")");
		}
	});
	addMessage("Test completed");
	
	
	/*---- Helper functions ----*/
	
	function check(func, expectResult) {  // Expects extra arguments
		if (arguments.length < 2)
			throw "Invalid number of arguments";
		var args = [];
		for (var i = 2; i < arguments.length; i++)
			args.push(arguments[i]);
		var actualResult = func.apply(null, args);
		if (actualResult !== expectResult)
			throw "Value mismatch: " + actualResult + " !== " + expectResult;
	}
	
	function addMessage(str) {
		var li = document.createElement("li");
		li.appendChild(document.createTextNode(str));
		testMessagesElem.appendChild(li);
	}
	
})();
