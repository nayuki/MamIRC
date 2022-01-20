package io.nayuki.mamirc;


@SuppressWarnings("serial")
final class IrcSyntaxException extends IllegalArgumentException {
	
	public IrcSyntaxException(String msg) {
		super(msg);
	}
	
}
