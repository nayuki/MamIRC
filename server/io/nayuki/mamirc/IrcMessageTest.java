package io.nayuki.mamirc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Assert;
import org.junit.Test;


public final class IrcMessageTest {
	
	@Test public void testZeroParam() {
		IrcMessage m = IrcMessage.parseLine("QUIT");
		assertTrue(m.prefix.isEmpty());
		assertEquals("QUIT", m.command);
		assertEquals(0, m.parameters.size());
	}
	
	@Test public void testOneParamA() {
		IrcMessage m = IrcMessage.parseLine("NICK John");
		assertTrue(m.prefix.isEmpty());
		assertEquals("NICK", m.command);
		assertEquals(1, m.parameters.size());
		assertEquals("John", m.parameters.get(0));
	}
	
	@Test public void testOneParamB() {
		IrcMessage m = IrcMessage.parseLine("NICK :John");
		assertTrue(m.prefix.isEmpty());
		assertEquals("NICK", m.command);
		assertEquals(1, m.parameters.size());
		assertEquals("John", m.parameters.get(0));
	}
	
	@Test public void testTwoParamA() {
		IrcMessage m = IrcMessage.parseLine("FOO 123 Abc");
		assertTrue(m.prefix.isEmpty());
		assertEquals("FOO", m.command);
		assertEquals(2, m.parameters.size());
		assertEquals("123", m.parameters.get(0));
		assertEquals("Abc", m.parameters.get(1));
	}
	
	@Test public void testTwoParamB() {
		IrcMessage m = IrcMessage.parseLine("FOO 123 :Abc");
		assertTrue(m.prefix.isEmpty());
		assertEquals("FOO", m.command);
		assertEquals(2, m.parameters.size());
		assertEquals("123", m.parameters.get(0));
		assertEquals("Abc", m.parameters.get(1));
	}
	
	@Test public void testOneParamSpaces() {
		IrcMessage m = IrcMessage.parseLine("NICK :John Smith");
		assertTrue(m.prefix.isEmpty());
		assertEquals("NICK", m.command);
		assertEquals(1, m.parameters.size());
		assertEquals("John Smith", m.parameters.get(0));
	}
	
	@Test public void testTwoParamSpaces() {
		IrcMessage m = IrcMessage.parseLine("FOO bar :alpha beta");
		assertTrue(m.prefix.isEmpty());
		assertEquals("FOO", m.command);
		assertEquals(2, m.parameters.size());
		assertEquals("bar", m.parameters.get(0));
		assertEquals("alpha beta", m.parameters.get(1));
	}
	
	@Test public void testLineWithPrefix() {
		IrcMessage m = IrcMessage.parseLine(":prefix PING");
		assertEquals("prefix", m.prefix.get().name);
		assertEquals("PING", m.command);
		assertEquals(0, m.parameters.size());
	}
	
	
	@Test public void testLineSyntaxErrors() {
		String[] CASES = {
			":prefixonly",
			":prefixonly  ",
			" :prefix PING",
		};
		for (String line : CASES) {
			try {
				IrcMessage.parseLine(line);
				Assert.fail();
			} catch (IllegalArgumentException e) {}  // Pass
		}
	}
	
	
	@Test public void testParsePrefix1() {
		IrcMessage m = IrcMessage.parseLine(":Alice PING");
		assertEquals("Alice", m.prefix.get().name);
		assertTrue(m.prefix.get().hostname.isEmpty());
		assertTrue(m.prefix.get().username.isEmpty());
	}
	
	@Test public void testParsePrefix2() {
		IrcMessage m = IrcMessage.parseLine(":Alice@Bob PING");
		assertEquals("Alice", m.prefix.get().name);
		assertEquals("Bob", m.prefix.get().hostname.get());
		assertTrue(m.prefix.get().username.isEmpty());
	}
	
	@Test public void testParsePrefix3() {
		IrcMessage m = IrcMessage.parseLine(":Alice!Carol@Bob PING");
		assertEquals("Alice", m.prefix.get().name);
		assertEquals("Bob", m.prefix.get().hostname.get());
		assertEquals("Carol", m.prefix.get().username.get());
	}
	
}
