/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.connector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;
import io.nayuki.mamirc.common.Utils;


public final class MamircConnectorTest {
	
	@Test public void testMakePongIfPing() {
		assertNull(makePong(""));
		assertNull(makePong("PONG"));
		assertNull(makePong("PINGU"));
		assertNull(makePong(" PING"));
		assertNull(makePong(" :abc PING"));
		assertNull(makePong("PING:def"));
		assertNull(makePong("abc WHAT PING"));
		assertNull(makePong("STOPPING"));
		assertEquals("PONG", makePong("PING"));
		assertEquals("PONG", makePong(":prefix PING"));
		assertEquals("PONG ", makePong(":pre   PING "));
		assertEquals("pong", makePong("ping"));
		assertEquals("pONg", makePong("pINg"));
		assertEquals("PONG abc def", makePong("PING abc def"));
		assertEquals("PONG :hello world", makePong("PING :hello world"));
		assertEquals("PONG  two   :three four", makePong(":one  PING  two   :three four"));
	}
	
	
	private static String makePong(String s) {
		byte[] b = MamircConnector.makePongIfPing(Utils.toUtf8(s));
		if (b == null)
			return null;
		else
			return Utils.fromUtf8(b);
	}
	
}
