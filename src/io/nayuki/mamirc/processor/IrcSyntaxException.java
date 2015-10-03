package io.nayuki.mamirc.processor;


@SuppressWarnings("serial")
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
