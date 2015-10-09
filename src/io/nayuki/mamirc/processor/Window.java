package io.nayuki.mamirc.processor;

import java.util.ArrayList;
import java.util.List;


final class Window {
	
	public List<Line> lines;
	public int nextSequence;
	
	
	
	public Window() {
		lines = new ArrayList<>();
		nextSequence = 0;
	}
	
	
	
	public void addLine(long timestamp, String payload, int flags) {
		lines.add(new Line(nextSequence, timestamp, payload, flags));
		nextSequence++;
	}
	
	
	
	public static final class Line {
		
		public final int sequence;
		public final long timestamp;
		public final String payload;
		
		/* 
		 * Bit 2: Outgoing PRIVMSG/NOTICE from me
		 * Bit 3: Incoming PRIVMSG/NOTICE containing my nickname
		 */
		public final int flags;
		
		
		public Line(int sequence, long timestamp, String payload, int flags) {
			if (payload == null)
				throw new NullPointerException();
			this.sequence = sequence;
			this.timestamp = timestamp;
			this.payload = payload;
			this.flags = flags;
		}
		
	}
	
}
