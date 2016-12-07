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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;
import io.nayuki.mamirc.common.BackendConfiguration;
import io.nayuki.mamirc.common.CleanLine;
import io.nayuki.mamirc.common.Event;
import io.nayuki.mamirc.common.OutputWriterThread;
import io.nayuki.mamirc.common.Utils;


public final class MamircProcessor {
	
	/*---- Stub main program ----*/
	
	public static void main(String[] args) throws IOException, SQLiteException {
		// Check the argument count
		if (args.length != 2) {
			System.err.println("Usage: java io/nayuki/mamirc/processor/MamircProcessor BackendConfig.json UserConfig.json");
			System.exit(1);
		}
		
		// Set logging levels and behaviors
		{
			// Suppress all debug messages from sqlite4java module because we are not developing it
			Logger.getLogger("com.almworks.sqlite4java").setLevel(Level.OFF);
			
			// Set the default logging level for this MamIRC Processor application (can be changed)
			Utils.logger.setLevel(Level.FINE);
			
			// Create new console output handler to overcome the default console handler only showing messages
			// down to Level.INFO (but not Level.FINE), and to define a more readable custom log line format
			Handler ch = new ConsoleHandler();
			ch.setLevel(Level.ALL);
			ch.setFormatter(new Formatter() {
				public String format(LogRecord rec) {
					String s = String.format("%tY-%<tm-%<td-%<ta %<tH:%<tM:%<tS %<tZ / %s / %s.%s() / %s%n",
							rec.getMillis(), rec.getLevel(), rec.getSourceClassName(), rec.getSourceMethodName(), rec.getMessage());
					if (rec.getThrown() != null) {
						StringWriter sw = new StringWriter();
						rec.getThrown().printStackTrace(new PrintWriter(sw));
						s += sw.toString();
					}
					return s;
				}
			});
			Utils.logger.setUseParentHandlers(false);
			Utils.logger.addHandler(ch);
			
			// Start a worker thread to read stdin, which lets the user change
			// the logging display level while the application is running
			Utils.startConsoleLogLevelChanger();
		}
		
		// Load configs and start Processor
		Utils.logger.info("MamIRC Processor application starting");
		
		File backendConfigFile = new File(args[0]);
		BackendConfiguration backendConfig = new BackendConfiguration(backendConfigFile);
		Utils.logger.info("Backend configuration file parsed: " + backendConfigFile.getCanonicalPath());
		
		File userConfigFile = new File(args[1]);
		UserConfiguration userConfig = new UserConfiguration(userConfigFile);
		Utils.logger.info("User configuration file parsed: " + userConfigFile.getCanonicalPath());
		
		new MamircProcessor(backendConfig, userConfig);
	}
	
	
	
	/*---- Fields (global state) ----*/
	
	// Essentially all operations need to be performed with this master lock held.
	// This ensures that at most one thread is reading or writing any part of
	// the entire MamIRC Processor application's state at any given moment.
	ReentrantLock lock;
	
	private final UserConfiguration userConfig;
	
	private ConnectorReaderThread reader;
	private OutputWriterThread writer;
	
	private StateUpdateHistory updateManager;
	private MessageManager messageManager;
	private LiveEventProcessor eventProcessor;
	
	
	
	/*---- Constructor ----*/
	
	public MamircProcessor(BackendConfiguration backendConfig, UserConfiguration userConfig)
			throws IOException, SQLiteException {
		
		// Handle arguments
		if (backendConfig == null || userConfig == null)
			throw new NullPointerException();
		this.userConfig = userConfig;
		
		lock = new ReentrantLock();
		
		// Connect to Connector and get current connections
		reader = new ConnectorReaderThread(this, backendConfig);
		Map<Integer,Integer> connectionSequences = new HashMap<>();
		writer = reader.readInitialDataAndGetWriter(connectionSequences);
		
		// Create part of event-processing stream and handle previous events in live connections
		messageManager = new MessageManager(userConfig.windowMessagesDatabaseFile);
		messageManager.beginTransaction();
		eventProcessor = new LiveEventProcessor(messageManager, this);
		lock.lock();
		processExistingConnections(backendConfig.connectorDatabaseFile, connectionSequences);
		
		// Perform more processing to prepare to transition to live event processing
		eventProcessor.finishCatchup(userConfig.profiles);
		lock.unlock();
		messageManager.commitTransaction();
		updateManager = new LiveStateUpdateHistory(30000);
		messageManager.updateMgr = updateManager;
		eventProcessor.updateMgr = updateManager;
		
		// Start live event processing, and start up the web server
		reader.start();
		new WebServer(backendConfig.webServerPort, this, messageManager);
	}
	
	
	
	/*---- Methods ----*/
	
	// Only called once by the constructor.
	private void processExistingConnections(File dbFile, Map<Integer,Integer> connectionSequences) throws SQLiteException {
		// Read archived events from database and process them
		SQLiteConnection database = new SQLiteConnection(dbFile);
		try {
			database.open(false);
			SQLiteStatement query = database.prepare(
				"SELECT sequence, timestamp, type, data FROM events " +
				"WHERE connectionId=? AND sequence<? ORDER BY sequence ASC");
			for (Map.Entry<Integer,Integer> entry : connectionSequences.entrySet()) {
				int conId = entry.getKey();
				int nextSeq = entry.getValue();
				query.bind(1, conId);
				query.bind(2, nextSeq);
				while (query.step()) {
					Event ev = new Event(
						conId,
						query.columnInt(0),
						query.columnLong(1),
						Event.Type.fromOrdinal(query.columnInt(2)),
						new CleanLine(query.columnBlob(3), false));
					eventProcessor.processEvent(ev);  // Non-real-time
				}
				query.reset();
			}
		} finally {
			database.dispose();  // Automatically disposes its associated statements
		}
	}
	
	
	// Only called by ConnectorReaderThread.run().
	void processEvent(Event ev) {
		lock.lock();
		try {
			eventProcessor.processEvent(ev);  // Real-time
		} finally {
			lock.unlock();
		}
	}
	
	
	// Only called by EventProcessor.
	void sendCommand(String line) {
		lock.lock();
		try {
			writer.postWrite(line);
		} finally {
			lock.unlock();
		}
	}
	
	
	void terminateProcessor(String reason) {
		lock.lock();
		try {
			Utils.logger.info("MamIRC Processor application terminating");
			System.exit(1);
			throw new AssertionError("Unreachable");
		} finally {
			lock.unlock();
		}
	}
	
	
	// Only called by WebServer.
	Object getNetworkProfilesAsJson() {
		lock.lock();
		try {
			Map<String,Object> result = new HashMap<>();
			for (NetworkProfile profile : userConfig.profiles.values())
				result.put(profile.name, profile.toJson());
			return result;
		} finally {
			lock.unlock();
		}
	}
	
	
	// Can be called from any thread, whether it holds the lock or not (obviously).
	void checkLock() {
		if (!lock.isHeldByCurrentThread())
			throw new AssertionError("Master lock is not held");
	}
	
}
