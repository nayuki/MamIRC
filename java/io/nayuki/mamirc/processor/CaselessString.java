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
		lowerCase = s.toLowerCase();
		properCase = s;
	}
	
	
	
	/*---- Methods ----*/
	
	public boolean equals(Object obj) {
		return obj instanceof CaselessString && lowerCase.equals(((CaselessString)obj).lowerCase);
	}
	
	
	public int hashCode() {
		return lowerCase.hashCode();
	}
	
}
