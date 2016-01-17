/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * http://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

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
import io.nayuki.mamirc.common.Utils;


/* 
 * A worker thread that receives event objects from the master and writes them to an SQLite database.
 * Additional functionality provided:
 * - Implements delays to cluster writes together and avoid writing too frequently
 * - Can synchronously flush queued events so that other readers can see the data
 */
final class DatabaseLoggerThread extends Thread {
	
	/*---- Fields ----*/
	
	// The mutex that protects all shared data accesses.
	private final Lock lock;
	// await() by this worker; signal() upon {queue non-empty OR flush request's rising edge OR terminate request's rising edge}.
	private final Condition condAll;
	// await() by this worker; signal() upon {flush request's rising edge OR terminate request's rising edge}.
	private final Condition condUrgent;
	// await() by caller of flushQueue(); signal() by this worker when all queue items at time of flushQueue() call
	// have been written and committed to database. no new items can be added while flushQueue() blocks because
	// the MamircConnector's global state is blocked.
	private final Condition condFlushed;
	
	// Monitor state (shared data accessed by various threads)
	private Queue<Event> queue;
	private boolean flushRequested;
	private boolean terminateRequested;
	
	// Database-related variables
	private final File databaseFile;
	private SQLiteConnection database;
	private SQLiteStatement beginTransaction;
	private SQLiteStatement commitTransaction;
	private SQLiteStatement insertEvent;
	
	
	/*---- Constructor ----*/
	
	// 'file' must be an existing file or a non-existent path, but not a directory.
	// This constructor initializes variables and objects but performs no I/O.
	public DatabaseLoggerThread(File file) {
		super("DatabaseLoggerThread");
		if (file == null)
			throw new NullPointerException();
		databaseFile = file;
		
		lock = new ReentrantLock();
		condAll     = lock.newCondition();
		condUrgent  = lock.newCondition();
		condFlushed = lock.newCondition();
		
		queue = new ArrayDeque<>();
		flushRequested = false;
		terminateRequested = false;
	}
	
	
	/*---- Methods ----*/
	
	// Initializes a database file if nonexistent, or reads from an existing one;
	// then this method returns the first suitable connection ID for the connector to use.
	public int initAndGetNextConnectionId() throws SQLiteException {
		if (database != null)
			throw new IllegalStateException();
		
		// Initialize table
		database = new SQLiteConnection(databaseFile);
		try {
			database.open(true);
			database.exec("PRAGMA journal_mode = PERSIST");
			database.exec("CREATE TABLE IF NOT EXISTS " +
				"events(connectionId INTEGER, sequence INTEGER, timestamp INTEGER NOT NULL, type INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(connectionId, sequence))");
			
			// Get current highest connection ID
			SQLiteStatement getMaxConId = database.prepare("SELECT max(connectionId) FROM events");
			Utils.stepStatement(getMaxConId, true);
			if (getMaxConId.columnNull(0))
				return 0;
			else
				return getMaxConId.columnInt(0) + 1;
		} finally {
			database.dispose();  // Automatically disposes its associated statements
		}
	}
	
	
	public void run() {
		if (database == null)
			throw new IllegalStateException();
		database = new SQLiteConnection(databaseFile);
		
		try {
			// Initialize database statements
			database.open(false);
			database.setBusyTimeout(60000);
			beginTransaction  = database.prepare("BEGIN TRANSACTION");
			commitTransaction = database.prepare("COMMIT TRANSACTION");
			insertEvent       = database.prepare("INSERT INTO events VALUES(?,?,?,?,?)");
			
			// Process incoming event objects
			lock.lock();
			try {
				while (processBatchOfEvents());
			} catch (SQLiteException e) {
				e.printStackTrace();
			} finally {
				queue = null;
				lock.unlock();
			}
			
		// Clean up
		} catch (SQLiteException e) {
			e.printStackTrace();
			System.exit(1);
		} catch (InterruptedException e) {  // Should not happen
			e.printStackTrace();
		}
		finally {
			database.dispose();  // Automatically disposes its associated statements
		}
	}
	
	
	private static final int WRITE_DELAY = 10000;  // In milliseconds
	
	// Must hold 'lock' before and after the method call.
	private boolean processBatchOfEvents() throws SQLiteException, InterruptedException {
		// Wait for something to do
		while (queue.isEmpty() && !flushRequested && !terminateRequested)
			condAll.await();
		
		if (flushRequested || terminateRequested) {
			// Drain the queue straightforwardly
			Utils.stepStatement(beginTransaction, false);
			while (!queue.isEmpty())
				insertEventIntoDb(queue.remove());
			Utils.stepStatement(commitTransaction, false);
			flushRequested = false;
			condFlushed.signal();
			return !terminateRequested;
			
		} else {
			// Wait to gather a burst of messages
			condUrgent.await(WRITE_DELAY, TimeUnit.MILLISECONDS);
			
			// Drain the queue without blocking on I/O
			Event[] events = new Event[queue.size()];
			for (int i = 0; i < events.length; i++)
				events[i] = queue.remove();
			if (!queue.isEmpty())
				throw new AssertionError();
			
			// Do all database I/O while allowing other threads to post events.
			// Note: Queue is empty and lock is dropped, but the data is not committed yet!
			// Thus flushQueue() cannot simply check for an empty queue and
			// return without explicit acknowledgement from this worker thread.
			lock.unlock();
			try {
				Utils.stepStatement(beginTransaction, false);
				for (Event ev : events)
					insertEventIntoDb(ev);
				Utils.stepStatement(commitTransaction, false);
			} finally {
				lock.lock();
			}
			// At this point, the queue may be non-empty and the flags may have changed
			return true;  // Re-evaluate the full situation even if termination is requested
		}
	}
	
	
	private void insertEventIntoDb(Event ev) throws SQLiteException {
		insertEvent.bind(1, ev.connectionId);
		insertEvent.bind(2, ev.sequence);
		insertEvent.bind(3, ev.timestamp);
		insertEvent.bind(4, ev.type.ordinal());
		insertEvent.bind(5, ev.line.getDataNoCopy());
		Utils.stepStatement(insertEvent, false);
	}
	
	
	// Should only be called from a thread currently executing in the MamircConnector object's context.
	public void postEvent(Event event) {
		if (event == null)
			throw new NullPointerException();
		lock.lock();
		try {
			queue.add(event);
			condAll.signal();
		} finally {
			lock.unlock();
		}
	}
	
	
	// Synchronously requests the worker thread to write and commit all queued events to
	// the database, blocking until finished. Should only be called from the connector object.
	public void flushQueue() {
		lock.lock();
		try {
			if (flushRequested)
				throw new IllegalStateException();
			flushRequested = true;
			condAll.signal();
			condUrgent.signal();
			do condFlushed.awaitUninterruptibly();
			while (flushRequested);
			if (!queue.isEmpty())
				throw new IllegalStateException();
		} finally {
			lock.unlock();
		}
	}
	
	
	// Asynchronously requests this worker thread to flush all data and terminate.
	// Can be called from any thread, but should only be called from the connector object.
	public void terminate() {
		lock.lock();
		try {
			terminateRequested = true;
			flushRequested = true;
			condAll.signal();
			condUrgent.signal();
		} finally {
			lock.unlock();
		}
	}
	
}
