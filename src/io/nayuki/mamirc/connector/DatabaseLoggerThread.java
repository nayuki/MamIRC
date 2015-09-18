package io.nayuki.mamirc.connector;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;
import io.nayuki.mamirc.common.Event;


final class DatabaseLoggerThread extends Thread {
	
	/*---- Fields ----*/
	
	private final MamircConnector master;
	private final SQLiteConnection database;
	private final BlockingQueue<Event> queue;
	
	private SQLiteStatement beginTransaction;
	private SQLiteStatement commitTransaction;
	private SQLiteStatement insertEvent;
	
	private boolean isTerminating;
	
	
	/*---- Constructor ----*/
	
	public DatabaseLoggerThread(MamircConnector master, File file) {
		if (master == null || file == null)
			throw new NullPointerException();
		this.master = master;
		queue = new ArrayBlockingQueue<>(1000);
		database = new SQLiteConnection(file);
		isTerminating = false;
	}
	
	
	/*---- Methods ----*/
	
	public void run() {
		try {
			// Prepare statements
			database.open(true);
			beginTransaction  = database.prepare("BEGIN TRANSACTION");
			commitTransaction = database.prepare("COMMIT TRANSACTION");
			
			// Initialize table
			database.exec("CREATE TABLE IF NOT EXISTS events(connectionId INTEGER, sequence INTEGER, timestamp INTEGER NOT NULL, type INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(connectionId, sequence))");
			insertEvent = database.prepare("INSERT INTO events VALUES(?,?,?,?,?)");
			
			// Query for next connection ID
			SQLiteStatement getMaxConId = database.prepare("SELECT max(connectionId) FROM events");
			if (!getMaxConId.step())
				throw new AssertionError();
			if (getMaxConId.columnNull(0))
				master.databaseReady(0);
			else
				master.databaseReady(getMaxConId.columnInt(0) + 1);
			getMaxConId.dispose();
			
			// Process events
			while (processBatchOfEvents());
			
		} catch (SQLiteException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {}
		finally {
			beginTransaction.dispose();
			commitTransaction.dispose();
			insertEvent.dispose();
			database.dispose();
		}
	}
	
	
	private static final int GATHER_DATA_DELAY     =  2000;  // In milliseconds
	private static final int DATABASE_COMMIT_DELAY = 10000;  // In milliseconds
	
	private boolean processBatchOfEvents() throws InterruptedException, SQLiteException {
		Event ev = queue.take();
		if (ev == TERMINATOR)
			return false;
		synchronized(this) {
			if (!isTerminating)
				wait(GATHER_DATA_DELAY);
		}
		beginTransaction.step();
		beginTransaction.reset();
		insertEventIntoDb(ev);
		
		boolean terminate = false;
		while (true) {
			ev = queue.poll();
			if (ev == null)
				break;
			if (ev == TERMINATOR) {
				terminate = true;
				break;
			}
			insertEventIntoDb(ev);
		}
		
		commitTransaction.step();
		commitTransaction.reset();
		if (terminate)
			return false;
		synchronized(this) {
			if (!isTerminating)
				wait(DATABASE_COMMIT_DELAY);
		}
		return true;
	}
	
	
	private void insertEventIntoDb(Event ev) throws SQLiteException {
		insertEvent.bind(1, ev.connectionId);
		insertEvent.bind(2, ev.sequence);
		insertEvent.bind(3, ev.timestamp);
		insertEvent.bind(4, ev.type.ordinal());
		insertEvent.bind(5, ev.getLine());
		if (insertEvent.step())
			throw new AssertionError();
		insertEvent.reset();
	}
	
	
	// Should only be called from the main thread.
	public void postEvent(Event event) {
		if (event == null)
			throw new NullPointerException();
		queue.add(event);
	}
	
	
	// Should only be called from the main thread.
	public void terminate() {
		queue.add(TERMINATOR);
		synchronized(this) {
			isTerminating = true;
			notify();
		}
	}
	
	
	private static final Event TERMINATOR = new Event(-1, -1, -1, Event.Type.CONNECTION, new byte[0]);
	
}
