/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.common;

import java.io.IOException;
import com.almworks.sqlite4java.SQLiteException;


public abstract class WorkerThread extends Thread {
	
	public WorkerThread(String name) {
		super(name);
	}
	
	
	public final void run() {
		try {
			runInner();
		} catch (IOException|SQLiteException|InterruptedException e) {}
	}
	
	
	protected abstract void runInner() throws IOException, SQLiteException, InterruptedException;
	
	
	// Asynchronously interrupts whatever this thread is waiting on,
	// and tries to stop execution. Can be called from any thread.
	public abstract void shutdown();
	
}
