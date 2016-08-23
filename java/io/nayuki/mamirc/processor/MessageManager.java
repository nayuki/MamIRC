/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;
import io.nayuki.mamirc.common.Utils;


final class MessageManager {
	
	/*---- Fields ----*/
	
	private final File databaseFile;
	
	private SQLiteConnection database;
	
	private SQLiteStatement getWindowId;
	private SQLiteStatement getMaxSequence;
	private SQLiteStatement insertWindow;
	private SQLiteStatement insertMessage;
	
	private int nextWindowId;
	
	private UpdateManager updateMgr;
	
	
	
	/*---- Constructors ----*/
	
	public MessageManager(File dbFile, UpdateManager updateMgr) throws SQLiteException {
		databaseFile = dbFile;
		this.updateMgr = updateMgr;
		
		database = new SQLiteConnection(dbFile);
		database.open(true);
		database.exec("CREATE TABLE IF NOT EXISTS windows("
			+ "id INTEGER, profile TEXT NOT NULL, partyProperCase TEXT NOT NULL, partyLowerCase TEXT NOT NULL, "
			+ "PRIMARY KEY(id))");
		database.exec("CREATE TABLE IF NOT EXISTS messages("
			+ "windowId INTEGER, sequence INTEGER, connectionId INTEGER NOT NULL, timestamp INTEGER NOT NULL, data TEXT NOT NULL, "
			+ "PRIMARY KEY(windowId, sequence), FOREIGN KEY(windowId) REFERENCES windows(id))");
		
		SQLiteStatement getMaxWinId = database.prepare("SELECT max(id) FROM windows");
		Utils.stepStatement(getMaxWinId, true);
		if (getMaxWinId.columnNull(0))
			nextWindowId = 0;
		else
			nextWindowId = getMaxWinId.columnInt(0) + 1;
		getMaxWinId.dispose();
		
		getWindowId    = database.prepare("SELECT id FROM windows WHERE profile=? AND partyLowerCase=?");
		getMaxSequence = database.prepare("SELECT max(sequence) FROM messages WHERE windowId=?");
		insertWindow   = database.prepare("INSERT INTO windows VALUES(?,?,?,?)");
		insertMessage  = database.prepare("INSERT INTO messages VALUES(?,?,?,?,?)");
		database.exec("BEGIN TRANSACTION");
	}
	
	
	
	/*---- Methods ----*/
	
	public void addMessage(String profile, String party, int conId, long timestamp, String type, String... args) {
		if (profile == null || party == null || type == null || args == null)
			throw new NullPointerException();
		try {
			int winId = getOrAddWindowId(profile, party);
			int seq = getNextSequence(winId);
			insertMessage.bind(1, winId);
			insertMessage.bind(2, seq);
			insertMessage.bind(3, conId);
			insertMessage.bind(4, timestamp);
			StringBuilder sb = new StringBuilder(type);
			for (String arg : args)
				sb.append("\n").append(arg);
			insertMessage.bind(5, sb.toString());
			Utils.stepStatement(insertMessage, false);
		} catch (SQLiteException e) {}
		
		if (updateMgr != null) {
			Object[] temp = new Object[5 + args.length];
			temp[0] = "WINMSG";
			temp[1] = profile;
			temp[2] = party;
			temp[3] = timestamp;
			temp[4] = type;
			System.arraycopy(args, 0, temp, 5, args.length);
			updateMgr.addUpdate(temp);
		}
	}
	
	
	public Object listAllWindowsAsJson() throws SQLiteException {
		SQLiteConnection db = new SQLiteConnection(databaseFile);
		try {
			db.open(false);
			SQLiteStatement windowsQuery = db.prepare(
				"SELECT id, profile, partyProperCase, max(sequence) " +
				"FROM windows JOIN messages ON windows.id=messages.windowId GROUP BY id");
			SQLiteStatement timestampQuery = db.prepare(
				"SELECT timestamp FROM messages WHERE windowId=? AND sequence=?");
			
			List<Object> result = new ArrayList<>();
			while (windowsQuery.step()) {
				int windowId = windowsQuery.columnInt(0);
				String profile = windowsQuery.columnString(1);
				String party = windowsQuery.columnString(2);
				int nextSequence = windowsQuery.columnInt(3) + 1;
				timestampQuery.bind(1, windowId);
				timestampQuery.bind(2, nextSequence - 1);
				Utils.stepStatement(timestampQuery, true);
				long lastTimestamp = timestampQuery.columnLong(0);
				timestampQuery.reset();
				result.add(Arrays.asList(profile, party, nextSequence, lastTimestamp));
			}
			return result;
		} finally {
			db.dispose();
		}
	}
	
	
	public Object getWindowMessagesAsJson(String profile, String party, int start, int end) throws SQLiteException {
		if (start < 0)
			return "Error: Negative start index";
		if (end < start)
			return "Error: End index less that start index";
		
		SQLiteConnection db = new SQLiteConnection(databaseFile);
		try {
			db.open(false);
			SQLiteStatement windowQuery = db.prepare(
				"SELECT id FROM windows WHERE profile=? AND partyProperCase=?");
			windowQuery.bind(1, profile);
			windowQuery.bind(2, party);
			if (!windowQuery.step())
				return "Error: Window does not exist";
			int windowId = windowQuery.columnInt(0);
			
			List<Object> result = new ArrayList<>();
			SQLiteStatement messagesQuery = db.prepare(
				"SELECT timestamp, data FROM messages " +
				"WHERE windowId=? AND ?<=sequence AND sequence<? " +
				"ORDER BY sequence ASC");
			messagesQuery.bind(1, windowId);
			messagesQuery.bind(2, start);
			messagesQuery.bind(3, end);
			while (messagesQuery.step()) {
				long time = messagesQuery.columnLong(0);
				String[] parts = messagesQuery.columnString(1).split("\n", -1);
				Object[] temp = new Object[parts.length + 1];
				temp[0] = time;
				System.arraycopy(parts, 0, temp, 1, parts.length);
				result.add(Arrays.asList(temp));
			}
			return result;
		} finally {
			db.dispose();
		}
	}
	
	
	public void dispose() throws SQLiteException {
		database.exec("COMMIT TRANSACTION");
		database.dispose();
	}
	
	
	private int getOrAddWindowId(String profile, String party) throws SQLiteException {
		if (profile == null || party == null)
			throw new NullPointerException();
		String partyLower = new CaselessString(party).lowerCase;
		getWindowId.bind(1, profile);
		getWindowId.bind(2, partyLower);
		int result;
		if (getWindowId.step())
			result = getWindowId.columnInt(0);
		else {
			result = nextWindowId;
			insertWindow.bind(1, result);
			insertWindow.bind(2, profile);
			insertWindow.bind(3, party);
			insertWindow.bind(4, partyLower);
			Utils.stepStatement(insertWindow, false);
			nextWindowId++;
		}
		getWindowId.reset();
		return result;
	}
	
	
	private int getNextSequence(int winId) throws SQLiteException {
		getMaxSequence.bind(1, winId);
		Utils.stepStatement(getMaxSequence, true);
		int result;
		if (getMaxSequence.columnNull(0))
			result = 0;
		else
			result = getMaxSequence.columnInt(0) + 1;
		getMaxSequence.reset();
		return result;
	}
	
}
