/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.util.TimerTask;


// Holds information about how to attempt to connect to servers in a profile. Mutable, not thread-safe.
final class ConnectionAttemptState {
	
	/*---- Fields ----*/
	
	// The number of connection attempts before the current one. Starts at zero and increments (must be non-negative).
	public int attemptCount;
	
	// The next server index to attempt to connect to. Must be in the range [0, servers.size()).
	public int serverIndex;
	
	// The number of milliseconds to wait if the current connection attempt fails.
	public int failureDelay;
	
	// The task object for the next connection attempt, kept if the attempt needs to be prematurely cancelled.
	public TimerTask timerTask;
	
	
	
	/*---- Constructors ----*/
	
	public ConnectionAttemptState() {
		attemptCount = 0;
		serverIndex = 0;
		failureDelay = 1000;
		timerTask = null;
	}
	
	
	
	/*---- Methods ----*/
	
	public int nextAttempt() {
		int result = failureDelay;
		attemptCount++;
		serverIndex++;
		if (failureDelay < 200000)
			failureDelay *= 2;
		return result;
	}
	
}
