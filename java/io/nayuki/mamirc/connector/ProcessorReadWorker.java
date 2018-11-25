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


final class ProcessorReadWorker extends Thread {
	
	/*---- Fields ----*/
	
	private final MamircConnector master;
	private final Socket socket;
	private final byte[] password;
	
	
	
	/*---- Constructor ----*/
	
	// This constructor must only be called from ProcessorListenWorker.
	public ProcessorReadWorker(MamircConnector master, Socket sock, byte[] password) {
		this.master   = Objects.requireNonNull(master);
		this.socket   = Objects.requireNonNull(sock);
		this.password = Objects.requireNonNull(password);
		start();
	}
	
	
	
	/*---- Methods ----*/
	
	public void run() {
		try {
			// Read password line with time limit
			LineReader reader;
			byte[] passwordLine;
			Future<?> killer = master.scheduler.schedule(() -> terminate(),
				AUTHENTICATION_TIMEOUT, TimeUnit.MILLISECONDS);
			try {
				reader = new LineReader(socket.getInputStream(), 1000);
				passwordLine = reader.readLine();
			} finally {
				killer.cancel(true);
			}
			if (passwordLine == null || !equalsTimingSafe(passwordLine, password))
				return;
			
			// Launch writer and register with Connector
			socket.setTcpNoDelay(true);
			OutputWriteWorker writer = new OutputWriteWorker(socket.getOutputStream(), new byte[]{'\r','\n'});
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
				writer.terminate();  // This reader is exclusively responsible for terminating the writer
			}
		} catch (IOException|InterruptedException e) {
		} finally {
			terminate();
		}
	}
	
	
	private void handleLine(byte[] line) throws InterruptedException {
		String lineStr = new String(line, StandardCharsets.UTF_8);
		String[] parts = lineStr.split(" ", 5);  // At most 5 parts in the current format
		String cmd = parts[0];
		try {
			
			if (cmd.equals("terminate") && parts.length == 1) {
				master.terminateConnector(this, "Explicit command received from Processor connection");
				
			} else if (cmd.equals("connect") && parts.length == 5) {
				if (!(parts[3].equals("true") || parts[3].equals("false")))
					throw new IllegalArgumentException();
				master.connectServer(this, parts[1], Integer.parseInt(parts[2]), Boolean.parseBoolean(parts[3]), parts[4]);
				
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
	
}
