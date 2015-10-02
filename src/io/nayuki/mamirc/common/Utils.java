package io.nayuki.mamirc.common;

import java.nio.charset.Charset;


public final class Utils {
	
	
	public static byte[] toUtf8(String s) {
		return s.getBytes(UTF8_CHARSET);
	}
	
	
	public static String fromUtf8(byte[] b) {
		return new String(b, UTF8_CHARSET);
	}
	
	
	private static final Charset UTF8_CHARSET = Charset.forName("UTF-8");
	
}
