package io.nayuki.mamirc.connector;

import java.io.IOException;
import java.net.Socket;
import io.nayuki.mamirc.common.LineReader;
import io.nayuki.mamirc.common.OutputWriterThread;


final class ProcessorReaderThread extends Thread {
	
	/*---- Fields ----*/
	
	private final MamircConnector master;
	public final Socket socket;
	private final byte[] password;
	private boolean isAuthenticated;
	
	
	/*---- Constructor ----*/
	
	public ProcessorReaderThread(MamircConnector master, Socket sock, byte[] password) {
		if (master == null || sock == null || password == null)
			throw new NullPointerException();
		this.master = master;
		socket = sock;
		this.password = password.clone();  // Defensive copy
	}
	
	
	/*---- Methods ----*/
	
	public void run() {
		OutputWriterThread writer = null;
		boolean isAttached = false;
		try {
			// Set up the authentication timeout
			isAuthenticated = false;
			Thread killer = new KillerThread();
			killer.start();
			
			// Read password line
			LineReader reader = new LineReader(socket.getInputStream());
			byte[] line = reader.readLine();
			killer.interrupt();
			if (line == null || !equalsTimingSafe(line, password))
				return;  // Authentication failure
			synchronized(this) {
				isAuthenticated = true;
			}
			
			// Launch writer thread
			writer = new OutputWriterThread(socket.getOutputStream(), new byte[]{'\r','\n'});
			writer.start();
			master.attachProcessor(this, writer);
			isAttached = true;
			
			// Process input lines
			while (true) {
				line = reader.readLine();
				if (line == null)
					break;
				
				String[] parts = new String(line, "UTF-8").split(" ", 5);
				String cmd = parts[0];
				if (cmd.equals("terminate") && parts.length == 1)
					master.terminateConnector(this);
				else if (cmd.equals("connect") && parts.length == 5)
					master.connectServer(parts[1], Integer.parseInt(parts[2]), Boolean.parseBoolean(parts[3]), parts[4], this);
				else if (cmd.equals("disconnect") && parts.length == 2)
					master.disconnectServer(Integer.parseInt(parts[1]), this);
				else if (cmd.equals("send")) {
					parts = new String(line, "UTF-8").split(" ", 3);
					master.sendMessage(Integer.parseInt(parts[1]), parts[2].getBytes(OutputWriterThread.UTF8_CHARSET), this);
				}
			}
			
		// Clean up
		} catch (IOException e) {}
		finally {
			if (isAttached)
				master.detachProcessor(this);
			try {
				socket.close();
			} catch (IOException e) {}
		}
	}
	
	
	/*---- Helper definitions ----*/
	
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
				synchronized(ProcessorReaderThread.this) {
					if (!isAuthenticated)
						socket.close();
				}
			} catch (IOException e) {}
			catch (InterruptedException e) {}
		}
	}
	
}
