/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.common;


/* 
 * A simple data structure that represents an interaction that occurred between the MamIRC Connector
 * and an IRC server. Events are conveyed from the Connector to the Processor through a local connection
 * or through the archival database or both (it is the receiver's responsibility to perform deduplication).
 * Event objects are conceptually immutable, but this property cannot be enforced
 * because CleanLine is implemented in a way to allow unsafe zero-copy operations.
 */
public final class Event {
	
	/*---- Fields ----*/
	
	public final int connectionId;  // 0, 1, 2, etc., never resetting
	public final int sequence;      // 0, 1, 2, etc., resetting with each connection
	public final long timestamp;    // Based on the Unix epoch, in milliseconds
	public final Type type;         // Not null
	public final CleanLine line;    // Not null. Not fully immutable.
	
	
	/*---- Constructors ----*/
	
	// Constructs an event with the given data, and uses the current time as the timestamp.
	public Event(int conId, int seq, Type type, CleanLine line) {
		this(conId, seq, System.currentTimeMillis(), type, line);
	}
	
	
	// Constructs an event that stores the given pieces of data.
	public Event(int conId, int seq, long time, Type type, CleanLine line) {
		if (type == null || line == null)
			throw new NullPointerException();
		connectionId = conId;
		sequence = seq;
		timestamp = time;
		this.type = type;
		this.line = line;
	}
	
	
	/*---- Methods ----*/
	
	// Returns a string representation of this event, only for debugging purposes.
	public String toString() {
		return String.format("Event(conId=%d, seq=%d, time=%d, type=%s, line=%s)",
			connectionId, sequence, timestamp, type.toString(), line.getString());
	}
	
	
	
	/*---- Enclosing type ----*/
	
	public enum Type {
		CONNECTION,  // 0
		RECEIVE,     // 1
		SEND;        // 2
		
		private static Type[] values = values();
		
		public static Type fromOrdinal(int index) {
			return values[index];
		}
	}
	
}
