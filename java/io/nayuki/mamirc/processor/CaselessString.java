/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;


final class CaselessString {
	
	/*---- Fields ----*/
	
	public final String lowerCase;
	public final String properCase;
	
	
	
	/*---- Constructors ----*/
	
	public CaselessString(String s) {
		properCase = s;
		
		// Convert to "lower case", according to RFC 2812 section 2.2
		char[] temp = s.toCharArray();
		for (int i = 0; i < temp.length; i++) {
			char c = temp[i];
			if ('A' <= c && c <= 'Z')
				c += 'a' - 'A';
			else if (c == '[')
				c = '{';
			else if (c == ']')
				c = '}';
			else if (c == '\\')
				c = '|';
			else if (c == '~')
				c = '^';
			// Else don't modify
			temp[i] = c;
		}
		lowerCase = new String(temp);
	}
	
	
	
	/*---- Methods ----*/
	
	public boolean equals(Object obj) {
		return obj instanceof CaselessString && lowerCase.equals(((CaselessString)obj).lowerCase);
	}
	
	
	public int hashCode() {
		return lowerCase.hashCode();
	}
	
}
