package io.nayuki.mamirc.connector;

import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import io.nayuki.mamirc.common.CleanLine;
import io.nayuki.mamirc.common.LineReader;
import io.nayuki.mamirc.common.OutputWriterThread;
import io.nayuki.mamirc.common.Utils;


/* 
 * Handles lines of commands from a processor connection and calls appropriate methods on MamircConnector.
 * Additional functionality:
 * - Reads and checks the password before doing anything
 * - Explicitly terminates the connection if the correct password is not received within a few seconds
 * - Creates and terminates a writer thread for the socket
 * 
 * These and only these line formats are allowed coming from the processor:
 * - "connect <hostname> <port> <useSsl> <metadata>"
 *   where hostname is in UTF-8, port is an integer in [0,65535], useSsl is true/false;
 *   metadata is in UTF-8 and can contain spaces.
 * - "disconnect <connectionId>"
 *   where connectionId is a non-negative integer.
 * - "send <connectionId> <payload>"
 *   where connectionId is a non-negative integer,
 *   and payload is a byte sequence (not necessarily UTF-8).
 * - "terminate"
 *   which requests the connector to shut down cleanly.
 * Notes:
 * - The line formats above are parsed as strictly as possible.
 *   For example: case-sensitive, no double spaces between fields, no ignoring trailing spaces.
 * - No line can contain any '\0' (NUL) characters.
 * - Because fields are space-separated, only the last field might contain spaces.
 *   Even then, it needs to be explicitly allowed by the documentation above.
 */
final class ProcessorReaderThread extends Thread {
	
	/*---- Fields ----*/
	
	private final MamircConnector master;
	private final Socket socket;
	private final byte[] password;
	
	
	/*---- Constructor ----*/
	
	// This constructor must only be called from ProcessorListenerThread.
	public ProcessorReaderThread(MamircConnector master, Socket sock, byte[] password) {
		super("ProcessorReaderThread " + (System.nanoTime() % 997));  // Generate a short, random-ish ID
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
			byte[] passwordLine = reader.readLine();  // First line, thus not null
			if (!equalsTimingSafe(passwordLine, password))
				return;  // Authentication failure
			
			// Launch writer thread
			writer = new OutputWriterThread(socket.getOutputStream(), new byte[]{'\r','\n'});
			writer.setName("OutputWriterThread : " + this.getName());
			writer.start();
			
			// Read action line
			String actionLine = Utils.fromUtf8(reader.readLine());
			killer.interrupt();  // Killer is no longer needed, now that we have read the lines
			if (actionLine.equals("list-connections")) {
				master.listConnectionsToProcessor(writer);
			} else if (actionLine.equals("attach")) {
				try {
					master.attachProcessor(this, writer);
					while (true) {  // Process input lines
						byte[] line = reader.readLine();
						if (line == LineReader.BLANK_EOF || line == null)
							break;
						handleLine(line);
					}
				} finally {
					master.detachProcessor(this);
				}
			}
		} catch (IOException e) {}
		finally {  // Clean up
			if (writer != null) {
				writer.terminate();  // This reader is exclusively responsible for terminating the writer
				try {
					writer.join();
				} catch (InterruptedException e) {}
			}
			terminate();
		}
	}
	
	
	private void handleLine(byte[] line) {
		String lineStr = Utils.fromUtf8(line);
		String[] parts = lineStr.split(" ", 5);  // At most 5 parts in the current format
		String cmd = parts[0];
		
		try {
			if (cmd.equals("terminate") && parts.length == 1) {
				master.terminateConnector(this);
				
			} else if (cmd.equals("connect") && parts.length == 5) {
				if (!(parts[3].equals("true") || parts[3].equals("false")))
					throw new IllegalArgumentException();
				master.connectServer(parts[1], Integer.parseInt(parts[2]), Boolean.parseBoolean(parts[3]), parts[4], this);
				
			} else if (cmd.equals("disconnect") && parts.length == 2) {
				master.disconnectServer(Integer.parseInt(parts[1]), this);
				
			} else if (cmd.equals("send") && parts.length >= 3) {
				byte[] payload = Arrays.copyOfRange(line, cmd.length() + parts[1].length() + 2, line.length);
				master.sendMessage(Integer.parseInt(parts[1]), new CleanLine(payload, false), this);
				
			} else {
				System.err.println("Unknown line from processor: " + lineStr);
			}
		} catch (IllegalArgumentException e) {}
	}
	
	
	// Thread-safe, and should only be called from MamircConnector or this class itself.
	public void terminate() {
		try {
			socket.close();
		} catch (IOException e) {}
	}
	
	
	/*---- Helper definitions ----*/
	
	// Performs a constant-time equality check, if both arrays are the same length.
	// This prevents the use of timing attacks to guess passwords.
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
