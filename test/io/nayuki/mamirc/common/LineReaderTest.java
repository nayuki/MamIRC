package io.nayuki.mamirc.common;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;


public final class LineReaderTest {
	
	@Test public void testBlank() {
		test("", "");
	}
	
	@Test public void testOneLine() {
		test("aa", "aa");
	}
	
	@Test public void testBlankTailCr() {
		test("b\r", "b", "");
	}
	
	@Test public void testBlankTailLf() {
		test("b\n", "b", "");
	}
	
	@Test public void testBlankTailCrLf() {
		test("b\r\n", "b", "");
	}
	
	@Test public void testTwoLinesCr() {
		test("ba\rcd", "ba", "cd");
	}
	
	@Test public void testTwoLinesLf() {
		test("ba\ncd", "ba", "cd");
	}
	
	@Test public void testTwoLinesCrLf() {
		test("ba\r\ncd", "ba", "cd");
	}
	
	@Test public void testAssorted() {
		test("the\rquick\nbrown\r\nfox\n\njumps\r\n\nover\r\rthelazydog", "the", "quick", "brown", "fox", "", "jumps", "", "over", "", "thelazydog");
	}
	
	@Test public void testLfCr0() {
		test("ba\n\r", "ba", "", "");
	}
	
	@Test public void testLfCr1() {
		test("ba\n\rcd", "ba", "", "cd");
	}
	
	
	private static void test(String raw, String... lines) {
		try {
			LineReader reader = new LineReader(new ByteArrayInputStream(Utils.toUtf8(raw)));
			for (String line : lines) {
				byte[] actual = reader.readLine();
				byte[] expected = Utils.toUtf8(line);
				Assert.assertNotNull(actual != null);
				Assert.assertArrayEquals(expected, actual);
			}
			Assert.assertNull(reader.readLine());
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}
	
}
