/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.io.File;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;
import io.nayuki.mamirc.common.Utils;


final class MessageSink {
	
	/*---- Fields ----*/
	
	private SQLiteConnection database;
	
	private SQLiteStatement getWindowId;
	private SQLiteStatement getMaxSequence;
	private SQLiteStatement insertWindow;
	private SQLiteStatement insertMessage;
	
	private int nextWindowId;
	
	private UpdateManager updateMgr;
	
	
	
	/*---- Constructors ----*/
	
	public MessageSink(File dbFile, UpdateManager updateMgr) throws SQLiteException {
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
