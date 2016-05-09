package io.nayuki.mamirc.common;

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
			
		} catch (Throwable e) {
			String s = String.format("%s encountered uncaught exception: id=%d, type=%s, msg=%s",
				getClassName(), getId(), e.getClass().getName(), e.getMessage());
			Utils.logger.log(Level.WARNING, s, e);
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
