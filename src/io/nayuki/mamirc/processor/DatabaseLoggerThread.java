package io.nayuki.mamirc.processor;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;


final class DatabaseLoggerThread extends Thread {
	
	/*---- Fields ----*/
	
	private final MamircProcessor master;
	private final SQLiteConnection database;
	private final BlockingQueue<Object[]> queue;
	private SQLiteStatement queryWindow;
	private SQLiteStatement queryWindowMax;
	private SQLiteStatement insertWindow;
	private SQLiteStatement insertMessage;
	private Map<String,Integer> windowIdCache;
	
	
	/*---- Constructor ----*/
	
	public DatabaseLoggerThread(MamircProcessor master, File file) {
		if (master == null || file == null)
			throw new NullPointerException();
		this.master = master;
		queue = new ArrayBlockingQueue<>(1000);
		windowIdCache = new HashMap<>();
		database = new SQLiteConnection(file);
	}
	
	
	/*---- Methods ----*/
	
	public void run() {
		try {
			// Initialize table
			database.open(true);
			database.exec("CREATE TABLE IF NOT EXISTS windows(id INTEGER PRIMARY KEY, profile TEXT, party TEXT)");
			database.exec("CREATE TABLE IF NOT EXISTS messages(connectionId INTEGER, sequence INTEGER, timestamp INTEGER NOT NULL, windowId INTEGER, line TEXT NOT NULL, PRIMARY KEY(connectionId, sequence, windowId))");
			queryWindow = database.prepare("SELECT id FROM windows WHERE profile=? AND party=?");
			queryWindowMax = database.prepare("SELECT max(id) FROM windows");
			insertWindow = database.prepare("INSERT INTO windows VALUES(?,?,?)");
			insertMessage = database.prepare("INSERT OR IGNORE INTO messages VALUES(?,?,?,?,?)");
			database.setBusyTimeout(60000);
			master.databaseLoggerReady(this);
			
			// Process events
			while (processBatchOfEvents());
			
		} catch (SQLiteException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {}
		finally {
			queryWindow.dispose();
			queryWindowMax.dispose();
			insertWindow.dispose();
			insertMessage.dispose();
			database.dispose();
		}
	}
	
	
	private boolean processBatchOfEvents() throws InterruptedException, SQLiteException {
		Object[] msg = queue.take();
		if (msg == TERMINATOR)
			return false;
		database.exec("BEGIN TRANSACTION");
		insertMessageIntoDb(msg);
		
		boolean terminate = false;
		while (true) {
			msg = queue.poll();
			if (msg == null)
				break;
			if (msg == TERMINATOR) {
				terminate = true;
				break;
			}
			insertMessageIntoDb(msg);
		}
		
		database.exec("COMMIT TRANSACTION");
		return !terminate;
	}
	
	
	private void insertMessageIntoDb(Object[] msg) throws SQLiteException {
		insertMessage.bind(1, (Integer)msg[0]);
		insertMessage.bind(2, (Integer)msg[1]);
		insertMessage.bind(3, (Long)msg[2]);
		insertMessage.bind(4, getWindowId((String)msg[3], (String)msg[4]));
		insertMessage.bind(5, (String)msg[5]);
		if (insertMessage.step())
			throw new AssertionError();
		insertMessage.reset();
	}
	
	
	private int getWindowId(String profile, String party) throws SQLiteException {
		String key = profile + "\n" + party;
		if (!windowIdCache.containsKey(key)) {
			int id;
			queryWindow.bind(1, profile);
			queryWindow.bind(2, party);
			if (queryWindow.step())
				id = queryWindow.columnInt(0);
			
			else {
				if (!queryWindowMax.step())
					throw new AssertionError();
				if (queryWindowMax.columnNull(0))
					id = 0;
				else
					id = queryWindowMax.columnInt(0) + 1;
				queryWindowMax.reset();
				
				insertWindow.bind(1, id);
				insertWindow.bind(2, profile);
				insertWindow.bind(3, party);
				if (insertWindow.step())
					throw new AssertionError();
				insertWindow.reset();
			}
			queryWindow.reset();
			windowIdCache.put(key, id);
		}
		return windowIdCache.get(key);
	}
	
	
	// Should only be called from the main thread.
	public void postMessage(int connectionId, int sequence, long timestamp, String profile, String party, String line) {
		queue.add(new Object[]{connectionId, sequence, timestamp, profile, party, line});
	}
	
	
	// Should only be called from the main thread.
	public void terminate() {
		queue.add(TERMINATOR);
	}
	
	
	private static final Object[] TERMINATOR = {};
	
}
