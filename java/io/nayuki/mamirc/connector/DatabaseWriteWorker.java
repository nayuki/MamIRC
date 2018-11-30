/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.connector;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;
import io.nayuki.mamirc.common.Event;


final class DatabaseWriteWorker extends Thread {
	
	/*---- Fields ----*/
	
	private final File databaseFile;
	private SQLiteConnection database;
	private SQLiteStatement addEvent;
	private SQLiteStatement addUnfinCon;
	private SQLiteStatement delUnfinCon;
	
	private int nextConnectionId;
	private Queue<Event> queue = new ArrayDeque<>();
	private boolean isWriting = false;
	
	
	
	/*---- Constructor ----*/
	
	public DatabaseWriteWorker(File file) throws SQLiteException {
		super("Database Writer");
		databaseFile = Objects.requireNonNull(file);
		database = new SQLiteConnection(file);
		try {
			database.open(true);
			database.exec("PRAGMA journal_mode = WAL");
			
			database.exec("CREATE TABLE IF NOT EXISTS events(\n" +
				"	connectionId INTEGER NOT NULL,\n" +
				"	sequence INTEGER NOT NULL,\n" +
				"	timestamp INTEGER NOT NULL,\n" +
				"	type INTEGER NOT NULL,\n" +
				"	data BLOB NOT NULL,\n" +
				"	PRIMARY KEY(connectionId, sequence)\n" +
				")");
			
			database.exec("CREATE TABLE IF NOT EXISTS unfinished_connections(\n" +
				"	connectionId INTEGER NOT NULL PRIMARY KEY\n" +
				")");
			
			database.exec("DELETE FROM unfinished_connections");
			
			SQLiteStatement getNextConId = database.prepare(
				"SELECT ifnull(max(connectionId)+1,0) FROM events");
			if (!getNextConId.step())
				throw new AssertionError();
			nextConnectionId = getNextConId.columnInt(0);
			
		} finally {
			database.dispose();
		}
		start();
	}
	
	
	public synchronized int getNextConnectionIdAndIncrement() {
		return nextConnectionId++;
	}
	
	
	public void run() {
		database = new SQLiteConnection(databaseFile);
		try {
			database.open(false);
			try {
				database.setBusyTimeout(60000);
				database.exec("BEGIN IMMEDIATE");
				addEvent    = database.prepare("INSERT INTO events VALUES(?,?,?,?,?)");
				addUnfinCon = database.prepare("INSERT INTO unfinished_connections VALUES(?)");
				delUnfinCon = database.prepare("DELETE FROM unfinished_connections WHERE connectionId=?");
				
				while (handleEvent());
				database.exec("DELETE FROM unfinished_connections");
				database.exec("COMMIT TRANSACTION");
				synchronized(this) {
					isWriting = false;
					notifyAll();
				}
			} finally {
				database.dispose();
			}
		} catch (SQLiteException|InterruptedException e) {}
	}
	
	
	private boolean handleEvent() throws SQLiteException, InterruptedException {
		Event ev;
		synchronized(this) {
			while (queue.isEmpty())
				wait();
			ev = queue.remove();
			isWriting = true;
		}
		if (ev == TERMINATOR)
			return false;
		
		addEvent.bind(1, ev.connectionId);
		addEvent.bind(2, ev.sequence);
		addEvent.bind(3, ev.timestamp);
		addEvent.bind(4, ev.type.ordinal());
		addEvent.bind(5, ev.line);
		if (addEvent.step())
			throw new AssertionError();
		addEvent.reset();
		
		if (ev.type == Event.Type.CONNECTION) {
			if (ev.sequence == 0) {
				addUnfinCon.bind(1, ev.connectionId);
				if (addUnfinCon.step())
					throw new AssertionError();
				addUnfinCon.reset();
			} else if (new String(ev.line, StandardCharsets.UTF_8).equals("closed")) {
				delUnfinCon.bind(1, ev.connectionId);
				if (delUnfinCon.step())
					throw new AssertionError();
				delUnfinCon.reset();
			}
		}
		
		boolean isEmpty;
		synchronized(this) {
			isEmpty = queue.isEmpty();
		}
		if (isEmpty) {
			database.exec("COMMIT TRANSACTION");
			synchronized(this) {
				isWriting = false;
				notifyAll();
			}
			database.exec("BEGIN IMMEDIATE");
		}
		return true;
	}
	
	
	public synchronized void writeEvent(Event ev) {
		Objects.requireNonNull(ev);
		queue.add(ev);
		notifyAll();
	}
	
	
	public synchronized void flush() throws InterruptedException {
		while (!queue.isEmpty() || isWriting)
			wait();
	}
	
	
	public synchronized void terminate() {
		writeEvent(TERMINATOR);
	}
	
	
	// A sentinel object that is only compared by reference. Its dereferenced values are never used.
	private static final Event TERMINATOR = new Event(0, 0, Event.Type.CONNECTION, new byte[0]);
	
}
