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
import io.nayuki.mamirc.common.ConnectorConfiguration;
import io.nayuki.mamirc.common.Event;
import io.nayuki.mamirc.common.OutputWriterThread;


public final class MamircConnector {
	
	/*---- Stub main program ----*/
	
	public static void main(String[] args) throws IOException {
		Logger.getLogger("com.almworks.sqlite4java").setLevel(Level.OFF);  // Prevent sqlite4java module from polluting stderr with debug messages
		new MamircConnector(new ConnectorConfiguration(new File(args[0])));
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
	
	public MamircConnector(ConnectorConfiguration config) throws IOException {
		// Initialize some fields
		serverConnections = new HashMap<>();
		
		// Wait for database logger to start and get next connection ID
		nextConnectionId = -1;
		databaseLogger = new DatabaseLoggerThread(this, config.databaseFile);
		databaseLogger.start();
		synchronized(this) {
			while (nextConnectionId == -1) {
				try {
					this.wait();
				} catch (InterruptedException e) {}
			}
		}
		
		// Listen for an incoming processor
		processorReader = null;
		processorWriter = null;
		try {
			processorListener = new ProcessorListenerThread(this, config.serverPort, config.getConnectorPassword());
		} catch (IOException e) {
			databaseLogger.terminate();
			throw e;
		}
		processorListener.start();
		System.out.println("Connector ready");
	}
	
	
	
	/*---- Methods for accessing/updating global state ----*/
	
	// These synchronized methods can be called safely from any thread.
	
	public synchronized void databaseReady(int nextConId) {
		if (nextConnectionId != -1)
			throw new IllegalStateException("This is only called once at initialization");
		nextConnectionId = nextConId;
		this.notify();
	}
	
	
	public synchronized void attachProcessor(ProcessorReaderThread reader, OutputWriterThread writer) {
		if (processorReader != null) {
			processorWriter.terminate();
			try {
				processorReader.socket.close();
			} catch (IOException e) {}
		}
		processorReader = reader;
		processorWriter = writer;
		
		// Dump this current state to processor
		databaseLogger.flushQueue();
		processorWriter.postWrite("active-connections");
		for (Map.Entry<Integer,ConnectionInfo> entry : serverConnections.entrySet()) {
			ConnectionInfo info = entry.getValue();
			processorWriter.postWrite(entry.getKey() + " " + info.nextSequence);
		}
		processorWriter.postWrite("live-events");
	}
	
	
	public synchronized void detachProcessor(ProcessorReaderThread reader) {
		if (processorReader == reader) {
			processorWriter.terminate();
			processorReader = null;
			processorWriter = null;
		}
	}
	
	
	public synchronized void connectServer(String hostname, int port, boolean useSsl, String metadata, ProcessorReaderThread reader) {
		if (reader != processorReader)
			return;
		int conId = nextConnectionId;
		ConnectionInfo info = new ConnectionInfo();
		postEvent(conId, info.nextSequence(), Event.Type.CONNECTION, ("connect " + metadata).getBytes(OutputWriterThread.UTF8_CHARSET));
		serverConnections.put(conId, info);
		new ServerReaderThread(this, conId, hostname, port, useSsl).start();
		nextConnectionId++;
	}
	
	
	public synchronized void disconnectServer(int conId, ProcessorReaderThread reader) {
		if (reader != processorReader)
			return;
		ConnectionInfo info = serverConnections.get(conId);
		info.writer.terminate();
		try {
			info.socket.close();
		} catch (IOException e) {}
	}
	
	
	public synchronized void connectionOpened(int conId, Socket sock, OutputWriterThread writer) {
		if (!serverConnections.containsKey(conId))
			throw new IllegalArgumentException("Connection ID does not exist: " + conId);
		ConnectionInfo info = serverConnections.get(conId);
		postEvent(conId, info.nextSequence(), Event.Type.CONNECTION, "opened".getBytes(OutputWriterThread.UTF8_CHARSET));
		info.socket = sock;
		info.writer = writer;
	}
	
	
	public synchronized void connectionClosed(int conId) {
		ConnectionInfo info = serverConnections.remove(conId);
		if (info == null)
			throw new IllegalArgumentException("Connection ID does not exist: " + conId);
		postEvent(conId, info.nextSequence(), Event.Type.CONNECTION, "closed".getBytes(OutputWriterThread.UTF8_CHARSET));
	}
	
	
	public synchronized void receiveMessage(int conId, byte[] line) {
		postEvent(conId, serverConnections.get(conId).nextSequence(), Event.Type.RECEIVE, line);
		handlePotentialPing(conId, line);
	}
	
	
	public synchronized void sendMessage(int conId, byte[] line, ProcessorReaderThread reader) {
		if (reader != processorReader)
			return;
		if (serverConnections.containsKey(conId)) {
			postEvent(conId, serverConnections.get(conId).nextSequence(), Event.Type.SEND, line);
			serverConnections.get(conId).write(line);
		} else
			System.out.println("Warning: Connection " + conId + " does not exist");
	}
	
	
	public synchronized void terminateConnector(ProcessorReaderThread reader) {
		if (reader != processorReader)
			return;
		try {
			System.out.println("Connector terminating");
			for (int conId : serverConnections.keySet()) {
				ConnectionInfo info = serverConnections.get(conId);
				info.writer.terminate();
				info.socket.close();
			}
			if (processorReader != null) {
				processorWriter.terminate();
				processorReader.socket.close();
			}
			processorListener.serverSocket.close();
			databaseLogger.terminate();
		} catch (IOException e) {}
	}
	
	
	// Must be called from one of the synchronized methods above.
	private void postEvent(int conId, int seq, Event.Type type, byte[] line) {
		Event ev = new Event(conId, seq, type, line);
		if (processorWriter != null)
			processorWriter.postWrite(serializeEventForProcessor(ev));
		databaseLogger.postEvent(ev);
	}
	
	
	// Must be called from receiveMessage(). Safely ignores illegal syntax lines instead of throwing an exception.
	private void handlePotentialPing(int conId, byte[] line) {
		// Pseudocode:
		//   if (line.contains("PING"))
		//     sendMessage("PONG" + line.parameters);
		//   /* Else do nothing */
		
		// Check if string contains "PING" in ASCII anywhere, without allocating new objects
		for (int i = 0; i < line.length - 3; i++) {
			if (line[i] == 'P' && line[i + 1] == 'I' && line[i + 2] == 'N' && line[i + 3] == 'G') {
				// Candidate line found; do precise parse to decide
				int start = 0;
				
				// Discard optional prefix
				if (line.length >= 1 && line[0] == ':') {
					start++;
					while (start < line.length && line[start] == ' ')
						start++;
				}
				
				// Check command
				if (line.length - start >= 4 && line[start] == 'P' && line[start + 1] == 'I' && line[start + 2] == 'N' && line[start + 3] == 'G') {
					byte[] reply = Arrays.copyOfRange(line, start, line.length);  // Copy the command and parameters verbatim
					reply[1] = 'O';  // Change "PING" to "PONG"
					sendMessage(conId, reply, processorReader);
				}
				return;  // Because parsing the full line is precise, we do not need to check any more candidates
			}
		}
	}
	
	
	
	/*---- Miscellaneous methods ----*/
	
	private static byte[] serializeEventForProcessor(Event ev) {
		try {
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			bout.write(String.format("%d %d %d %d ", ev.connectionId, ev.sequence, ev.timestamp, ev.type.ordinal()).getBytes(OutputWriterThread.UTF8_CHARSET));
			bout.write(ev.getLine());
			return bout.toByteArray();
		} catch (IOException e) {
			throw new AssertionError(e);
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
