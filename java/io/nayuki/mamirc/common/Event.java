/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.common;

import java.nio.charset.StandardCharsets;
import java.util.Objects;


/* 
 * Simple data structure representing an interaction that occurred
 * between the MamIRC Connector and an IRC server. These objects must
 * be treated as immutable (despite 'byte[] line' not enforcing it).
 * 
 * The Connector logs events to an archival database file.
 * It also sends them over a local connection to a Processor.
 */
public final class Event {
	
	/*---- Fields ----*/
	
	public final int connectionId;  // 0, 1, 2, etc., never resetting
	public final int sequence;      // 0, 1, 2, etc., resetting with each connection
	public final long timestamp;    // Based on the Unix epoch, in milliseconds
	public final Type type;         // Not null
	public final byte[] line;       // Not null. Users must not modify elements.
	
	
	
	/*---- Constructors ----*/
	
	// Creates an event with the given data, using the current time as the timestamp.
	public Event(int conId, int seq, Type type, byte[] line) {
		this(conId, seq, System.currentTimeMillis(), type, line);
	}
	
	
	// Creates an event that stores the given pieces of data.
	public Event(int conId, int seq, long time, Type type, byte[] line) {
		if (conId < 0 || seq < 0)
			throw new IllegalArgumentException();
		connectionId = conId;
		sequence = seq;
		timestamp = time;
		this.type = Objects.requireNonNull(type);
		this.line = Objects.requireNonNull(line);
	}
	
	
	
	/*---- Methods ----*/
	
	// Returns a string representation of this event, only for debugging purposes.
	public String toString() {
		return String.format("Event(conId=%d, seq=%d, time=%d, type=%s, line=%s)",
			connectionId, sequence, timestamp, type.toString(), new String(line, StandardCharsets.UTF_8));
	}
	
	
	
	/*---- Helper type ----*/
	
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
