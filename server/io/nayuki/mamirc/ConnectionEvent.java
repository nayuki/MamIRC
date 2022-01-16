package io.nayuki.mamirc;

import java.net.InetAddress;


abstract class ConnectionEvent {
	
	public long timestampUnixMs = System.currentTimeMillis();
	
	
	
	/*---- Subclasses ----*/
	
	public static final class Opening extends ConnectionEvent {
		public String hostname;
		public int port;
		
		public Opening(String hostname, int port) {
			this.hostname = hostname;
			this.port = port;
		}
	}
	
	
	public static final class Opened extends ConnectionEvent {
		public InetAddress ipAddress;

		public Opened(InetAddress ipAddress) {
			this.ipAddress = ipAddress;
		}
	}
	
	
	public static final class LineReceived extends ConnectionEvent {
		public byte[] line;

		public LineReceived(byte[] line) {
			this.line = line;
		}
	}
	
	
	public static final class LineSent extends ConnectionEvent {
		public byte[] line;

		public LineSent(byte[] line) {
			this.line = line;
		}
	}
	
	
	public static final class ReadException extends ConnectionEvent {
		public String message;

		public ReadException(String message) {
			this.message = message;
		}
	}
	
	
	public static final class WriteException extends ConnectionEvent {
		public String message;

		public WriteException(String message) {
			this.message = message;
		}
	}
	
	
	public static final class Closing extends ConnectionEvent {}
	
	
	public static final class Closed extends ConnectionEvent {}
	
}
