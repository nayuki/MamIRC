/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.connector;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;


final class ProcessorListenWorker extends Thread {
	
	/*---- Fields ----*/
	
	private final MamircConnector master;
	private final ServerSocket serverSocket;
	private final byte[] password;
	
	
	
	/*---- Constructor ----*/
	
	// The server socket is created on the caller's thread, to make the caller deal with an I/O exception immediately.
	public ProcessorListenWorker(MamircConnector master, int port, byte[] password) throws IOException {
		super("Processor Listener");
		this.master   = Objects.requireNonNull(master);
		this.password = Objects.requireNonNull(password);
		if (port < 0 || port > 0xFFFF)
			throw new IllegalArgumentException("Invalid TCP port number: " + port);
		
		int backlog = 4;  // Limit the number of waiting connections
		serverSocket = new ServerSocket();
		serverSocket.bind(new InetSocketAddress("localhost", port), backlog);
		start();
	}
	
	
	
	/*---- Methods ----*/
	
	public void run() {
		try {
			while (true) {
				Socket sock = serverSocket.accept();
				new ProcessorReadWorker(master, sock, password);
				Thread.sleep(100);  // Safety delay
			}
		} catch (IOException|InterruptedException e) {
		} finally {
			try {
				serverSocket.close();
			} catch (IOException e) {}
		}
	}
	
	
	public void terminate() {
		try {
			serverSocket.close();
			interrupt();
		} catch (IOException e) {}
	}
	
}
