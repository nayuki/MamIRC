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
	
	
	// Steps the given SQLite statement and checks whether the step should produce a result or not.
	// Additionally if no result is expected, the statement is immediately reset (for easier reuse).
	public static void stepStatement(SQLiteStatement statement, boolean expectingResult) throws SQLiteException {
		if (statement.step() != expectingResult)
			throw new AssertionError();
		if (!expectingResult)
			statement.reset();
	}
	
	
	private static final Charset UTF8_CHARSET = Charset.forName("UTF-8");
	
}
