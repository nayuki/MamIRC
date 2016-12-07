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
 * Buffers a sequence of updates in memory, and allows waiting for and retrieving them.
 * Thread-safe. Synchronization occurs on the intrinsic lock of objects of this class.
 */
final class LiveStateUpdateHistory extends StateUpdateHistory {
	
	/*---- Fields ----*/
	
	// Controls the size limit of recentUpdates.
	private final int bufferSize;
	
	// Starts at 0 and increments each time addUpdate() is called. Non-negative.
	private int nextUpdateId;
	
	// List of update objects. List and elements are not null. Users must not modify the elements of the arrays.
	// The last element of the list (if any) has updateId equal to nextUpdateId - 1.
	private final List<Object[]> recentUpdates;
	
	
	
	/*---- Constructors ----*/
	
	public LiveStateUpdateHistory(int bufSize) {
		if (bufSize <= 0)
			throw new IllegalArgumentException("Buffer size must be positive");
		bufferSize = bufSize;
		nextUpdateId = 0;
		recentUpdates = new ArrayList<>();
	}
	
	
	
	/*---- Methods ----*/
	
	// Returns the next update ID, which is always non-negative and non-decreasing over time.
	public synchronized int getNextUpdateId() {
		return nextUpdateId;
	}
	
	
	// Adds the given array of data to the list of updates, and alerts any waiting listeners.
	public synchronized void addUpdate(Object... data) {
		if (data == null)
			throw new NullPointerException();
		recentUpdates.add(data);
		nextUpdateId++;
		this.notifyAll();
		
		// Purge old updates periodically to limit memory usage
		if (recentUpdates.size() > bufferSize)
			recentUpdates.subList(0, recentUpdates.size() / 10).clear();
	}
	
	
	// Retrieves all updates from startId (inclusive) up to the current end of the list. If startId is equal to
	// the next ID and a positive wait time is given, then the method will wait up to that long for new updates.
	// Returns null if startId precedes the earliest ID in the current state of the list (due to purging).
	public synchronized List<Object[]> getUpdates(int startId, int maxWait) throws InterruptedException {
		// Check arguments and state
		if (startId < 0 || startId > nextUpdateId)
			throw new IllegalArgumentException("Invalid start update ID");
		if (maxWait < 0)
			throw new IllegalArgumentException("Invalid wait time");
		
		// Wait and/or handle sublist
		if (maxWait > 0 && startId == nextUpdateId)
			this.wait(maxWait);
		int size = recentUpdates.size();
		if (startId >= nextUpdateId - size)
			return new ArrayList<>(recentUpdates.subList(size - (nextUpdateId - startId), size));
		else  // startId is too early; need caller to resynchronize fully
			return null;
	}
	
}
