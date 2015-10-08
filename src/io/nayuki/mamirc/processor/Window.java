package io.nayuki.mamirc.processor;

import java.util.ArrayList;
import java.util.List;


final class Window {
	
	public List<Line> lines;
	
	
	
	public Window() {
		lines = new ArrayList<>();
	}
	
	
	
	public static final class Line {
		
		public final long timestamp;
		public final String payload;
		
		/* 
		 * Bit 2: Outgoing PRIVMSG/NOTICE from me
		 * Bit 3: Incoming PRIVMSG/NOTICE containing my nickname
		 */
		public final int flags;
		
		
		public Line(long timestamp, String payload, int flags) {
			if (payload == null)
				throw new NullPointerException();
			this.timestamp = timestamp;
			this.payload = payload;
			this.flags = flags;
		}
		
	}
	
}
