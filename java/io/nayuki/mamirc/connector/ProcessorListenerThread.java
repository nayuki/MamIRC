/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * http://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.connector;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import io.nayuki.mamirc.common.Utils;


/* 
 * Manages a local server socket to listen for incoming processor connections,
 * and launches a new ProcessorReaderThread on each connection received.
 * This class implements rate-limiting to prevent denial-of-service attacks (but because the socket only listens
 * to localhost, the attacker would be another process running by this user, or another user on this machine).
 */
final class ProcessorListenerThread extends Thread {
	
	/*---- Fields ----*/
	
	private final MamircConnector master;
	private final ServerSocket serverSocket;
	private final byte[] password;
	
	
	/*---- Constructor ----*/
	
	// The server socket is created on the caller's thread, to make the caller deal with an I/O exception immediately.
	public ProcessorListenerThread(MamircConnector master, int port, byte[] password) throws IOException {
		super("ProcessorListenerThread");
		if (master == null || password == null)
			throw new NullPointerException();
		Utils.checkPortNumber(port);
		
		this.master = master;
		this.password = password.clone();  // Defensive copy
		serverSocket = new ServerSocket();
		serverSocket.bind(new InetSocketAddress("localhost", port), 4);  // Limit the number of waiting connections
	}
	
	
	/*---- Methods ----*/
	
	public void run() {
		try {
			while (true) {
				Socket sock = serverSocket.accept();
				new ProcessorReaderThread(master, sock, password).start();
				Thread.sleep(100);  // Safety delay
			}
		} catch (IOException e) {}
		catch (InterruptedException e) {}
		finally {  // Clean up
			terminate();
		}
	}
	
	
	// Can be called from any thread, and is idempotent.
	public void terminate() {
		try {
			serverSocket.close();
		} catch (IOException e) {}
	}
	
}
