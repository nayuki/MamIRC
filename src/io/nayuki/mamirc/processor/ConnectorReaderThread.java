package io.nayuki.mamirc.processor;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;
import io.nayuki.mamirc.common.CleanLine;
import io.nayuki.mamirc.common.BackendConfiguration;
import io.nayuki.mamirc.common.Event;
import io.nayuki.mamirc.common.LineReader;
import io.nayuki.mamirc.common.OutputWriterThread;
import io.nayuki.mamirc.common.Utils;


/* 
 * A worker thread that reads event lines from a socket connection to the MamIRC connector.
 * Additional functionality:
 * - Authenticates with the connector
 * - Parses the list of current active connections
 * - Reads database to catch up on all past events in the active connections
 * - Creates and terminates a writer thread for the socket
 */
final class ConnectorReaderThread extends Thread {
	
	/*---- Fields ----*/
	
	private final MamircProcessor master;
	private final BackendConfiguration configuration;
	private Socket socket;
	private OutputWriterThread writer;
	
	
	/*---- Constructor ----*/
	
	public ConnectorReaderThread(MamircProcessor master, BackendConfiguration config) {
		super("ConnectorReaderThread");
		if (master == null || config == null)
			throw new NullPointerException();
		this.master = master;
		configuration = config;
		socket = null;
		writer = null;
	}
	
	
	/*---- Methods ----*/
	
	public void run() {
		try {
			// Connect to connector, read and process archived events
			LineReader reader = init();
			
			// Process real-time events
			while (true) {
				String line = readStringLine(reader);
				if (line == null)
					break;
				String[] parts = line.split(" ", 5);
				Event ev = new Event(
					Integer.parseInt(parts[0]),
					Integer.parseInt(parts[1]),
					Long.parseLong(parts[2]),
					Event.Type.fromOrdinal(Integer.parseInt(parts[3])),
					new CleanLine(parts[4]));
				master.processEvent(ev, true);
			}
		} catch (IOException e) {
			e.printStackTrace();
		} catch (SQLiteException e) {
			e.printStackTrace();
		} finally {  // Clean up
			if (writer != null)
				writer.terminate();
			if (socket != null) {
				try {
					socket.close();
				} catch (IOException e) {}
			}
			master.terminate();
		}
	}
	
	
	private LineReader init() throws IOException, SQLiteException {
		// Connect and authenticate
		if (socket != null)
			throw new IllegalStateException();
		socket = new Socket("localhost", configuration.connectorServerPort);
		writer = new OutputWriterThread(socket.getOutputStream(), new byte[]{'\n'});
		master.attachConnectorWriter(writer);
		writer.start();
		writer.postWrite(new CleanLine(configuration.getConnectorPassword(), false));
		writer.postWrite(new CleanLine("attach"));
		
		// Read first line
		LineReader reader = new LineReader(socket.getInputStream());
		String line = readStringLine(reader);
		if (line == null)
			throw new RuntimeException("Authentication failure");
		if (!line.equals("active-connections"))
			throw new RuntimeException("Invalid data format");
		
		// Get set of current connections
		Map<Integer,Integer> connectionSequences = new HashMap<>();
		while (true) {
			line = readStringLine(reader);
			if (line.equals("end-list"))
				break;
			String[] parts = line.split(" ", 2);  // Connection ID, next (unused) sequence number
			connectionSequences.put(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
		}
		line = readStringLine(reader);
		if (!line.equals("live-events"))
			throw new RuntimeException("Invalid data format");
		
		// Read archived events from database and process them
		SQLiteConnection database = new SQLiteConnection(configuration.connectorDatabaseFile);
		try {
			database.open(false);
			SQLiteStatement query = database.prepare("SELECT sequence, timestamp, type, data FROM events WHERE connectionId=? AND sequence<? ORDER BY sequence ASC");
			for (int conId : connectionSequences.keySet()) {
				int nextSeq = connectionSequences.get(conId);
				query.bind(1, conId);
				query.bind(2, nextSeq);
				while (query.step()) {
					Event ev = new Event(conId, query.columnInt(0), query.columnLong(1), Event.Type.fromOrdinal(query.columnInt(2)), new CleanLine(query.columnBlob(3), false));
					master.processEvent(ev, false);  // Non-real-time
				}
				query.reset();
			}
		} finally {
			database.dispose();  // Automatically disposes its associated statements
		}
		
		master.finishCatchup();  // Fire off queued actions just before starting real-time processing
		return reader;
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
