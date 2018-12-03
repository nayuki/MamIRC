/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;


public final class MamircProcessor {
	
	/*---- Stub main program ----*/
	
	public static void main(String[] args) throws SQLiteException {
		if (args.length != 1)
			errorExit("Usage: java io/nayuki/mamirc/processor/MamircProcessor Configuration.sqlite");
		
		File config = new File(args[0]);
		if (!config.isFile())
			errorExit("Non-existent configuration file: " + args[0]);
		
		Logger.getLogger("com.almworks.sqlite4java").setLevel(Level.OFF);
		try {
			new MamircProcessor(config);
		} catch (Throwable e) {
			errorExit(e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}
	
	
	// Only called by main().
	private static void errorExit(String msg) {
		System.err.println(msg);
		System.exit(1);
	}
	
	
	
	/*---- Constructor and its helpers ----*/
	
	private MamircProcessor(File config) throws SQLiteException, IOException {
		initializeTables(config);
	}
	
	
	private static void initializeTables(File config) throws SQLiteException {
		SQLiteConnection dbCon = new SQLiteConnection(config);
		try {
			dbCon.open(false);
			
			dbCon.exec("CREATE TABLE IF NOT EXISTS network_profiles(\n" +
				"	id INTEGER NOT NULL PRIMARY KEY,\n" +
				"	profile_name TEXT NOT NULL,\n" +
				"	username TEXT NOT NULL,\n" +
				"	real_name TEXT NOT NULL,\n" +
				"	nickserv_password TEXT\n" +
				")");
			
			dbCon.exec("CREATE TABLE IF NOT EXISTS network_nicknames(\n" +
				"	profile_id INTEGER NOT NULL REFERENCES network_profiles(id),\n" +
				"	sequence INTEGER NOT NULL,\n" +
				"	nickname TEXT NOT NULL,\n" +
				"	PRIMARY KEY(profile_id, sequence)\n" +
				")");
			
			dbCon.exec("CREATE TABLE IF NOT EXISTS network_channels(\n" +
				"	profile_id INTEGER NOT NULL REFERENCES network_profiles(id),\n" +
				"	channel_name TEXT NOT NULL,\n" +
				"	channel_password TEXT,\n" +
				"	PRIMARY KEY(profile_id, channel_name)\n" +
				")");
			
		} finally {
			dbCon.dispose();
		}
	}
	
}
