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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.almworks.sqlite4java.SQLiteException;
import io.nayuki.mamirc.common.BackendConfiguration;
import io.nayuki.mamirc.common.CleanLine;
import io.nayuki.mamirc.common.Event;
import io.nayuki.mamirc.common.OutputWriterThread;
import io.nayuki.mamirc.common.Utils;


/* 
 * The MamIRC Connector main program class. It launches a bunch of worker threads,
 * and the one MamircConnector object holds the application state in its fields.
 */
public final class MamircConnector {
	
	/*---- Stub main program ----*/
	
	// Performs some configuration, creates the MamircConnector object
	// which launches worker threads, and then the main thread returns.
	public static void main(String[] args) throws IOException, SQLiteException {
		if (args.length != 1) {
			System.err.println("Usage: java io/nayuki/mamirc/connector/MamircConnector BackendConfig.json");
			System.exit(1);
		}
		
		// Set logging levels
		Logger.getLogger("com.almworks.sqlite4java").setLevel(Level.OFF);
		Utils.logger.setLevel(Level.INFO);
		Handler ch = new ConsoleHandler();
		ch.setLevel(Level.ALL);
		Utils.logger.setUseParentHandlers(false);
		Utils.logger.addHandler(ch);
		
		// Load config and start connector
		Utils.logger.info("MamIRC Connector starting");
		File configFile = new File(args[0]);
		BackendConfiguration config = new BackendConfiguration(configFile);
		Utils.logger.info("Configuration file parsed");
		new MamircConnector(config);
	}
	
	
	
	/*---- Fields ----*/
	
	// Connections to remote IRC servers, which need the mutex when accessed
	private final Map<Integer,ConnectionInfo> serverConnections;  // Contents are mutable
	private int nextConnectionId;
	
	// Ephemeral threads, which need the mutex when accessed
	private ProcessorReaderThread processorReader;
	private OutputWriterThread processorWriter;
	
	// Singleton threads, which are always safe to access without synchronization
	private final DatabaseLoggerThread databaseLogger;
	private final ProcessorListenerThread processorListener;
	final Timer timer;  // Shared timer usable by any MamircConnector component
	
	
	
	/*---- Constructor ----*/
	
	// This constructor performs as much work as possible on the
	// caller's thread. Then it launches a bunch of worker threads.
	public MamircConnector(BackendConfiguration config) throws IOException, SQLiteException {
		// Initialize database writer and get next connection ID
		databaseLogger = new DatabaseLoggerThread(config.connectorDatabaseFile);
		nextConnectionId = databaseLogger.initAndGetNextConnectionId();
		Utils.logger.info("Database file opened");
		
		// Create socket to listen for an incoming processor
		processorListener = new ProcessorListenerThread(this, config.connectorServerPort, config.getConnectorPassword());
		Utils.logger.info("Listening on port " + config.connectorServerPort);
		
		// Initialize other mutable fields, if no fatal exceptions were thrown above
		serverConnections = new HashMap<>();
		processorReader = null;
		processorWriter = null;
		
		// Launch the worker threads
		databaseLogger.start();
		processorListener.start();
		timer = new Timer();
		timer.schedule(new TimerTask() {
			public void run() {
				pingConnections();
			}
		}, PING_INTERVAL, PING_INTERVAL);
		Utils.logger.info("Connector ready");
	}
	
	
	
	/*---- Methods for accessing/updating global state ----*/
	
