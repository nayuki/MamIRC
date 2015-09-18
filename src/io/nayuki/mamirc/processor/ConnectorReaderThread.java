package io.nayuki.mamirc.processor;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;
import io.nayuki.mamirc.common.ConnectorConfiguration;
import io.nayuki.mamirc.common.Event;
import io.nayuki.mamirc.common.LineReader;
import io.nayuki.mamirc.common.OutputWriterThread;


final class ConnectorReaderThread extends Thread {
	
	/*---- Fields ----*/
	
	private final MamircProcessor master;
	private final ConnectorConfiguration configuration;
	private Socket socket;
	private OutputWriterThread writer;
	
	
	/*---- Constructor ----*/
	
	public ConnectorReaderThread(MamircProcessor master, ConnectorConfiguration config) {
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
				if (line == null) {
					writer.terminate();
					break;
				}
				String[] parts = line.split(" ", 5);
				Event ev = new Event(
					Integer.parseInt(parts[0]),
					Integer.parseInt(parts[1]),
					Long.parseLong(parts[2]),
					Event.Type.fromOrdinal(Integer.parseInt(parts[3])),
					parts[4].getBytes(OutputWriterThread.UTF8_CHARSET));
				master.processEvent(ev, true);
			}
			
		} catch (IOException e) {}
		catch (SQLiteException e) {}
		finally {
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
		socket = new Socket("localhost", configuration.serverPort);
		writer = new OutputWriterThread(socket.getOutputStream(), new byte[]{'\n'});
		master.attachConnectorWriter(writer);
		writer.start();
		writer.postWrite(configuration.getConnectorPassword());
		
		LineReader reader = new LineReader(socket.getInputStream());
		String line = readStringLine(reader);
		if (line == null)
			throw new RuntimeException("Authentication failure");
		
		// Get set of current connections
		if (!line.equals("active-connections"))
			throw new RuntimeException("Invalid data received");
		Map<Integer,Integer> connectionSequences = new HashMap<>();
		while (true) {
			line = readStringLine(reader);
			if (line.equals("live-events"))
				break;
			String[] parts = line.split(" ", 3);
			connectionSequences.put(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
		}
		
		// Read archived events from database
		List<Event> archivedEvents = new ArrayList<>();
		SQLiteConnection database = new SQLiteConnection(configuration.databaseFile);
		database.open(false);
		SQLiteStatement query = database.prepare("SELECT sequence, timestamp, type, data FROM events WHERE connectionId=? AND sequence<? ORDER BY sequence ASC");
		for (int conId : connectionSequences.keySet()) {
			int nextSeq = connectionSequences.get(conId);
			query.bind(1, conId);
			query.bind(2, nextSeq);
			while (query.step()) {
				Event ev = new Event(conId, query.columnInt(0), query.columnLong(1), Event.Type.fromOrdinal(query.columnInt(2)), query.columnBlob(3));
				archivedEvents.add(ev);
			}
			query.reset();
		}
		query.dispose();
		database.dispose();
		
		// Process all archived (non-real-time) events
		for (Event ev : archivedEvents)
			master.processEvent(ev, false);
		master.finishCatchup();  // Fire off queued actions just before starting real-time processing
		return reader;
	}
	
	
	private static String readStringLine(LineReader reader) throws IOException {
		byte[] line = reader.readLine();
		if (line == null)
			return null;
		else
			return new String(line, OutputWriterThread.UTF8_CHARSET);
	}
	
	
}
