/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.connector;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import io.nayuki.mamirc.common.LineReader;
import io.nayuki.mamirc.common.OutputWriteWorker;
import io.nayuki.mamirc.common.WorkerThread;


final class ProcessorReadWorker extends WorkerThread {
	
	/*---- Fields ----*/
	
	private final MamircConnector master;
	private final Socket socket;
	private final byte[] password;
	
	
	
	/*---- Constructor ----*/
	
	// This constructor must only be called from ProcessorListenWorker.
	public ProcessorReadWorker(MamircConnector master, Socket sock, byte[] password) {
		super("Processor Reader");
		this.master   = Objects.requireNonNull(master);
		this.socket   = Objects.requireNonNull(sock);
		this.password = Objects.requireNonNull(password);
		start();
	}
	
	
	
	/*---- Methods ----*/
	
	protected void runInner() throws IOException, InterruptedException {
		try (Socket sock = socket) {
			// Read password line with time limit
			LineReader reader;
			byte[] passwordLine;
			Future<?> killer = master.scheduler.schedule(() -> shutdown(),
				AUTHENTICATION_TIMEOUT, TimeUnit.MILLISECONDS);
			try {
				reader = new LineReader(sock.getInputStream(), 1000);
				passwordLine = reader.readLine();
			} finally {
				killer.cancel(true);
			}
			if (passwordLine == null || !equalsTimingSafe(passwordLine, password))
				return;
			
			// Launch writer and register with Connector
			sock.setTcpNoDelay(true);
			OutputWriteWorker writer = new OutputWriteWorker("Processor Writer",
				sock.getOutputStream(), new byte[]{'\r','\n'});
			try {
				master.attachProcessor(this, writer);
				
				try {  // Process input lines
					while (true) {
						byte[] line = reader.readLine();
						if (line == null)
							break;
						handleLine(line);
					}
					
				} finally {
					master.detachProcessor(this);
				}
			} finally {
				writer.shutdown();  // This reader is exclusively responsible for shutting down the writer
			}
		}
	}
	
	
	private void handleLine(byte[] line) throws InterruptedException {
		String lineStr = new String(line, StandardCharsets.UTF_8);
		String[] parts = lineStr.split(" ", 6);  // At most 6 parts in the current format
		String cmd = parts[0];
		try {
			
			if (cmd.equals("shutdown") && parts.length == 1) {
				master.shutdownConnector(this, "Explicit command received from Processor connection");
				
			} else if (cmd.equals("connect") && parts.length == 6) {
				String hostname = parts[1];
				int port = Integer.parseInt(parts[2]);
				String useSsl = parts[3];
				int timeout = Integer.parseInt(parts[4]);
				String profileName = parts[5];
				if (!(useSsl.equals("true") || useSsl.equals("false")))
					throw new IllegalArgumentException();
				master.connectServer(this, hostname, port, Boolean.parseBoolean(useSsl), timeout, profileName);
				
			} else if (cmd.equals("disconnect") && parts.length == 2) {
				master.disconnectServer(this, Integer.parseInt(parts[1]));
				
			} else if (cmd.equals("send") && parts.length >= 3) {
				byte[] payload = Arrays.copyOfRange(line, cmd.length() + parts[1].length() + 2, line.length);
				master.sendMessage(this, Integer.parseInt(parts[1]), payload);
				
			} else {
				System.err.println("Unknown line from Processor: " + lineStr);
			}
			
		} catch (IllegalArgumentException e) {  // Includes NumberFormatException
			System.err.println("Invalid line format from Processor: " + lineStr);
		}
	}
	
	
	// Asynchronously closes the socket; then requests this worker thread to
	// stop reading lines and stop execution. Can be called from any thread.
	public void shutdown() {
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
	
}
