package io.nayuki.mamirc;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;


abstract class ConnectionEvent {
	
	public long timestampUnixMs = System.currentTimeMillis();
	
	public abstract byte[] toBytes();
	
	
	
	/*---- Subclasses ----*/
	
	private static abstract class ConnectionEventStringRepr extends ConnectionEvent {
		public byte[] toBytes() {
			return toBytesAsString().getBytes(StandardCharsets.UTF_8);
		}
		
		protected abstract String toBytesAsString();
	}
	
	
	public static final class Opening extends ConnectionEventStringRepr {
		public String hostname;
		public int port;
		
		public Opening(String hostname, int port) {
			this.hostname = hostname;
			this.port = port;
		}
		
		protected String toBytesAsString() {
			return String.format("opening\n%s\n%d", hostname, port);
		}
	}
	
	
	public static final class Opened extends ConnectionEventStringRepr {
		public InetAddress ipAddress;
		
		public Opened(InetAddress ipAddress) {
			this.ipAddress = ipAddress;
		}
		
		protected String toBytesAsString() {
			String ipStr;
			byte[] raw = ipAddress.getAddress();
			List<String> parts = new ArrayList<>();
			if (ipAddress instanceof Inet4Address) {
				for (byte b : raw)
					parts.add(Integer.toString(b & 0xFF));
				ipStr = String.join(".", parts);
			} else if (ipAddress instanceof Inet6Address) {
				for (int i = 0; i < raw.length; i += 2) {
					int word =
						(raw[i + 0] & 0xFF) << 8 |
						(raw[i + 1] & 0xFF) << 0;
					parts.add(String.format("%04x", word));
				}
				ipStr = String.join(":", parts);
			} else
				throw new AssertionError();
			return String.format("opened\n%s", ipStr);
		}
	}
	
	
	public static final class LineReceived extends ConnectionEvent {
		public byte[] line;
		
		public LineReceived(byte[] line) {
			this.line = line;
		}
		
		public byte[] toBytes() {
			byte[] result = new byte[Math.addExact(line.length, 1)];
			result[0] = 'R';
			System.arraycopy(line, 0, result, 1, line.length);
			return result;
		}
	}
	
	
	public static final class LineSent extends ConnectionEvent {
		public byte[] line;
		
		public LineSent(byte[] line) {
			this.line = line;
		}
		
		public byte[] toBytes() {
			byte[] result = new byte[Math.addExact(line.length, 1)];
			result[0] = 'S';
			System.arraycopy(line, 0, result, 1, line.length);
			return result;
		}
	}
	
	
	public static final class ReadException extends ConnectionEventStringRepr {
		public String message;
		
		public ReadException(String message) {
			this.message = message;
		}
		
		protected String toBytesAsString() {
			return String.format("read exception\n%s", message);
		}
	}
	
	
	public static final class WriteException extends ConnectionEventStringRepr {
		public String message;
		
		public WriteException(String message) {
			this.message = message;
		}
		
		protected String toBytesAsString() {
			return String.format("write exception\n%s", message);
		}
	}
	
	
	public static final class Closing extends ConnectionEventStringRepr {
		protected String toBytesAsString() {
			return "closing";
		}
	}
	
	
	public static final class Closed extends ConnectionEventStringRepr {
		protected String toBytesAsString() {
			return "closed";
		}
	}
	
}
