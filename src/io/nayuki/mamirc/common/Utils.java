package io.nayuki.mamirc.common;

import java.nio.charset.Charset;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;


/* 
 * Miscellaneous helper functions that are used in numerous places and don't have a common theme.
 */
public final class Utils {
	
	// Returns a new array of bytes from encoding the given string in UTF-8.
	public static byte[] toUtf8(String s) {
		return s.getBytes(UTF8_CHARSET);
	}
	
	
	// Returns the string from decoding the given bytes in UTF-8.
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
	
	
	// Returns the argument if it is in the range [0, 65535], otherwise throws an exception.
	public static int checkPortNumber(int port) {
		if (port >= 0 && port <= 0xFFFF)
			return port;
		else
			throw new IllegalArgumentException("Invalid TCP port number: " + port);
	}
	
	
	// Character set object for UTF-8.
	private static final Charset UTF8_CHARSET = Charset.forName("UTF-8");
	
	
	// Not instantiable.
	private Utils() {}
	
}
