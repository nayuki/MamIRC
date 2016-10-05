/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.util.ArrayList;
import java.util.List;


/* 
 * Buffers sequential updates and allows waiting for and retrieving them. Thread-safe.
 */
final class UpdateManager {
	
	/*---- Fields ----*/
	
	private int nextUpdateId;
	
	private List<Object[]> recentUpdates;
	
	
	
	/*---- Constructors ----*/
	
	public UpdateManager() {
		nextUpdateId = 0;
		recentUpdates = new ArrayList<>();
	}
	
	
	
	/*---- Methods ----*/
	
	public synchronized int getNextUpdateId() {
		return nextUpdateId;
	}
	
	
	public synchronized void addUpdate(Object... data) {
		recentUpdates.add(data);
		nextUpdateId++;
		if (recentUpdates.size() > 30000)  // Purge old updates to reduce memory usage
			recentUpdates.subList(0, recentUpdates.size() / 10).clear();
		this.notifyAll();
	}
	
	
	public synchronized List<Object[]> getUpdates(int startId, int maxWait) throws InterruptedException {
		if (startId < 0 || maxWait < 0 || maxWait > 5 * 60 * 1000)
			throw new IllegalArgumentException();
		if (maxWait > 0 && startId == nextUpdateId)
			this.wait(maxWait);
		int size = recentUpdates.size();
		if (nextUpdateId - size <= startId && startId <= nextUpdateId)
			return new ArrayList<>(recentUpdates.subList(size - (nextUpdateId - startId), size));
		else  // startId is too early or too late; need caller to resynchronize fully
			return null;
	}
	
}
