/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.connector;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.almworks.sqlite4java.SQLiteException;
import io.nayuki.mamirc.common.BasicConfigurationDatabase;
import io.nayuki.mamirc.common.Event;
import io.nayuki.mamirc.common.OutputWriteWorker;


public final class MamircConnector {
	
	/*---- Stub main program ----*/
	
	public static void main(String[] args) throws SQLiteException {
		if (args.length != 1)
			errorExit("Usage: java io/nayuki/mamirc/connector/MamircConnector Configuration.sqlite");
		
		Logger.getLogger("com.almworks.sqlite4java").setLevel(Level.OFF);
		File config = new File(args[0]);
		if (!config.isFile())
			errorExit("Non-existent configuration file: " + args[0]);
		
		try {
			new MamircConnector(config);
		} catch (Throwable e) {
			errorExit(e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}
	
	
	// Only called by main().
	private static void errorExit(String msg) {
		System.err.println(msg);
		System.exit(1);
	}
	
	
	
	/*---- Instance fields ----*/
	
	ScheduledExecutorService scheduler = null;  // Shared service usable by any MamircConnector component
	private DatabaseWriteWorker databaseWriter = null;
	private ProcessorListenWorker processorListener = null;
	
	private ProcessorReadWorker processorReader = null;
	private OutputWriteWorker processorWriter = null;
	
	private final Map<Integer,ConnectionInfo> serverConnections = new HashMap<>();
	private boolean isShuttingDown = false;
	
	
	
	/*---- Constructor and its helpers ----*/
	
	private MamircConnector(File config) throws SQLiteException, IOException {
		// Fields to read from config
		int serverPort;
		File archiveDb;
		byte[] password;
		
		// Start reading config DB file
		try (BasicConfigurationDatabase configDb = new BasicConfigurationDatabase(config)) {
			// Retrieve and parse various fields
			serverPort = Integer.parseInt(configDb.getValue("connector server port"));
			archiveDb = new File(configDb.getValue("archive database file"));
			
			String pswd = configDb.getValue("connector password");
			password = new byte[pswd.length()];
			for (int i = 0; i < pswd.length(); i++) {
				char c = pswd.charAt(i);
				if (c > 0xFF)
					throw new IllegalArgumentException("Password character outside of range [U+00, U+FF]");
				password[i] = (byte)c;  // Truncate Unicode code point into byte
			}
		}
		
		try {
			scheduler = Executors.newSingleThreadScheduledExecutor();
			databaseWriter = new DatabaseWriteWorker(this, archiveDb);
			processorListener = new ProcessorListenWorker(this, serverPort, password);
		} catch (SQLiteException|IOException e) {
			shutdown("Error in initialization");
			throw e;
		}
	}
	
	
	
	/*---- Callback methods from ProcessorReadWorker ----*/
	
	synchronized void attachProcessor(ProcessorReadWorker reader, OutputWriteWorker writer) throws InterruptedException {
		Objects.requireNonNull(reader);
		Objects.requireNonNull(writer);
		
		if (isShuttingDown) {
			reader.shutdown();
			return;
		}
		if (processorReader != null)
			processorReader.shutdown();
		processorReader = reader;
		processorWriter = writer;
		
		databaseWriter.flush();
		processorWriter.writeLine("active-connections");
		Map<Integer,ConnectionInfo> temp = new TreeMap<>(serverConnections);  // Force ascending sort
		for (Map.Entry<Integer,ConnectionInfo> entry : temp.entrySet())
			writer.writeLine(entry.getKey() + " " + entry.getValue().nextSequence);
		processorWriter.writeLine("end-list");
		processorWriter.writeLine("live-events");
	}
	
	
	synchronized void detachProcessor(ProcessorReadWorker reader) {
		Objects.requireNonNull(reader);
		if (reader != processorReader)
			return;
		
		processorReader = null;
		processorWriter = null;
	}
	
	
	synchronized void connectServer(ProcessorReadWorker reader, String hostname, int port, boolean useSsl, int timeout, String metadata) {
		Objects.requireNonNull(reader);
		Objects.requireNonNull(hostname);
		Objects.requireNonNull(metadata);
		if (reader != processorReader)
			return;
		
		int conId = databaseWriter.getNextConnectionIdAndIncrement();
		if (serverConnections.containsKey(conId))
			throw new AssertionError();
		ConnectionInfo info = new ConnectionInfo(conId,
			new IrcServerReadWorker(this, conId, hostname, port, useSsl, timeout));
		serverConnections.put(conId, info);
		String str = "connect " + hostname + " " + port + " " + (useSsl ? "ssl" : "nossl") + " " + metadata;
		distributeEvent(info, Event.Type.CONNECTION, str.getBytes(StandardCharsets.UTF_8));
	}
	
	
	synchronized void disconnectServer(ProcessorReadWorker reader, int conId) {
		Objects.requireNonNull(reader);
		if (reader != processorReader)
			return;
		
		ConnectionInfo info = serverConnections.get(conId);
		if (info != null) {
			distributeEvent(info, Event.Type.CONNECTION, "disconnect".getBytes(StandardCharsets.UTF_8));
			info.reader.shutdown();
		}
	}
	
	
	synchronized void sendMessage(ProcessorReadWorker reader, int conId, byte[] line) {
		Objects.requireNonNull(reader);
		Objects.requireNonNull(line);
		if (reader != processorReader)
			return;
		
		ConnectionInfo info = serverConnections.get(conId);
		if (info != null && info.writer != null) {
			info.writer.writeLine(line);
			distributeEvent(info, Event.Type.SEND, line);
		}
	}
	
	
	synchronized void shutdownConnector(ProcessorReadWorker reader, String reason) throws InterruptedException {
		Objects.requireNonNull(reader);
		synchronized(this) {
			if (reader != processorReader)
				return;
			isShuttingDown = true;
		}
		shutdown(reason);
	}
	
	
	
	/*---- Callback methods from IrcServerReadWorker ----*/
	
	synchronized void connectionOpened(int conId, InetAddress addr, OutputWriteWorker writer) {
		Objects.requireNonNull(addr);
		Objects.requireNonNull(writer);
		if (conId < 0)
			throw new IllegalArgumentException();
		
		if (!serverConnections.containsKey(conId))
			throw new IllegalStateException("Connection ID does not exist: " + conId);
		ConnectionInfo info = serverConnections.get(conId);
		distributeEvent(info, Event.Type.CONNECTION, ("opened " + addr.getHostAddress()).getBytes(StandardCharsets.UTF_8));
		info.writer = writer;
	}
	
	
	synchronized void connectionClosed(int conId) {
		ConnectionInfo info = serverConnections.remove(conId);
		if (info != null)
			distributeEvent(info, Event.Type.CONNECTION, "closed".getBytes(StandardCharsets.UTF_8));
	}
	
	
	synchronized void messageReceived(int conId, byte[] line) {
		Objects.requireNonNull(line);
		if (isShuttingDown)
			return;
		ConnectionInfo info = serverConnections.get(conId);
		if (info != null)
			distributeEvent(info, Event.Type.RECEIVE, line);
	}
	
	
	private void distributeEvent(ConnectionInfo info, Event.Type type, byte[] line) {
		Event ev = new Event(info.connectionId, info.nextSequence++, type, line);
		if (processorWriter != null) {
			try {
				ByteArrayOutputStream bout = new ByteArrayOutputStream(line.length + 40);
				String s = String.format("%d %d %d %d ", ev.connectionId, ev.sequence, ev.timestamp, ev.type.ordinal());
				bout.write(s.getBytes(StandardCharsets.UTF_8));
				bout.write(line);
				processorWriter.writeLine(bout.toByteArray());
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		}
		databaseWriter.writeEvent(ev);
	}
	
	
	
	/*---- Miscellaneous methods ----*/
	
	// When calling this method, the thread must not hold the lock for this object.
	void shutdown(String reason) {
		Objects.requireNonNull(reason);
		System.err.println("MamIRC Connector shutting down: " + reason);
		Collection<Thread> threads = new ArrayList<>();
		synchronized(this) {
			isShuttingDown = true;
			if (scheduler != null) {
				scheduler.shutdown();
				scheduler = null;
			}
			if (processorListener != null) {
				processorListener.shutdown();
				processorListener = null;
			}
			if (processorReader != null) {
				processorReader.shutdown();
				processorReader = null;
				processorWriter = null;
			}
			for (ConnectionInfo info : serverConnections.values()) {
				info.reader.shutdown();
				threads.add(info.reader);
			}
		}
		try {
			for (Thread th : threads)
				th.join();
		} catch (InterruptedException e) {}
		synchronized(this) {
			databaseWriter.shutdown();
			databaseWriter = null;
		}
	}
	
	
	
	/*---- Helper structure ----*/
	
	private static final class ConnectionInfo {
		
		public final int connectionId;             // Non-negative
		public int nextSequence = 0;               // Non-negative
		public final IrcServerReadWorker reader;   // Non-null
		public OutputWriteWorker writer = null;    // Non-null after connectionOpened() is called
		
		
		public ConnectionInfo(int conId, IrcServerReadWorker read) {
			if (conId < 0)
				throw new IllegalArgumentException("Connection ID must be positive");
			connectionId = conId;
			reader = Objects.requireNonNull(read);
		}
		
	}
	
}
