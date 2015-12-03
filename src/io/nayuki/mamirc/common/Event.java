package io.nayuki.mamirc.common;


/* 
 * A simple immutable data structure that represents an interaction
 * that occurred between the MamIRC connector and an IRC server.
 * Events are conveyed from the connector to the processor through a local connection or through the
 * archival database or both (it is the receiver's responsibility to perform deduplication).
 */
public final class Event {
	
	/*---- Fields ----*/
	
	public final int connectionId;  // 0, 1, 2, etc., never resetting
	public final int sequence;      // 0, 1, 2, etc., resetting with each connection
	public final long timestamp;    // Based on the Unix epoch, in milliseconds
	public final Type type;         // Not null
	public final CleanLine line;    // Not null. Not fully immutable.
	
	
	/*---- Constructors ----*/
	
	// Constructs an event with the given data and uses the current time as the timestamp.
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
		/*0*/CONNECTION, /*1*/RECEIVE, /*2*/SEND;
		
		private static Type[] values = values();
		
		public static Type fromOrdinal(int index) {
			return values[index];
		}
	}
	
}
