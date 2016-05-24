/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;


/* 
 * Miscellaneous helper functions that are used in numerous places and don't have a common theme.
 */
public final class Utils {
	
	// Returns a new array of bytes from encoding the given string in UTF-8.
	public static byte[] toUtf8(String s) {
		if (s == null)
			throw new NullPointerException();
		return s.getBytes(StandardCharsets.UTF_8);
	}
	
	
	// Returns the string from decoding the given bytes in UTF-8.
	public static String fromUtf8(byte[] b) {
		if (b == null)
			throw new NullPointerException();
		return new String(b, StandardCharsets.UTF_8);
	}
	
	
	// Steps the given SQLite statement and checks whether the step should produce a result or not.
	// Additionally if no result is expected, the statement is immediately reset (for easier reuse).
	public static void stepStatement(SQLiteStatement statement, boolean expectingResult) throws SQLiteException {
		if (statement == null)
			throw new NullPointerException();
		boolean hasResult = statement.step();
		if (expectingResult && !hasResult)
			throw new AssertionError("Expected database row as result but got nothing");
		else if (!expectingResult) {
			if (hasResult)
				throw new AssertionError("Expected nothing as result but got a database row");
			statement.reset();
		}
	}
	
	
	// Returns the argument if it is in the range [0, 65535], otherwise throws an exception.
	public static int checkPortNumber(int port) {
		if ((port & 0xFFFF) == port)
			return port;
		else
			throw new IllegalArgumentException("Invalid TCP port number: " + port);
	}
	
	
	// Logger for events and debugging.
	public static final Logger logger = Logger.getLogger("io.nayuki.mamirc");
	
	
	private static Thread consoleLogLevelChanger = null;
	
	
	public static synchronized void startConsoleLogLevelChanger() {
		if (consoleLogLevelChanger != null)
			throw new IllegalStateException("Console log level changer already running");
		
		consoleLogLevelChanger = new Thread() {
			public void run() {
				Level[] logLevels = {
					Level.OFF,
					Level.SEVERE,
					Level.WARNING,
					Level.INFO,
					Level.FINE,
					Level.FINER,
					Level.FINEST,
					Level.ALL,
				};
				
				try {
					BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
					String helpMsg = "To change the level of logging events shown, type an integer from 0 (OFF) to 7 (ALL) and press enter.";
					System.err.println(helpMsg);
					while (true) {
						String line = in.readLine();
						if (line == null)
							break;
						if (line.equals(""))
							continue;
						try {
							int val = Integer.parseInt(line);
							if (val < 0 || val > 7)
								throw new IllegalArgumentException();
							Level lvl = logLevels[val];
							logger.setLevel(lvl);
							System.err.println("Now showing logging events at level " + lvl.getName());
						} catch (IllegalArgumentException e) {  // Includes NumberFormatException
							System.out.println(helpMsg);
						}
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		};
		consoleLogLevelChanger.start();
	}
	
	
	
	// Not instantiable.
	private Utils() {}
	
}
