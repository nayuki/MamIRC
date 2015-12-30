/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * http://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Assert;
import org.junit.Test;


public final class IrcLineTest {
	
	@Test public void testZeroParam() {
		IrcLine l = new IrcLine("QUIT");
		assertNull(l.prefixName);
		assertNull(l.prefixHostname);
		assertNull(l.prefixUsername);
		assertEquals("QUIT", l.command);
		assertEquals(0, l.parameters.size());
	}
	
	@Test public void testOneParamA() {
		IrcLine l = new IrcLine("NICK John");
		assertNull(l.prefixName);
		assertEquals("NICK", l.command);
		assertEquals(1, l.parameters.size());
		assertEquals("John", l.parameters.get(0));
	}
	
	@Test public void testOneParamB() {
		IrcLine l = new IrcLine("NICK :John");
		assertNull(l.prefixName);
		assertEquals("NICK", l.command);
		assertEquals(1, l.parameters.size());
		assertEquals("John", l.parameters.get(0));
	}
	
	@Test public void testTwoParamA() {
		IrcLine l = new IrcLine("FOO 123 Abc");
		assertNull(l.prefixName);
		assertEquals("FOO", l.command);
		assertEquals(2, l.parameters.size());
		assertEquals("123", l.parameters.get(0));
		assertEquals("Abc", l.parameters.get(1));
	}
	
	@Test public void testTwoParamB() {
		IrcLine l = new IrcLine("FOO 123 :Abc");
		assertNull(l.prefixName);
		assertEquals("FOO", l.command);
		assertEquals(2, l.parameters.size());
		assertEquals("123", l.parameters.get(0));
		assertEquals("Abc", l.parameters.get(1));
	}
	
	@Test public void testOneParamSpaces() {
		IrcLine l = new IrcLine("NICK :John Smith");
		assertNull(l.prefixName);
		assertEquals("NICK", l.command);
		assertEquals(1, l.parameters.size());
		assertEquals("John Smith", l.parameters.get(0));
	}
	
	@Test public void testTwoParamSpaces() {
		IrcLine l = new IrcLine("FOO bar :alpha beta");
		assertNull(l.prefixName);
		assertEquals("FOO", l.command);
		assertEquals(2, l.parameters.size());
		assertEquals("bar", l.parameters.get(0));
		assertEquals("alpha beta", l.parameters.get(1));
	}
	
	@Test public void testLineWithPrefix() {
		IrcLine l = new IrcLine(":prefix PING");
		assertEquals("prefix", l.prefixName);
		assertEquals("PING", l.command);
		assertEquals(0, l.parameters.size());
	}
	
	
	@Test public void testLineSyntaxErrors() {
		String[] cases = {
			":prefixonly",
			":prefixonly  ",
			" :prefix PING",
		};
		for (String line : cases) {
			try {
				new IrcLine(line);
				Assert.fail();
			} catch (IrcSyntaxException e) {}  // Pass
		}
	}
	
	
	@Test public void testParsePrefix1() {
		IrcLine l = new IrcLine(":Alice PING");
		assertEquals("Alice", l.prefixName);
		assertNull(l.prefixHostname);
		assertNull(l.prefixUsername);
	}
	
	@Test public void testParsePrefix2() {
		IrcLine l = new IrcLine(":Alice@Bob PING");
		assertEquals("Alice", l.prefixName);
		assertEquals("Bob", l.prefixHostname);
		assertNull(l.prefixUsername);
	}
	
	@Test public void testParsePrefix3() {
		IrcLine l = new IrcLine(":Alice!Carol@Bob PING");
		assertEquals("Alice", l.prefixName);
		assertEquals("Bob", l.prefixHostname);
		assertEquals("Carol", l.prefixUsername);
	}
	
}
