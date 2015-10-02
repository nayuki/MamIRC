package io.nayuki.mamirc.processor;


public final class IrcSyntaxException extends RuntimeException {
	
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
