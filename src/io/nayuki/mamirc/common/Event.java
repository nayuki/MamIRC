package io.nayuki.mamirc.common;


// Immutable data structure that reflects all state changes in the connector.
// This information is conveyed from the connector to the processor through the network connection or through the archival database.
public final class Event {
	
	/*---- Fields ----*/
	
	public final int connectionId;
	public final int sequence;
	public final long timestamp;  // Based on Unix epoch, in milliseconds
	public final Type type;
	private final byte[] line;
	
	
	/*---- Constructors ----*/
	
	public Event(int conId, int seq, Type type, byte[] line) {
		this(conId, seq, System.currentTimeMillis(), type, line);
	}
	
	
	public Event(int conId, int seq, long time, Type type, byte[] line) {
		if (type == null || line == null)
			throw new NullPointerException();
		for (byte b : line) {
			if (b == '\0' || b == '\r' || b == '\n')
				throw new IllegalArgumentException("Invalid characters in line");
		}
		connectionId = conId;
		sequence = seq;
		timestamp = time;
		this.type = type;
		this.line = line.clone();  // Defensive copy
	}
	
	
	/*---- Methods ----*/
	
	public byte[] getLine() {
		return line.clone();  // Defensive copy
	}
	
	
	public String toString() {
		return String.format("Event(conId=%d, seq=%d, time=%d, type=%s, line=%s)",
			connectionId, sequence, timestamp, type.toString(), new String(line, OutputWriterThread.UTF8_CHARSET));
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
