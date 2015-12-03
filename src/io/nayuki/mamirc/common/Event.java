package io.nayuki.mamirc.common;


// Immutable data structure that reflects all state changes in the connector.
// This information is conveyed from the connector to the processor
// through the network connection or through the archival database.
public final class Event {
	
	/*---- Fields ----*/
	
	public final int connectionId;  // 0, 1, 2, etc.
	public final int sequence;      // 0, 1, 2, etc., resetting with each connection
	public final long timestamp;    // Based on Unix epoch, in milliseconds
	public final Type type;         // Not null
	public final CleanLine line;    // Not null
	
	
	/*---- Constructors ----*/
	
	// Creates an event with the given data and uses the current time as the timestamp.
	public Event(int conId, int seq, Type type, CleanLine line) {
		this(conId, seq, System.currentTimeMillis(), type, line);
	}
	
	
	// Creates an event with the given data.
	// The byte array is cloned and stored in this data structure, so the caller
	// is allowed to change the array values after the constructor returns.
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
	
	public String toString() {
		return String.format("Event(conId=%d, seq=%d, time=%d, type=%s, line=%s)",
			connectionId, sequence, timestamp, type.toString(), line.getString());
	}
	
	
	/*---- Enclosing type ----*/
	
	public enum Type {
		CONNECTION, RECEIVE, SEND;
		
		private static Type[] values = values();
		
		public static Type fromOrdinal(int index) {
			return values[index];
		}
	}
	
}
