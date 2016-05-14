/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.common;

import java.io.IOException;
import java.util.logging.Level;


/* 
 * A base class for worker threads that includes logging calls for debugging.
 */
public abstract class WorkerThread extends Thread {
	
	/*---- Constructor ----*/
	
	// Constructs a worker thread with the given thread name (not null).
	public WorkerThread(String name) {
		super(name);
		Utils.logger.fine(getClassName() + " created: id=" + getId());
	}
	
	
	
	/*---- Methods ----*/
	
	// Generates log events when this thread starts and ends, calls the
	// actual worker method, and logs an unhandled exception (if any).
	public final void run() {
		Utils.logger.fine(getClassName() + " started: id=" + getId());
		try {
			runInner();
		}
		catch (IOException e) {  // A very normal occurrence
			Utils.logger.log(Level.INFO, "Minor I/O exception occurred", e);
		}
		catch (AssertionError|IllegalArgumentException|NullPointerException e) {
			Utils.logger.log(Level.SEVERE, "Potential bug in application logic", e);
		}
		catch (Throwable e) {  // All unrecognized throwables, which may or may not be relevant
			Utils.logger.log(Level.WARNING, "Uncaught exception occurred", e);
		}
		Utils.logger.fine(getClassName() + " ended: id=" + getId());
	}
	
	
	// The worker method which subclasses must implement.
	protected abstract void runInner() throws Throwable;
	
	
	// Returns the concrete class name of this object, without the package.
	private String getClassName() {
		return getClass().getSimpleName();
	}
	
}
