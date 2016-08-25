/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;
import io.nayuki.mamirc.common.CleanLine;
import io.nayuki.mamirc.common.Event;


public final class ConvertEventsToMessages {
	
	public static void main(String[] args) throws SQLiteException {
		// Check and get arguments
		if (args.length != 2) {
			System.err.println("Usage: java io/nayuki/mamirc/processor/ConvertEventsToMessages MamircArchive.sqlite MamircMessages.sqlite");
			System.exit(1);
		}
		File inFile  = new File(args[0]);
		File outFile = new File(args[1]);
		
		// Open input and output database files
		Logger.getLogger("com.almworks.sqlite4java").setLevel(Level.OFF);
		SQLiteConnection database = new SQLiteConnection(inFile);
		database.open(false);
		MessageManager msgSink = new MessageManager(outFile, null);
		EventProcessor evtProc = new EventProcessor(msgSink);
		
		// Prepare query statements
		SQLiteStatement getConnectionIds = database.prepare("SELECT DISTINCT connectionId FROM events ORDER BY connectionId ASC");
		SQLiteStatement getConnectionEvents = database.prepare(
			"SELECT sequence, timestamp, type, data FROM events " +
			"WHERE connectionId=? ORDER BY sequence ASC");
		
		// For each connection ID in ascending order
		while (getConnectionIds.step()) {
			int conId = getConnectionIds.columnInt(0);
			System.err.println("Connection " + conId);
			getConnectionEvents.bind(1, conId);
			
			// For each event in this connection, in sequential order
			int eventCount = 0;
			while (getConnectionEvents.step()) {
				Event ev = new Event(
					conId,
					getConnectionEvents.columnInt(0),
					getConnectionEvents.columnLong(1),
					Event.Type.fromOrdinal(getConnectionEvents.columnInt(2)),
					new CleanLine(getConnectionEvents.columnBlob(3), false));
				evtProc.processEvent(ev);
				eventCount++;
			}
			System.err.println("  " + eventCount + " events");
			getConnectionEvents.reset();
		}
		
		// Clean up
		msgSink.dispose();
		database.dispose();
	}
	
}