	// Should only be called from ProcessorReaderThread or MamircConnector.attachProcessor().
	synchronized void listConnectionsToProcessor(OutputWriterThread writer) {
		if (writer == null)
			throw new NullPointerException();
		// Dump current connection IDs and sequences to the Processor
		databaseLogger.flushQueue();
		writer.postWrite("active-connections");
		for (Map.Entry<Integer,ConnectionInfo> entry : serverConnections.entrySet())
			writer.postWrite(entry.getKey() + " " + entry.getValue().nextSequence);
		writer.postWrite("end-list");
	}
	
	
	// Should only be called from ProcessorReaderThread.
	synchronized void attachProcessor(ProcessorReaderThread reader, OutputWriterThread writer) {
		if (reader == null || writer == null)
			throw new NullPointerException();
		// Kick out existing processor, and set fields
		if (processorReader != null)
			processorReader.terminate();  // Asynchronous termination
		processorReader = reader;
		processorWriter = writer;
		Utils.logger.info("Processor attached");
		listConnectionsToProcessor(writer);
		processorWriter.postWrite("live-events");
	}
	
	
	// Should only be called from ProcessorReaderThread. Caller is responsible for its own termination.
	synchronized void detachProcessor(ProcessorReaderThread reader) {
		if (reader == processorReader) {
			processorReader = null;
			processorWriter = null;
		}
		// Else ignore
	}
	
	
	// Should only be called from ProcessorReaderThread. Hostname and metadata must not contain '\0', '\r', or '\n'.
	synchronized void connectServer(String hostname, int port, boolean useSsl, String metadata, ProcessorReaderThread reader) {
		if (reader != processorReader)
			return;
		ConnectionInfo info = new ConnectionInfo(nextConnectionId);
		nextConnectionId++;
		String str = "connect " + hostname + " " + port + " " + (useSsl ? "ssl" : "nossl") + " " + metadata;
		postEvent(info, Event.Type.CONNECTION, new CleanLine(str));
		serverConnections.put(info.connectionId, info);
		new ServerReaderThread(this, info.connectionId, hostname, port, useSsl).start();
	}
	
	
	// Should only be called from ProcessorReaderThread.
	synchronized void disconnectServer(int conId, ProcessorReaderThread reader) {
		if (reader != processorReader)
			return;
		ConnectionInfo info = serverConnections.get(conId);
		if (info == null)
			Utils.logger.info("Warning: Connection " + conId + " does not exist");
		else {
			postEvent(info, Event.Type.CONNECTION, new CleanLine("disconnect"));
			info.reader.terminate();
		}
	}
	
	
	// Should only be called from ServerReaderThread.
	synchronized void connectionOpened(int conId, InetAddress addr, ServerReaderThread reader, OutputWriterThread writer) {
		if (addr == null || reader == null || writer == null)
			throw new NullPointerException();
		if (conId < 0 || conId >= nextConnectionId)
			throw new AssertionError();
		if (!serverConnections.containsKey(conId))
			throw new IllegalStateException("Connection ID does not exist: " + conId);
		ConnectionInfo info = serverConnections.get(conId);
		postEvent(info, Event.Type.CONNECTION, new CleanLine("opened " + addr.getHostAddress()));
		info.reader = reader;
		info.writer = writer;
	}
	
	
	// Should only be called from ServerReaderThread.
	synchronized void connectionClosed(int conId) {
		ConnectionInfo info = serverConnections.remove(conId);
		if (info == null)
			throw new IllegalStateException("Connection ID does not exist: " + conId);
		postEvent(info, Event.Type.CONNECTION, new CleanLine("closed"));
	}
	
	
	// Should only be called from ServerReaderThread.
	synchronized void receiveMessage(int conId, CleanLine line) {
		if (line == null)
			throw new NullPointerException();
		ConnectionInfo info = serverConnections.get(conId);
		if (info == null)
			throw new IllegalStateException("Connection ID does not exist: " + conId);
		postEvent(info, Event.Type.RECEIVE, line);
		Utils.logger.finest("Receive line from IRC server");
		byte[] pong = makePongIfPing(line.getDataNoCopy());
		if (pong != null)
			sendMessage(conId, new CleanLine(pong, false), processorReader);
	}
	
	
	// Should only be called from ProcessorReaderThread or receiveMessage().
	synchronized void sendMessage(int conId, CleanLine line, ProcessorReaderThread reader) {
		if (line == null || reader == null)
			throw new NullPointerException();
		if (reader != processorReader)
			return;
		ConnectionInfo info = serverConnections.get(conId);
		if (info != null && info.writer != null) {
			postEvent(info, Event.Type.SEND, line);
			Utils.logger.finest("Send line to IRC server");
			info.writer.postWrite(line);
		} else
			Utils.logger.info("Warning: Connection " + conId + " does not exist");
	}
	
	
	// Should only be called from ProcessorReaderThread or ProcessorListenerThread.
	synchronized void terminateConnector(String reason) {
		Utils.logger.info("Application termination requested: " + reason);
		// The DatabaseLoggerThread is solely responsible for terminating the entire application
		databaseLogger.terminate();
		throw new AssertionError("Unreachable");
	}
	
	
	// Logs the event to the database, and relays another copy to the currently attached processor.
	// Must only be called from one of the synchronized methods above.
	private void postEvent(ConnectionInfo info, Event.Type type, CleanLine line) {
		Event ev = new Event(info.connectionId, info.nextSequence++, type, line);
		if (processorWriter != null) {
			try {
				ByteArrayOutputStream bout = new ByteArrayOutputStream(line.getDataNoCopy().length + 40);
				bout.write(Utils.toUtf8(String.format("%d %d %d %d ", ev.connectionId, ev.sequence, ev.timestamp, ev.type.ordinal())));
				bout.write(line.getDataNoCopy());
				processorWriter.postWrite(new CleanLine(bout.toByteArray(), false));
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		}
		databaseLogger.postEvent(ev);
	}
	
	
	// Scans all currently active connections and sends a ping to each one. If a connection is bad, this write
	// soon causes the socket read() to throw an IOException, due to a reset packet or lack of acknowledgement.
	// This write is necessary because without it, the read() might keep silently blocking for minutes or hours
	// on a bad connection, depending on how the underlying platform handles socket keepalives.
	// Note that these pings are not logged to the database or relayed to the processor.
	// This method should only be called from the timer thread.
	private synchronized void pingConnections() {
		// From surveying ~5 different IRC servers, it appears that sending a blank line is always safely ignored.
		// (However, some servers give an error response to a whitespace-only line consisting of one or more spaces.)
		// This pseudo-ping is more lightweight than sending a real IRC PING command, and justifies the lack of logging.
		if (Utils.logger.isLoggable(Level.FINEST))
			Utils.logger.finest("Sending blank line to " + serverConnections.size() + " IRC server connections");
		for (ConnectionInfo info : serverConnections.values()) {
			if (info.writer != null)
				info.writer.postWrite(BLANK_LINE);
		}
	}
	
	private static final CleanLine BLANK_LINE = new CleanLine("");
	
	private static final int PING_INTERVAL = 20000;  // In milliseconds
	
	
	// If the given line is an IRC PING command, then this returns a new byte array containing an appropriate PONG response.
	// Otherwise this function returns null. This handles all inputs correctly, and safely ignores lines with illegal IRC syntax.
	static byte[] makePongIfPing(byte[] line) {
		// Skip prefix, if any
		int i = 0;
		if (line.length >= 1 && line[i] == ':') {
			i++;
			while (i < line.length && line[i] != ' ')
				i++;
			while (i < line.length && line[i] == ' ')
				i++;
		}
		
		// Check that next 4 characters are "PING" case-insensitively, followed by space or end of string
		byte[] reply = null;
		if (line.length - i >= 4 && (line[i + 0] & 0xDF) == 'P' && (line[i + 1] & 0xDF) == 'I' && (line[i + 2] & 0xDF) == 'N' && (line[i + 3] & 0xDF) == 'G'
				&& (line.length - i == 4 || line[i + 4] == ' ')) {
			// Create reply by dropping prefix, changing PING to PONG, and copying all parameters
			reply = Arrays.copyOfRange(line, i, line.length);
			reply[1] += 'O' - 'I';
		}
		return reply;
	}
	
	
	
	/*---- Helper structure ----*/
	
	private static final class ConnectionInfo {
		
		public final int connectionId;     // Non-negative
		public int nextSequence;           // Non-negative
		public ServerReaderThread reader;  // Initially null, but non-null after connectionOpened() is called
		public OutputWriterThread writer;  // Initially null, but non-null after connectionOpened() is called
		
		
		public ConnectionInfo(int conId) {
			if (conId < 0)
				throw new IllegalArgumentException("Connection ID must be positive");
			connectionId = conId;
			nextSequence = 0;
			reader = null;
			writer = null;
		}
		
	}
	
}
