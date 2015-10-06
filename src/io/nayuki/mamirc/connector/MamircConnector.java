package io.nayuki.mamirc.connector;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.almworks.sqlite4java.SQLiteException;
import io.nayuki.mamirc.common.ConnectorConfiguration;
import io.nayuki.mamirc.common.Event;
import io.nayuki.mamirc.common.OutputWriterThread;
import io.nayuki.mamirc.common.Utils;


public final class MamircConnector {
	
	/*---- Stub main program ----*/
	
	public static void main(String[] args) throws IOException, SQLiteException {
		if (args.length != 1) {
			System.err.println("Usage: java io/nayuki/mamirc/connector/MamircConnector connector.ini");
			System.exit(1);
		}
		
		// Prevent sqlite4java module from polluting stderr with debug messages
		Logger.getLogger("com.almworks.sqlite4java").setLevel(Level.OFF);
		
		// Load config and start connector
		File configFile = new File(args[0]);
		ConnectorConfiguration config = new ConnectorConfiguration(configFile);
		new MamircConnector(config);
		// The main thread returns, while other threads live on
	}
	
	
	
	/*---- Fields (global state) ----*/
	
	// Connections to remote IRC servers
	private int nextConnectionId;
	private final Map<Integer,ConnectionInfo> serverConnections;
	
	// Ephemeral threads
	private ProcessorReaderThread processorReader;
	private OutputWriterThread processorWriter;
	
	// Singleton threads
	private final DatabaseLoggerThread databaseLogger;
	private final ProcessorListenerThread processorListener;
	
	
	
	/*---- Constructor ----*/
	
	// This launches a bunch of threads and returns immediately.
	public MamircConnector(ConnectorConfiguration config) throws IOException, SQLiteException {
		// Initialize some fields
		serverConnections = new HashMap<>();
		
		// Initialize database logger and get next connection ID
		databaseLogger = new DatabaseLoggerThread(config.databaseFile);
		nextConnectionId = databaseLogger.initAndGetNextConnectionId();
		databaseLogger.start();
		System.err.println("Database opened");
		
		// Listen for an incoming processor
		processorReader = null;
		processorWriter = null;
		try {
			processorListener = new ProcessorListenerThread(this, config.serverPort, config.getConnectorPassword());
			System.err.println("Listening on port " + config.serverPort);
		} catch (IOException e) {
			databaseLogger.terminate();
			throw e;
		}
		processorListener.start();
		System.err.println("Connector ready");
	}
	
	
	
	/*---- Methods for accessing/updating global state ----*/
	
	// These synchronized methods can be called safely from any thread.
	
