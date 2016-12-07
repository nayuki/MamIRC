/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;


// A wrapper around String where equals() and hashCode() are case-insensitive. Immutable.
// Useful for determining whether two nicknames or channel names are aliases of each other.
final class CaselessString {
	
	/*---- Fields ----*/
	
	// The string value converted to IRC's definition of lower case.
	public final String lowerCase;
	
	// The original string value passed to the constructor.
	public final String properCase;
	
	
	
	/*---- Constructors ----*/
	
	// Constructs a caseless string based on the given string.
	public CaselessString(String s) {
		if (s == null)
			throw new NullPointerException();
		properCase = s;
		
		// Convert to "lowercase", according to RFC 2812 section 2.2
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
	
	// Tests whether the given object is a caseless string with the same lowercase value.
	public boolean equals(Object obj) {
		return obj instanceof CaselessString && lowerCase.equals(((CaselessString)obj).lowerCase);
	}
	
	
	// Returns the hash code of the lowercase value.
	public int hashCode() {
		return lowerCase.hashCode();
	}
	
}
