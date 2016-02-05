/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;


final class CaseInsensitiveTreeMap<V> extends AbstractMap<String,V> {
	
	/*---- Fields ----*/
	
	private Map<String,V> data;
	private Map<String,String> caseConverter;
	
	
	/*---- Constructor ----*/
	
	public CaseInsensitiveTreeMap() {
		data = new TreeMap<>();
		caseConverter = new HashMap<>();
	}
	
	
	/*---- Methods ----*/
	
	public boolean containsKey(Object obj) {
		return obj instanceof String && caseConverter.containsKey(((String)obj).toLowerCase());
	}
	
	
	public V get(Object obj) {
		if (!(obj instanceof String))
			return null;
		String lower = ((String)obj).toLowerCase();
		String proper = caseConverter.get(lower);
		if (proper == null)
			return null;
		return data.get(proper);
	}
	
	
	public V put(String key, V val) {
		String lower = key.toLowerCase();
		String oldProper = caseConverter.put(lower, key);
		if (oldProper == null)
			return data.put(key, val);
		else {
			V result = data.remove(oldProper);
			data.put(key, val);
			return result;
		}
	}
	
	
	public V remove(Object obj) {
		if (!(obj instanceof String))
			return null;
		String lower = ((String)obj).toLowerCase();
		String proper = caseConverter.get(lower);
		if (proper == null)
			return null;
		else {
			caseConverter.remove(lower);
			return data.remove(proper);
		}
	}
	
	
	public Set<Map.Entry<String,V>> entrySet() {
		return data.entrySet();
	}
	
	
	void checkStructure() {
		if (data.size() != caseConverter.size())
			throw new AssertionError();
		Set<String> properNames = new HashSet<>();
		for (String lower : caseConverter.keySet()) {
			String proper = caseConverter.get(lower);
			if (!properNames.add(proper))
				throw new AssertionError();
			if (!data.containsKey(proper))
				throw new AssertionError();
		}
	}
	
}
