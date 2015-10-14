package io.nayuki.mamirc.processor;

import java.util.ArrayList;
import java.util.List;


final class Window {
	
	public List<Line> lines;
	public int nextSequence;
	public int markedReadUntil;  // All lines with sequence < markedReadUntil are considered to be already read
	
	
	
	public Window() {
		lines = new ArrayList<>();
		nextSequence = 0;
		markedReadUntil = 0;
	}
	
	
	
	public void addLine(int flags, long timestamp, String payload) {
		lines.add(new Line(nextSequence, flags, timestamp, payload));
		nextSequence++;
	}
	
	
	// Deletes all lines with sequence numbers strictly less than the given number.
	public void clearUntil(int sequence) {
		int i;
		for (i = 0; i < lines.size() && lines.get(i).sequence < sequence; i++);
		lines.subList(0, i).clear();
		markedReadUntil = Math.max(markedReadUntil, sequence);
	}
	
	
	
	public static final class Line {
		public final int sequence;
		public final int flags;
		public final long timestamp;
		public final String payload;
		
		
		public Line(int sequence, int flags, long timestamp, String payload) {
			if (payload == null)
				throw new NullPointerException();
			this.sequence = sequence;
			this.flags = flags;
			this.timestamp = timestamp;
			this.payload = payload;
		}
	}
	
	
	
	public enum Flags {
		OUTGOING(1 << 0),
		NICKFLAG(1 << 1);
		
		public final int value;
		private Flags(int val) {
			value = val;
		}
	}
	
}
