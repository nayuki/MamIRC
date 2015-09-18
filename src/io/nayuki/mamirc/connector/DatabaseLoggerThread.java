package io.nayuki.mamirc.connector;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;
import io.nayuki.mamirc.common.Event;


final class DatabaseLoggerThread extends Thread {
	
	/*---- Fields ----*/
	
	private final MamircConnector master;
	
	// Synchronization
	private final Lock lock;
	private final Condition condAll;
	private final Condition condUrgent;
	private final Condition condFlushed;
	
	// Monitor state
	private final Queue<Event> queue;
	private boolean flushRequested;
	private boolean terminateRequested;
	
	// Database access
	private final SQLiteConnection database;
	private SQLiteStatement beginTransaction;
	private SQLiteStatement commitTransaction;
	private SQLiteStatement insertEvent;
	
	
	/*---- Constructor ----*/
	
	public DatabaseLoggerThread(MamircConnector master, File file) {
		if (master == null || file == null)
			throw new NullPointerException();
		this.master = master;
		
		lock = new ReentrantLock();
		condAll     = lock.newCondition();
		condUrgent  = lock.newCondition();
		condFlushed = lock.newCondition();
		
		queue = new ArrayDeque<>();
		flushRequested = false;
		terminateRequested = false;
		
		database = new SQLiteConnection(file);
	}
	
	
	/*---- Methods ----*/
	
	public void run() {
		try {
			// Initialize table, prepare statements
			database.open(true);
			database.exec("CREATE TABLE IF NOT EXISTS events(connectionId INTEGER, sequence INTEGER, timestamp INTEGER NOT NULL, type INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(connectionId, sequence))");
			beginTransaction  = database.prepare("BEGIN TRANSACTION");
			commitTransaction = database.prepare("COMMIT TRANSACTION");
			insertEvent       = database.prepare("INSERT INTO events VALUES(?,?,?,?,?)");
			
			// Query for next connection ID
			SQLiteStatement getMaxConId = database.prepare("SELECT max(connectionId) FROM events");
			step(getMaxConId, true);
			if (getMaxConId.columnNull(0))
				master.databaseReady(0);
			else
				master.databaseReady(getMaxConId.columnInt(0) + 1);
			getMaxConId.dispose();
			
			// Process events
			lock.lock();
			try {
				while (processBatchOfEvents());
			} finally {
				lock.unlock();
			}
			
		// Clean up
		} catch (SQLiteException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
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
		// Wait for something to do
		while (queue.isEmpty() && !flushRequested && !terminateRequested)
			condAll.await();
		
		if (queue.isEmpty()) {
			if (flushRequested) {
				flushRequested = false;
				condFlushed.signal();
			}
			
		} else {  // Drain the queue. Release the lock during database operations to allow other threads to add to the queue.
			if (!flushRequested)
				condUrgent.await(GATHER_DATA_DELAY, TimeUnit.MILLISECONDS);
			
			lock.unlock();
			step(beginTransaction, false);
			beginTransaction.reset();
			lock.lock();
			
			while (!queue.isEmpty()) {
				Event ev = queue.remove();
				lock.unlock();
				insertEventIntoDb(ev);
				lock.lock();
			}
			
			if (flushRequested) {
				// Synchronous commit
				step(commitTransaction, false);
				flushRequested = false;
				condFlushed.signal();
				commitTransaction.reset();
			} else {
				// Asynchronous commit
				lock.unlock();
				step(commitTransaction, false);
				commitTransaction.reset();
				lock.lock();
			}
		}
		
		if (terminateRequested)
			return false;
		else {
			condUrgent.await(DATABASE_COMMIT_DELAY, TimeUnit.MILLISECONDS);
			return true;
		}
	}
	
	
	private void insertEventIntoDb(Event ev) throws SQLiteException {
		insertEvent.bind(1, ev.connectionId);
		insertEvent.bind(2, ev.sequence);
		insertEvent.bind(3, ev.timestamp);
		insertEvent.bind(4, ev.type.ordinal());
		insertEvent.bind(5, ev.getLine());
		step(insertEvent, false);
		insertEvent.reset();
	}
	
	
	// Should only be called from the connector object.
	public void postEvent(Event event) {
		if (event == null)
			throw new NullPointerException();
		lock.lock();
		queue.add(event);
		condAll.signal();
		lock.unlock();
	}
	
	
	// Synchronously requests all queued events to be committed to the database,
	// blocking until finished. Should only be called from the connector object.
	public void flushQueue() {
		lock.lock();
		if (flushRequested)
			throw new IllegalStateException();
		if (!queue.isEmpty()) {
			flushRequested = true;
			condAll.signal();
			condUrgent.signal();
			do condFlushed.awaitUninterruptibly();
			while (flushRequested);
			if (!queue.isEmpty())
				throw new IllegalStateException();
		}
		lock.unlock();
	}
	
	
	// Asynchronously requests this thread to terminate. Should only be called from the connector object.
	public void terminate() {
		lock.lock();
		terminateRequested = true;
		condAll.signal();
		condUrgent.signal();
		lock.unlock();
	}
	
	
	private static void step(SQLiteStatement stat, boolean expectingResult) throws SQLiteException {
		if (stat.step() != expectingResult)
			throw new AssertionError();
	}
	
}