	// Should only be called from ProcessorReaderThread.
	public synchronized void attachProcessor(ProcessorReaderThread reader, OutputWriterThread writer) {
		// Kick out existing processor, and set fields
		if (processorReader != null) {
			try {
				processorReader.socket.close();  // Asynchronous termination
			} catch (IOException e) {}
		}
		processorReader = reader;
		processorWriter = writer;
		
		// Dump current connection info to processor
		databaseLogger.flushQueue();
		processorWriter.postWrite("active-connections");
		for (int conId : serverConnections.keySet())
			processorWriter.postWrite(conId + " " + serverConnections.get(conId).nextSequence);
		processorWriter.postWrite("live-events");
	}
	
	
	// Should only be called from ProcessorReaderThread. Caller is responsible for closing its socket.
	public synchronized void detachProcessor(ProcessorReaderThread reader) {
		if (reader == processorReader) {
			processorReader = null;
			processorWriter = null;
		}
	}
	
	
	// Should only be called from ProcessorReaderThread.
	public synchronized void connectServer(String hostname, int port, boolean useSsl, String metadata, ProcessorReaderThread reader) {
		if (reader != processorReader)
			return;
		int conId = nextConnectionId;
		ConnectionInfo info = new ConnectionInfo();
		postEvent(conId, info.nextSequence(), Event.Type.CONNECTION,
				Utils.toUtf8("connect " + hostname + " " + port + " " + (useSsl ? "ssl" : "nossl") + " " + metadata));
		serverConnections.put(conId, info);
		new ServerReaderThread(this, conId, hostname, port, useSsl).start();
		nextConnectionId++;
	}
	
	
	// Should only be called from ProcessorReaderThread and terminateConnector().
	public synchronized void disconnectServer(int conId, ProcessorReaderThread reader) {
		if (reader != processorReader)
			return;
		ConnectionInfo info = serverConnections.get(conId);
		if (info == null)
			System.err.println("Warning: Connection " + conId + " does not exist");
		else {
			postEvent(conId, info.nextSequence(), Event.Type.CONNECTION, Utils.toUtf8("disconnect"));
			try {
				info.socket.close();  // Asynchronous termination
			} catch (IOException e) {}
		}
	}
	
	
	// Should only be called from ServerReaderThread.
	public synchronized void connectionOpened(int conId, Socket sock, OutputWriterThread writer) {
		if (!serverConnections.containsKey(conId))
			throw new IllegalArgumentException("Connection ID does not exist: " + conId);
		ConnectionInfo info = serverConnections.get(conId);
		postEvent(conId, info.nextSequence(), Event.Type.CONNECTION, Utils.toUtf8("opened " + sock.getInetAddress().getHostAddress()));
		info.socket = sock;
		info.writer = writer;
	}
	
	
	// Should only be called from ServerReaderThread.
	public synchronized void connectionClosed(int conId) {
		ConnectionInfo info = serverConnections.remove(conId);
		if (info == null)
			throw new IllegalArgumentException("Connection ID does not exist: " + conId);
		postEvent(conId, info.nextSequence(), Event.Type.CONNECTION, Utils.toUtf8("closed"));
	}
	
	
	// Should only be called from ServerReaderThread.
	public synchronized void receiveMessage(int conId, byte[] line) {
		postEvent(conId, serverConnections.get(conId).nextSequence(), Event.Type.RECEIVE, line);
		handlePotentialPing(conId, line);
	}
	
	
	// Should only be called from ProcessorReaderThread and handlePotentialPing().
	public synchronized void sendMessage(int conId, byte[] line, ProcessorReaderThread reader) {
		if (reader != processorReader)
			return;
		if (serverConnections.containsKey(conId)) {
			postEvent(conId, serverConnections.get(conId).nextSequence(), Event.Type.SEND, line);
			serverConnections.get(conId).write(line);
		} else
			System.err.println("Warning: Connection " + conId + " does not exist");
	}
	
	
	// Should only be called from ProcessorReaderThread.
	public synchronized void terminateConnector(ProcessorReaderThread reader) {
		if (reader != processorReader)
			return;
		System.err.println("Connector terminating");
		
		for (int conId : serverConnections.keySet())
			disconnectServer(conId, processorReader);
		
		if (processorReader != null) {
			try {
				processorReader.socket.close();
			} catch (IOException e) {}
		}
		
		try {
			processorListener.serverSocket.close();
		} catch (IOException e) {}
		databaseLogger.terminate();
	}
	
	
	// Must only be called from one of the synchronized methods above.
	private void postEvent(int conId, int seq, Event.Type type, byte[] line) {
		Event ev = new Event(conId, seq, type, line);
		if (processorWriter != null) {
			try {
				ByteArrayOutputStream bout = new ByteArrayOutputStream();
				bout.write(Utils.toUtf8(String.format("%d %d %d %d ", ev.connectionId, ev.sequence, ev.timestamp, ev.type.ordinal())));
				bout.write(ev.getLine());
				processorWriter.postWrite(bout.toByteArray());
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		}
		databaseLogger.postEvent(ev);
	}
	
	
	// Must only be called from receiveMessage(). Safely ignores illegal syntax lines instead of throwing an exception.
	private void handlePotentialPing(int conId, byte[] line) {
		// Skip prefix, if any
		int i = 0;
		if (line.length >= 1 && line[i] == ':') {
			i++;
			while (i < line.length && line[i] != ' ')
				i++;
			if (i < line.length && line[i] == ' ')
				i++;
		}
		
		// Check that next 4 characters are "PING" case-insensitively, followed by space or end of string
		if (line.length - i >= 4 && (line[i + 0] & 0xDF) == 'P' && (line[i + 1] & 0xDF) == 'I' && (line[i + 2] & 0xDF) == 'N' && (line[i + 3] & 0xDF) == 'G'
				&& (line.length - i == 4 || line[i + 4] == ' ')) {
			// Create reply by dropping prefix, changing PING to PONG, and copying parameters
			byte[] reply = Arrays.copyOfRange(line, i, line.length);
			reply[1] += 6;
			sendMessage(conId, reply, processorReader);
		}
	}
	
	
	
	/*---- Nested classes ----*/
	
	private static final class ConnectionInfo {
		
		public int nextSequence;
		public Socket socket;
		public OutputWriterThread writer;
		
		
		public ConnectionInfo() {
			nextSequence = 0;
			socket = null;
			writer = null;
		}
		
		
		public int nextSequence() {
			int result = nextSequence;
			nextSequence++;
			return result;
		}
		
		
		public void write(byte[] line) {
			if (writer == null)
				throw new IllegalStateException("Not connected");
			writer.postWrite(line);
		}
		
	}
	
}
