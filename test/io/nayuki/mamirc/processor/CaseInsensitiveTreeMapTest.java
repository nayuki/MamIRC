/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * http://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.Test;


public final class CaseInsensitiveTreeMapTest {
	
	@Test public void testPut() {
		CaseInsensitiveTreeMap<Integer> m = new CaseInsensitiveTreeMap<>();
		assertNull(m.put("a", 3));
		m.checkStructure();
		assertEquals(1, m.size());
		assertNull(m.put("b", 0));
		m.checkStructure();
		assertEquals(2, m.size());
		assertEquals(3, (int)m.put("a", 2));
		m.checkStructure();
		assertEquals(2, m.size());
		assertEquals(0, (int)m.put("B", 5));
		m.checkStructure();
		assertEquals(2, m.size());
	}
	
	
	@Test public void testContainsKey() {
		CaseInsensitiveTreeMap<Integer> m = new CaseInsensitiveTreeMap<>();
		assertFalse(m.containsKey("xyz"));
		m.put("xyz", 1);
		assertTrue(m.containsKey("xyz"));
		assertTrue(m.containsKey("xyZ"));
		assertTrue(m.containsKey("XYZ"));
		m.put("XYZ", 5);
		m.checkStructure();
		assertTrue(m.containsKey("xyz"));
		assertTrue(m.containsKey("xyZ"));
		assertTrue(m.containsKey("XYZ"));
	}
	
	
	@Test public void testGet() {
		CaseInsensitiveTreeMap<Integer> m = new CaseInsensitiveTreeMap<>();
		m.put("a", 3);
		m.put("b", 0);
		assertEquals(3, (int)m.get("a"));
		assertEquals(3, (int)m.get("A"));
		assertEquals(0, (int)m.get("b"));
		assertEquals(0, (int)m.get("B"));
		m.put("a", 2);
		m.checkStructure();
		m.put("B", 5);
		m.checkStructure();
		assertEquals(2, (int)m.get("a"));
		assertEquals(2, (int)m.get("A"));
		assertEquals(5, (int)m.get("b"));
		assertEquals(5, (int)m.get("B"));
	}
	
	
	@Test public void testRemove() {
		CaseInsensitiveTreeMap<Integer> m = new CaseInsensitiveTreeMap<>();
		m.put("a", 3);
		m.put("b", 0);
		assertEquals(3, (int)m.remove("a"));
		m.checkStructure();
		assertEquals(1, m.size());
		assertNull(m.get("a"));
		assertNull(m.get("A"));
		assertEquals(0, (int)m.remove("B"));
		m.checkStructure();
		assertEquals(0, m.size());
		assertNull(m.get("a"));
		assertNull(m.get("A"));
		assertNull(m.get("b"));
		assertNull(m.get("B"));
	}
	
	
	@Test public void testRandom() {
		Map<String,String> lowerToProper = new HashMap<>();
		Map<String,Integer> lowerToData = new HashMap<>();
		CaseInsensitiveTreeMap<Integer> m = new CaseInsensitiveTreeMap<>();
		for (int i = 0; i < 1000000; i++) {
			String proper = randomKey();
			String lower = proper.toLowerCase();
			double op = rand.nextDouble();
			if (op < 0.4) {  // Query
				boolean has = lowerToProper.containsKey(lower);
				assertTrue(has == m.containsKey(proper));
				assertEquals(lowerToData.get(lower), m.get(proper));
			} else if (op < 0.7) {  // Put
				int val = rand.nextInt();
				lowerToProper.put(lower, proper);
				assertEquals(lowerToData.put(lower, val), m.put(proper, val));
			} else {  // Remove
				assertEquals(lowerToData.remove(lower), m.remove(proper));
				lowerToProper.remove(lower);
			}
			assertEquals(lowerToProper.size(), lowerToData.size());
			assertEquals(lowerToProper.size(), m.size());
			if (rand.nextDouble() < 0.001)
				m.checkStructure();
		}
	}
	
	
	private static String randomKey() {
		int x = rand.nextInt(26) + 'A';
		int y = rand.nextInt(26) + 'A';
		int z = rand.nextInt(4) + '0';
		if (rand.nextBoolean()) x += 32;
		if (rand.nextBoolean()) y += 32;
		char[] chrs = {(char)x, (char)y, (char)z};
		return new String(chrs);
	}
	
	
	private static Random rand = new Random();
	
}
