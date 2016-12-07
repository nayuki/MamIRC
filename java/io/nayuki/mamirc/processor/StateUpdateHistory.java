/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;


/* 
 * Receives data about state updates. This class discards the data, but subclasses might do something.
 */
class StateUpdateHistory {
	
	/*---- Methods ----*/
	
	// Receives an update and does nothing.
	public void addUpdate(Object... data) {}
	
}
