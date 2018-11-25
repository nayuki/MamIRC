/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.connector;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;
import io.nayuki.mamirc.common.Event;
import io.nayuki.mamirc.common.OutputWriteWorker;


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
	
	
	
	/*---- Instance fields ----*/
	
	private final ProcessorListenWorker processorListener;
	private ProcessorReadWorker processorReader;
	private OutputWriteWorker processorWriter;
	
	final ScheduledExecutorService scheduler;  // Shared service usable by any MamircConnector component
	private final DatabaseWriteWorker databaseWriter;
	
	
	
	/*---- Constructor and its helpers ----*/
	
	private MamircConnector(File config) throws SQLiteException, IOException {
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
		
		scheduler = Executors.newSingleThreadScheduledExecutor();
		try {
			
			databaseWriter = new DatabaseWriteWorker(archiveDb);
			processorListener = new ProcessorListenWorker(this, serverPort, password);
			
		} catch (IOException e) {
			scheduler.shutdown();
			throw e;
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
	
	
	
	/*---- Callback methods from ProcessorReadWorker ----*/
	
	synchronized void attachProcessor(ProcessorReadWorker reader, OutputWriteWorker writer) {
		Objects.requireNonNull(reader);
		Objects.requireNonNull(writer);
		if (reader != processorReader)
			return;
		
		if (processorReader != null)
			processorReader.terminate();
		processorReader = reader;
		processorWriter = writer;
	}
	
	
	synchronized void detachProcessor(ProcessorReadWorker reader) {
		Objects.requireNonNull(reader);
		if (reader != processorReader)
			return;
		
		processorReader = null;
		processorWriter = null;
	}
	
	
	synchronized void connectServer(ProcessorReadWorker reader, String hostname, int port, boolean useSsl, String metadata) {
		Objects.requireNonNull(reader);
		Objects.requireNonNull(hostname);
		Objects.requireNonNull(metadata);
		if (reader != processorReader)
			return;
	}
	
	
	synchronized void disconnectServer(ProcessorReadWorker reader, int conId) {
		Objects.requireNonNull(reader);
		if (reader != processorReader)
			return;
	}
	
	
	synchronized void sendMessage(ProcessorReadWorker reader, int conId, byte[] line) {
		Objects.requireNonNull(reader);
		if (reader != processorReader)
			return;
	}
	
	
	synchronized void terminateConnector(ProcessorReadWorker reader, String reason) throws InterruptedException {
		Objects.requireNonNull(reader);
		if (reader != processorReader)
			return;
		
		reader.terminate();
		databaseWriter.terminate();
	}
	
	
	
	/*---- Callback methods from IrcServerReadWorker ----*/
	
	synchronized void connectionOpened(int conId, InetAddress addr, IrcServerReadWorker reader, OutputWriteWorker writer) {
		Objects.requireNonNull(addr);
		Objects.requireNonNull(reader);
		Objects.requireNonNull(writer);
	}
	
	
	synchronized void connectionClosed(int conId) {
	}
	
	
	synchronized void messageReceived(int conId, byte[] line) {
		Objects.requireNonNull(line);
	}
	
	
	private void handleEvent(Event ev) throws InterruptedException {
		Objects.requireNonNull(ev);
		databaseWriter.writeEvent(ev);
	}
	
}
