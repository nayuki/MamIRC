/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.common;

import java.io.File;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;


public class BasicConfigurationDatabase implements AutoCloseable {
	
	/*---- Fields ----*/
	
	protected SQLiteConnection database;
	protected SQLiteStatement getConfig;
	
	
	
	/*---- Constructor ----*/
	
	public BasicConfigurationDatabase(File file) throws SQLiteException {
		database = new SQLiteConnection(file);
		try {
			database.openReadonly();
			getConfig = database.prepare("SELECT value FROM main WHERE key=?");
		} catch (SQLiteException e) {
			close();
			throw e;
		}
	}
	
	
	
	/*---- Methods ----*/
	
	public String getValue(String key) throws SQLiteException {
		try {
			getConfig.bind(1, key);
			if (getConfig.step())
				return getConfig.columnString(0);
			throw new IllegalArgumentException("Missing configuration key: \"" + key + "\"");
		} finally {
			getConfig.reset();
		}
	}
	
	
	public void close() {
		database.dispose();
	}
	
}
