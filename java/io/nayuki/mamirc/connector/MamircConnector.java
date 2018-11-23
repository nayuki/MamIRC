/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.connector;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;


public final class MamircConnector {
	
	/*---- Stub main program ----*/
	
	public static void main(String[] args) throws SQLiteException {
		if (args.length != 1) {
			System.err.println("Usage: java io/nayuki/mamirc/connector/MamircConnector Configuration.sqlite");
			System.exit(1);
		}
		
		Logger.getLogger("com.almworks.sqlite4java").setLevel(Level.OFF);
		File config = new File(args[0]);
		if (!config.isFile())
			System.err.println("Non-existent configuration file: " + args[0]);
		
		try {
			new MamircConnector(config);
		} catch (Throwable e) {
			System.err.println(e.getClass().getSimpleName() + ": " + e.getMessage());
			System.exit(1);
		}
	}
	
	
	
	/*---- Constructor and its helpers ----*/
	
	private MamircConnector(File config) throws SQLiteException {
		// Fields to read from config
		int serverPort;
		File archiveDb;
		byte[] password;
		
		// Start reading config DB file
		SQLiteConnection dbCon = new SQLiteConnection(config);
		try {
			dbCon.openReadonly();
			SQLiteStatement getConfig = dbCon.prepare("SELECT value FROM main WHERE key=?");
			
			// Retrieve and parse various fields
			serverPort = Integer.parseInt(getConfigValue(getConfig, "connector server port"));
			archiveDb = new File(getConfigValue(getConfig, "archive database file"));
			
			String pswd = getConfigValue(getConfig, "connector password");
			password = new byte[pswd.length()];
			for (int i = 0; i < pswd.length(); i++) {
				char c = pswd.charAt(i);
				if (c > 0xFF)
					throw new IllegalArgumentException("Password character outside of range [U+00, U+FF]");
				password[i] = (byte)c;  // Truncate Unicode code point into byte
			}
			
		} finally {
			dbCon.dispose();
		}
	}
	
	
	private String getConfigValue(SQLiteStatement getConfig, String key) throws SQLiteException {
		try {
			getConfig.bind(1, key);
			if (getConfig.step())
				return getConfig.columnString(0);
			throw new IllegalArgumentException("Missing configuration key: \"" + key + "\"");
		} finally {
			getConfig.reset();
		}
	}
	
}
