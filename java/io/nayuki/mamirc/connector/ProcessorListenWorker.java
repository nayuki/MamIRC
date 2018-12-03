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
import io.nayuki.mamirc.common.WorkerThread;


final class ProcessorListenWorker extends WorkerThread {
	
	/*---- Fields ----*/
	
	private final MamircConnector master;
	private final ServerSocket serverSocket;
	private final byte[] password;
	
	
	
	/*---- Constructor ----*/
	
	// This creates the server socket synchronously. If an exception is thrown (e.g. port in use),
	// then the caller can abort the initialization more easily (with fewer worker threads running).
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
	
	protected void runInner() throws IOException, InterruptedException {
		try (ServerSocket servSock = serverSocket) {
			while (true) {
				Socket sock = servSock.accept();
				new ProcessorReadWorker(master, sock, password);
				Thread.sleep(100);  // Safety delay
			}
		}
	}
	
	
	// Asynchronously closes the server socket; then requests this worker thread to stop
	// listening for incoming connections, stop waiting, and stop execution. This does not
	// close any sockets accepted from the server socket. Can be called from any thread.
	public void shutdown() {
		try {
			serverSocket.close();
			interrupt();
		} catch (IOException e) {}
	}
	
}
