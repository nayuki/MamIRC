/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;
import io.nayuki.mamirc.common.Event;
import io.nayuki.mamirc.common.WorkerThread;


final class DatabaseWriteWorker extends WorkerThread {
	
	/*---- Fields ----*/
	
	private final File databaseFile;
	private SQLiteConnection database;
	
	
	
	/*---- Constructor ----*/
	
	// This performs some initial database operations synchronously.
	// If an exception is thrown (e.g. cannot write), then the caller can
	// abort the initialization more easily (with fewer worker threads running).
	public DatabaseWriteWorker(File file) throws SQLiteException {
		super("Database Writer");
		databaseFile = Objects.requireNonNull(file);
		database = new SQLiteConnection(file);
		try {
			database.open(true);
			database.exec("PRAGMA journal_mode = WAL");
			
			database.exec("CREATE TABLE IF NOT EXISTS profiles(\n" +
				"	id INTEGER NOT NULL PRIMARY KEY,\n" +
				"	name TEXT NOT NULL\n" +
				")");
			
			database.exec("CREATE TABLE IF NOT EXISTS windows(\n" +
				"	id INTEGER NOT NULL PRIMARY KEY,\n" +
				"	profile_id INTEGER NOT NULL REFERENCES profiles(id),\n" +
				"	canonical_name TEXT NOT NULL,\n" +
				"	display_name TEXT NOT NULL,\n" +
				"	UNIQUE(profile_id, canonical_name)\n" +
				")");
			
			database.exec("CREATE TABLE IF NOT EXISTS messages(\n" +
				"	window_id INTEGER NOT NULL REFERENCES windows(id),\n" +
				"	sequence INTEGER NOT NULL,\n" +
				"	connection_id INTEGER NOT NULL,\n" +
				"	data TEXT NOT NULL,\n" +
				"	PRIMARY KEY(window_id, sequence)\n" +
				")");
			
			database.exec("CREATE TABLE IF NOT EXISTS fully_processed_connections(\n" +
				"	connection_id INTEGER NOT NULL PRIMARY KEY\n" +
				")");
			
			database.exec("CREATE INDEX IF NOT EXISTS messages_from_unfinished_connections_index_windowid_sequence\n" +
				"	ON messages_from_unfinished_connections(window_id, sequence)");
			
			database.exec("DELETE FROM messages WHERE ");
			
		} finally {
			database.dispose();
		}
		start();
	}
	
	
	protected void runInner() {}
	
	
	public void shutdown() {}
	
}
