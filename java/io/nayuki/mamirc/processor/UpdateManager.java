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
 * Buffers a sequence of updates in memory, and allows waiting for and retrieving them. Thread-safe.
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
		if (recentUpdates.size() > 30000)  // Purge old updates to keep memory usage bounded
			recentUpdates.subList(0, recentUpdates.size() / 10).clear();
		this.notifyAll();
	}
	
	
	// Retrieves all updates from startId (inclusive) up to the current end of the list. If startId is equal to the next
	// ID and a positive wait time is given, then the method will wait up to that long for new updates. Returns null
	// if startId precedes the earliest ID in the current state of the list (due to purging) or exceeds the next ID.
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
