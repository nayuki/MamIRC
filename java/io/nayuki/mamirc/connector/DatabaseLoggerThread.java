/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
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
import java.util.logging.Level;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;
import io.nayuki.mamirc.common.Event;
import io.nayuki.mamirc.common.LockHelper;
import io.nayuki.mamirc.common.Utils;
import io.nayuki.mamirc.common.WorkerThread;


/* 
 * A worker thread that receives event objects from the master and writes them to an SQLite database.
 * Additional functionality provided:
 * - Implements delays to cluster writes together and avoid writing too frequently
 * - Can synchronously flush queued events so that other readers can see the data
 */
final class DatabaseLoggerThread extends WorkerThread {
	
	/*---- Fields ----*/
	
	// The mutex that protects all shared data accesses.
	private final Lock lock;
	// The preferred convenient way to use the lock.
	private final LockHelper locker;
	// await() by this worker; signal() upon {queue non-empty OR flush request's rising edge}.
	private final Condition condAll;
	// await() by this worker; signal() upon flush request's rising edge.
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
		locker = new LockHelper(lock);
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
	
	
	protected void runInner() throws InterruptedException {
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
			while (!queue.isEmpty() || !terminateRequested)
				processBatchOfEvents();
			// Lock is still held while cleaning up and terminating
		}
		catch (SQLiteException e) {
			Utils.logger.log(Level.SEVERE, "Database error", e);
		}
		finally {
			database.dispose();  // Automatically disposes its associated statements
			Utils.logger.info("MamIRC Connector terminating");
			System.exit(1);  // The one and only way to terminate a MamircConnector processor
		}
	}
	
	
	private static final int WRITE_DELAY = 10000;  // In milliseconds
	
	// Must hold 'lock' before and after the method call.
	private void processBatchOfEvents() throws SQLiteException, InterruptedException {
		// Wait for something to do
		while (queue.isEmpty() && !flushRequested && !terminateRequested)
			condAll.await();
		if (Utils.logger.isLoggable(Level.FINEST)) {
			Utils.logger.finest(String.format(
				"Database thread awoke: queue.size()=%d, flushRequested=%b, terminateRequested=%b",
				queue.size(), flushRequested, terminateRequested));
		}
		
		if (flushRequested || terminateRequested) {
			// Drain the queue straightforwardly
			Utils.stepStatement(beginTransaction, false);
			while (!queue.isEmpty())
				insertEventIntoDb(queue.remove());
			Utils.stepStatement(commitTransaction, false);
			Utils.logger.finest("Wrote all pending events to database");
			flushRequested = false;
			condFlushed.signal();
			
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
				Utils.logger.finest("Wrote events to database: count=" + events.length);
			} finally {
				lock.lock();
			}
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
	public void postEvent(Event ev) {
		if (ev == null)
			throw new NullPointerException();
		try (LockHelper lh = locker.enter()) {
			queue.add(ev);
			condAll.signal();
		}
	}
	
	
	// Synchronously requests the worker thread to write and commit all queued events to
	// the database, blocking until finished. Should only be called from the connector object.
	public void flushQueue() {
		try (LockHelper lh = locker.enter()) {
			if (flushRequested)
				throw new IllegalStateException();
			flushRequested = true;
			condAll.signal();
			condUrgent.signal();
			do condFlushed.awaitUninterruptibly();
			while (flushRequested);
			if (!queue.isEmpty())
				throw new IllegalStateException();
		}
	}
	
	
	// Asynchronously requests this worker thread to flush all pending data and terminate the entire application.
	// Should only be called by MamircConnector.terminateConnector() and nothing else.
	public void terminate() {
		try (LockHelper lh = locker.enter()) {
			terminateRequested = true;
			flushQueue();
		}
	}
	
}
