package io.nayuki.mamirc.processor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Assert;
import org.junit.Test;


public final class IrcLineTest {
	
	/*---- Testing constructor IrcLine() ----*/
	
	@Test public void testZeroParam() {
		IrcLine l = new IrcLine("QUIT");
		assertNull(l.prefix);
		assertEquals("QUIT", l.command);
		assertEquals(0, l.parameters.size());
	}
	
	@Test public void testOneParamA() {
		IrcLine l = new IrcLine("NICK John");
		assertNull(l.prefix);
		assertEquals("NICK", l.command);
		assertEquals(1, l.parameters.size());
		assertEquals("John", l.parameters.get(0));
	}
	
	@Test public void testOneParamB() {
		IrcLine l = new IrcLine("NICK :John");
		assertNull(l.prefix);
		assertEquals("NICK", l.command);
		assertEquals(1, l.parameters.size());
		assertEquals("John", l.parameters.get(0));
	}
	
	@Test public void testTwoParamA() {
		IrcLine l = new IrcLine("FOO 123 Abc");
		assertNull(l.prefix);
		assertEquals("FOO", l.command);
		assertEquals(2, l.parameters.size());
		assertEquals("123", l.parameters.get(0));
		assertEquals("Abc", l.parameters.get(1));
	}
	
	@Test public void testTwoParamB() {
		IrcLine l = new IrcLine("FOO 123 :Abc");
		assertNull(l.prefix);
		assertEquals("FOO", l.command);
		assertEquals(2, l.parameters.size());
		assertEquals("123", l.parameters.get(0));
		assertEquals("Abc", l.parameters.get(1));
	}
	
	@Test public void testOneParamSpaces() {
		IrcLine l = new IrcLine("NICK :John Smith");
		assertNull(l.prefix);
		assertEquals("NICK", l.command);
		assertEquals(1, l.parameters.size());
		assertEquals("John Smith", l.parameters.get(0));
	}
	
	@Test public void testTwoParamSpaces() {
		IrcLine l = new IrcLine("FOO bar :alpha beta");
		assertNull(l.prefix);
		assertEquals("FOO", l.command);
		assertEquals(2, l.parameters.size());
		assertEquals("bar", l.parameters.get(0));
		assertEquals("alpha beta", l.parameters.get(1));
	}
	
	@Test public void testLineWithPrefix() {
		IrcLine l = new IrcLine(":prefix PING");
		assertEquals("prefix", l.prefix);
		assertEquals("PING", l.command);
		assertEquals(0, l.parameters.size());
	}
	
	
	@Test public void testLineSyntaxErrors() {
		String[] cases = {
			" :prefix PING",
			":prefix  PING",
			"HELLO ",
			"HELLO  ",
			"HELLO WORLD ",
			"HELLO  world",
			"HELLO new  world",
			"HELLO  new  world",
			"HELLO  new  world",
		};
		for (String line : cases) {
			try {
				new IrcLine(line);
				Assert.fail();
			} catch (IllegalArgumentException e) {}  // Pass
		}
	}
	
	
	/*---- Testing constructor IrcLine.Prefix() ----*/
	
	@Test public void testParsePrefix1() {
		IrcLine.Prefix p = new IrcLine.Prefix("Alice");
		assertEquals("Alice", p.name);
		assertNull(p.hostname);
		assertNull(p.username);
	}
	
	@Test public void testParsePrefix2() {
		IrcLine.Prefix p = new IrcLine.Prefix("Alice@Bob");
		assertEquals("Alice", p.name);
		assertEquals("Bob", p.hostname);
		assertNull(p.username);
	}
	
	@Test public void testParsePrefix3() {
		IrcLine.Prefix p = new IrcLine.Prefix("Alice!Carol@Bob");
		assertEquals("Alice", p.name);
		assertEquals("Bob", p.hostname);
		assertEquals("Carol", p.username);
	}
	
}
