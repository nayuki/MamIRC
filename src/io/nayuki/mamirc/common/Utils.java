package io.nayuki.mamirc.common;

import java.nio.charset.Charset;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;


public final class Utils {
	
	
	public static byte[] toUtf8(String s) {
		return s.getBytes(UTF8_CHARSET);
	}
	
	
	public static String fromUtf8(byte[] b) {
		return new String(b, UTF8_CHARSET);
	}
	
	
	public static void stepStatement(SQLiteStatement statement, boolean expectingResult) throws SQLiteException {
		if (statement.step() != expectingResult)
			throw new AssertionError();
	}
	
	
	private static final Charset UTF8_CHARSET = Charset.forName("UTF-8");
	
}
