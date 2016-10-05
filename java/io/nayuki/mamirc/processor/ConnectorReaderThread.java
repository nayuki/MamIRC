/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import io.nayuki.mamirc.common.BackendConfiguration;
import io.nayuki.mamirc.common.CleanLine;
import io.nayuki.mamirc.common.Event;
import io.nayuki.mamirc.common.LineReader;
import io.nayuki.mamirc.common.OutputWriterThread;
import io.nayuki.mamirc.common.Utils;
import io.nayuki.mamirc.common.WorkerThread;


/* 
 * A worker thread that reads event lines from a socket connection to the MamIRC Connector.
 * Additional functionality:
 * - Authenticates with the connector
 * - Parses the list of current active connections
 * - Creates and terminates a writer thread for the socket
 */
final class ConnectorReaderThread extends WorkerThread {
	
	/*---- Fields ----*/
	
	private final MamircProcessor master;
	private Socket socket;
	private LineReader reader;
	private OutputWriterThread writer;
	
	
	
	/*---- Constructor ----*/
	
	public ConnectorReaderThread(MamircProcessor master, BackendConfiguration config) throws IOException {
		super("ConnectorReaderThread");
		if (master == null || config == null)
			throw new NullPointerException();
		this.master = master;
		
		// Connect and authenticate
		socket = new Socket("localhost", config.connectorServerPort);
		Utils.logger.info("Socket opened to " + socket.getInetAddress() + " port " + socket.getPort());
		writer = new OutputWriterThread(socket.getOutputStream(), new byte[]{'\n'});
		writer.start();
		writer.postWrite(new CleanLine(config.getConnectorPassword(), false));
		writer.postWrite(new CleanLine("attach"));
		
		// Read first line
		reader = new LineReader(socket.getInputStream());
		String line = readStringLine(reader);
		if (line == null)
			throw new RuntimeException("Authentication failure");
		if (!line.equals("active-connections"))
			throw new RuntimeException("Invalid data format");
		Utils.logger.info("Connector connection successfully authenticated");
	}
		
	
	
	/*---- Methods ----*/
	
	public OutputWriterThread readInitialDataAndGetWriter(Map<Integer,Integer> connectionSequences) throws IOException {
		if (connectionSequences == null)
			throw new NullPointerException();
		if (connectionSequences.size() != 0)
			throw new IllegalArgumentException();
		if (getState() != Thread.State.NEW)
			throw new IllegalStateException();
		
		// Read the set of current connections
		while (true) {
			String line = readStringLine(reader);
			if (line.equals("end-list"))
				break;
			String[] parts = line.split(" ", 2);  // Connection ID, next (unused) sequence number
			connectionSequences.put(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
		}
		String line = readStringLine(reader);
		if (!line.equals("live-events"))
			throw new RuntimeException("Invalid data format");
		Utils.logger.fine("Received current set of connections: " + connectionSequences);
		return writer;
	}
	
	
	public void runInner() throws IOException {
		try {
			// Read and parse real-time events
			while (true) {
				String line = readStringLine(reader);
				if (line == null)
					break;
				Utils.logger.finest("Received event from Connector");
				String[] parts = line.split(" ", 5);
				Event ev = new Event(
					Integer.parseInt(parts[0]),
					Integer.parseInt(parts[1]),
					Long.parseLong(parts[2]),
					Event.Type.fromOrdinal(Integer.parseInt(parts[3])),
					new CleanLine(parts[4]));
				master.processEvent(ev);
			}
		}
		finally {  // Clean up
			writer.terminate();
			if (socket != null) {
				try {
					socket.close();
				} catch (IOException e) {}
			}
			master.terminateProcessor("Lost connection to Connector");
		}
	}
	
	
	// Returns the next line from the given reader decoded as UTF-8, or null if the end of stream is reached.
	private static String readStringLine(LineReader reader) throws IOException {
		byte[] line = reader.readLine();
		if (line == LineReader.BLANK_EOF || line == null)
			return null;
		else
			return Utils.fromUtf8(line);
	}
	
}
