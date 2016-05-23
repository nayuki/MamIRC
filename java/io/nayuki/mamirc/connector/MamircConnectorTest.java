/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.connector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
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
			{null, ":nick!user@host PRIVMSG #chan :PING"},
			{"PONG", "PING"},
			{"PONG", ":prefix PING"},
			{"PONG ", ":pre   PING "},
			{"pong", "ping"},
			{"pONg", "pINg"},
			{"PONG abc def", "PING abc def"},
			{"PONG :hello world", "PING :hello world"},
			{"PONG  two   :three four", ":one  PING  two   :three four"},
			{"PONG PONG PING", "PING PONG PING"},
			{"pong PIng", ":PING ping PIng"},
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
	
	
	@Test public void testEqualsTimingSafe() {
		byte[][][] cases = {
			{{}, {}},
			{{0}, {0}},
			{{0}, {1}},
			{{0}, {0, 1}},
			{{0, 2}, {0}},
			{{3, 0}, {0, 3}},
			{{-1}, {127}},
			{{0, 0}, {0, 0, 0}},
		};
		for (byte[][] cs : cases)
			assertTrue(ProcessorReaderThread.equalsTimingSafe(cs[0], cs[1]) == Arrays.equals(cs[0], cs[1]));
	}
	
}
