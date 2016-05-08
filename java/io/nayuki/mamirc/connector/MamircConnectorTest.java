/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.connector;

import static org.junit.Assert.assertEquals;
import org.junit.Test;
import io.nayuki.mamirc.common.Utils;


public final class MamircConnectorTest {
	
	@Test public void testMakePongIfPing() {
		String[][] cases = {
			{null, ""},
			{null, "PONG"},
			{null, "PINGU"},
			{null, " PING"},
			{null, " :abc PING"},
			{null, "PING:def"},
			{null, "abc WHAT PING"},
			{null, "STOPPING"},
			{"PONG", "PING"},
			{"PONG", ":prefix PING"},
			{"PONG ", ":pre   PING "},
			{"pong", "ping"},
			{"pONg", "pINg"},
			{"PONG abc def", "PING abc def"},
			{"PONG :hello world", "PING :hello world"},
			{"PONG  two   :three four", ":one  PING  two   :three four"},
		};
		for (String[] cs : cases)
			assertEquals(cs[0], makePong(cs[1]));
	}
	
	
	private static String makePong(String s) {
		byte[] b = MamircConnector.makePongIfPing(Utils.toUtf8(s));
		if (b == null)
			return null;
		else
			return Utils.fromUtf8(b);
	}
	
}
