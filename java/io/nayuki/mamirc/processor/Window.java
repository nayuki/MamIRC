/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * http://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

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
	
	
	
	// Timestamp is in seconds instead of milliseconds.
	public void addLine(int flags, long timestamp, Object... payload) {
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
		public final long timestamp;  // In seconds, not milliseconds
		public final Object[] payload;
		
		
		public Line(int sequence, int flags, long timestamp, Object... payload) {
			if (payload == null)
				throw new NullPointerException();
			this.sequence = sequence;
			this.flags = flags;
			this.timestamp = timestamp;
			this.payload = payload.clone();
		}
	}
	
	
	
	public enum Flags {
		RESERVED(0),
		CONNECTING(1),
		CONNECTED(2),
		DISCONNECTED(3),
		INITTOPIC(4),
		INITNOTOPIC(5),
		JOIN(6),
		KICK(7),
		MODE(8),
		NAMES(9),
		NICK(10),
		NOTICE(11),
		PART(12),
		PRIVMSG(13),
		QUIT(14),
		SERVERREPLY(15),
		TOPIC(16),
		TYPE_MASK((1 << 5) - 1),
		OUTGOING(1 << 5),
		NICKFLAG(1 << 6);
		
		public final int value;
		private Flags(int val) {
			value = val;
		}
	}
	
}
