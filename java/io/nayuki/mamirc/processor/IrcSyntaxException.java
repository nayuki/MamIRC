/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;


/* 
 * Thrown when an IRC line does not conform to the syntax rules, or a parser expects an argument that was not found.
 */
@SuppressWarnings("serial")
final class IrcSyntaxException extends RuntimeException {
	
	public IrcSyntaxException() {
		super();
	}
	
	
	public IrcSyntaxException(String msg, Throwable cause) {
		super(msg, cause);
	}
	
	
	public IrcSyntaxException(String msg) {
		super(msg);
	}
	
}
