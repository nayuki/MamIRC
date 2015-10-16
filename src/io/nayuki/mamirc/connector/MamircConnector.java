package io.nayuki.mamirc.connector;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.almworks.sqlite4java.SQLiteException;
import io.nayuki.mamirc.common.CleanLine;
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
	
	
	
	/*---- Fields ----*/
	
	// All of these fields are global state. Any read/write access
	// must be done while synchronized on this MamircConnector object!
	
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
		
	// Should only be called from ProcessorReaderThread.
	public synchronized void attachProcessor(ProcessorReaderThread reader, OutputWriterThread writer) {
		// Kick out existing processor, and set fields
		if (processorReader != null)
			processorReader.terminate();  // Asynchronous termination
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
	
	
	// Should only be called from ProcessorReaderThread. Metadata must not contain '\0', '\r', or '\n'.
	public synchronized void connectServer(String hostname, int port, boolean useSsl, String metadata, ProcessorReaderThread reader) {
		if (reader != processorReader)
			return;
		int conId = nextConnectionId;
		ConnectionInfo info = new ConnectionInfo();
		String str = "connect " + hostname + " " + port + " " + (useSsl ? "ssl" : "nossl") + " " + metadata;
		postEvent(conId, info, Event.Type.CONNECTION, new CleanLine(str));
		serverConnections.put(conId, info);
		new ServerReaderThread(this, conId, hostname, port, useSsl).start();
		nextConnectionId++;
	}
	
	
	// Should only be called from ProcessorReaderThread or terminateConnector().
	public synchronized void disconnectServer(int conId, ProcessorReaderThread reader) {
		if (reader != processorReader)
			return;
		ConnectionInfo info = serverConnections.get(conId);
		if (info == null)
			System.err.println("Warning: Connection " + conId + " does not exist");
		else {
			postEvent(conId, info, Event.Type.CONNECTION, new CleanLine("disconnect"));
			info.reader.terminate();
		}
	}
	
	
	// Should only be called from ServerReaderThread.
	public synchronized void connectionOpened(int conId, InetAddress addr, ServerReaderThread reader, OutputWriterThread writer) {
		if (!serverConnections.containsKey(conId))
			throw new IllegalArgumentException("Connection ID does not exist: " + conId);
		ConnectionInfo info = serverConnections.get(conId);
		postEvent(conId, info, Event.Type.CONNECTION, new CleanLine("opened " + addr.getHostAddress()));
		info.reader = reader;
		info.writer = writer;
	}
	
	
	// Should only be called from ServerReaderThread.
	public synchronized void connectionClosed(int conId) {
		ConnectionInfo info = serverConnections.remove(conId);
		if (info == null)
			throw new IllegalArgumentException("Connection ID does not exist: " + conId);
		postEvent(conId, info, Event.Type.CONNECTION, new CleanLine("closed"));
	}
	
	
	// Should only be called from ServerReaderThread.
	public synchronized void receiveMessage(int conId, CleanLine line) {
		postEvent(conId, serverConnections.get(conId), Event.Type.RECEIVE, line);
		handlePotentialPing(conId, line);
	}
	
	
	// Should only be called from ProcessorReaderThread and handlePotentialPing().
	public synchronized void sendMessage(int conId, CleanLine line, ProcessorReaderThread reader) {
		if (reader != processorReader)
			return;
		ConnectionInfo info = serverConnections.get(conId);
		if (info != null && info.writer != null) {
			postEvent(conId, info, Event.Type.SEND, line);
			info.writer.postWrite(line);
		} else
			System.err.println("Warning: Connection " + conId + " does not exist");
	}
	
	
	// Should only be called from ProcessorReaderThread.
	public void terminateConnector(ProcessorReaderThread reader) {
		if (reader != processorReader)
			return;
		
		Thread[] toWait;
		synchronized(this) {
			System.err.println("Connector terminating");
			
			toWait = new ServerReaderThread[serverConnections.size()];
			int i = 0;
			for (int conId : serverConnections.keySet()) {
				toWait[i] = serverConnections.get(conId).reader;
				disconnectServer(conId, processorReader);
				i++;
			}
			
			if (processorReader != null) {
				processorReader.terminate();
				processorReader = null;
				processorWriter = null;
			}
			
			try {
				processorListener.serverSocket.close();
			} catch (IOException e) {}
		}
		
		try {
			for (Thread th : toWait)
				th.join();
		} catch (InterruptedException e) {}
		databaseLogger.terminate();
	}
	
	
	// Must only be called from one of the synchronized methods above.
	private void postEvent(int conId, ConnectionInfo info, Event.Type type, CleanLine line) {
		Event ev = new Event(conId, info.nextSequence(), type, line);
		if (processorWriter != null) {
			try {
				ByteArrayOutputStream bout = new ByteArrayOutputStream();
				bout.write(Utils.toUtf8(String.format("%d %d %d %d ", ev.connectionId, ev.sequence, ev.timestamp, ev.type.ordinal())));
				bout.write(line.getData());
				processorWriter.postWrite(new CleanLine(bout.toByteArray()));
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		}
		databaseLogger.postEvent(ev);
	}
	
	
	// Must only be called from receiveMessage(). Safely ignores illegal syntax lines instead of throwing an exception.
	private void handlePotentialPing(int conId, CleanLine line) {
		// Skip prefix, if any
		byte[] b = line.getData();
		int i = 0;
		if (b.length >= 1 && b[i] == ':') {
			i++;
			while (i < b.length && b[i] != ' ')
				i++;
			while (i < b.length && b[i] == ' ')
				i++;
		}
		
		// Check that next 4 characters are "PING" case-insensitively, followed by space or end of string
		if (b.length - i >= 4 && (b[i + 0] & 0xDF) == 'P' && (b[i + 1] & 0xDF) == 'I' && (b[i + 2] & 0xDF) == 'N' && (b[i + 3] & 0xDF) == 'G'
				&& (b.length - i == 4 || b[i + 4] == ' ')) {
			// Create reply by dropping prefix, changing PING to PONG, and copying parameters
			byte[] reply = Arrays.copyOfRange(b, i, b.length);
			reply[1] += 6;
			sendMessage(conId, new CleanLine(reply), processorReader);
		}
	}
	
	
	
	/*---- Nested classes ----*/
	
	private static final class ConnectionInfo {
		
		public int nextSequence;
		public ServerReaderThread reader;
		public OutputWriterThread writer;
		
		
		public ConnectionInfo() {
			nextSequence = 0;
			reader = null;
			writer = null;
		}
		
		
		public int nextSequence() {
			int result = nextSequence;
			nextSequence++;
			return result;
		}
		
	}
	
}
