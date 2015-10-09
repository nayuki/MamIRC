package io.nayuki.mamirc.processor;


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
