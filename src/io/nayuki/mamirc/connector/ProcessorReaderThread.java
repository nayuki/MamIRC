package io.nayuki.mamirc.connector;

import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import io.nayuki.mamirc.common.LineReader;
import io.nayuki.mamirc.common.OutputWriterThread;


final class ProcessorReaderThread extends Thread {
	
	/*---- Fields ----*/
	
	private final MamircConnector master;
	private final Socket socket;
	private final byte[] password;
	
	
	/*---- Constructor ----*/
	
	// Should only be called from ProcessorListenerThread.
	public ProcessorReaderThread(MamircConnector master, Socket sock, byte[] password) {
		if (master == null || sock == null || password == null)
			throw new NullPointerException();
		this.master = master;
		socket = sock;
		this.password = password;
	}
	
	
	/*---- Methods ----*/
	
	public void run() {
		OutputWriterThread writer = null;
		try {
			// Set up the authentication timeout
			Thread killer = new KillerThread();
			killer.start();
			
			// Read password line
			LineReader reader = new LineReader(socket.getInputStream());
			byte[] line = reader.readLine();  // First line, thus not null
			killer.interrupt();  // Killer is no longer needed, now that we have read the line
			if (!equalsTimingSafe(line, password))
				return;  // Authentication failure
			
			// Launch writer thread
			writer = new OutputWriterThread(socket.getOutputStream(), new byte[]{'\r','\n'});
			writer.start();
			master.attachProcessor(this, writer);
			
			// Process input lines
			while (true) {
				line = reader.readLine();
				if (line == LineReader.BLANK_EOF || line == null)
					break;
				
				String lineStr = new String(line, "UTF-8");
				String[] parts = lineStr.split(" ", 5);  // At most 5 parts
				String cmd = parts[0];
				
				try {
					if (cmd.equals("terminate") && parts.length == 1) {
						master.terminateConnector(this);
						
					} else if (cmd.equals("connect") && parts.length == 5) {
						if (!(parts[3].equals("true") || parts[3].equals("false")) || parts[4].contains("\0"))
							throw new IllegalArgumentException();
						master.connectServer(parts[1], Integer.parseInt(parts[2]), Boolean.parseBoolean(parts[3]), parts[4], this);
						
					} else if (cmd.equals("disconnect") && parts.length == 2) {
						master.disconnectServer(Integer.parseInt(parts[1]), this);
						
					} else if (cmd.equals("send") && parts.length >= 3) {
						byte[] outLine = Arrays.copyOfRange(line, cmd.length() + parts[1].length() + 2, line.length);
						for (byte b : outLine) {
							if (b == '\0')
								throw new IllegalArgumentException();
						}
						master.sendMessage(Integer.parseInt(parts[1]), outLine, this);
						
					} else {
						System.err.println("Unknown line from processor: " + lineStr);
					}
				} catch (IllegalArgumentException e) {}
			}
			
		// Clean up
		} catch (IOException e) {}
		finally {
			if (writer != null) {
				master.detachProcessor(this);
				writer.terminate();  // This reader is exclusively responsible for terminating the writer
			}
			terminate();
		}
	}
	
	
	// Thread-safe, should only be called from MamircConnector or this class itself.
	public void terminate() {
		try {
			socket.close();
		} catch (IOException e) {}
	}
	
	
	/*---- Helper definitions ----*/
	
	// Performs a constant-time equality comparison if both arrays are the same length.
	private static boolean equalsTimingSafe(byte[] a, byte[] b) {
		if (a.length != b.length)
			return false;
		int diff = 0;
		for (int i = 0; i < a.length; i++)
			diff |= a[i] ^ b[i];
		return diff == 0;
	}
	
	
	private static final int AUTHENTICATION_TIMEOUT = 3000;  // In milliseconds
	
	private final class KillerThread extends Thread {
		public void run() {
			try {
				Thread.sleep(AUTHENTICATION_TIMEOUT);
				terminate();  // Will not be called if sleep is interrupted
			} catch (InterruptedException e) {}
		}
	}
	
}
