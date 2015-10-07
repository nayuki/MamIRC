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
		
		public Line(long timestamp, String payload) {
			if (payload == null)
				throw new NullPointerException();
			this.timestamp = timestamp;
			this.payload = payload;
		}
		
	}
	
}
