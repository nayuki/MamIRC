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
import java.util.logging.Level;
import io.nayuki.mamirc.common.Utils;
import io.nayuki.mamirc.common.WorkerThread;


/* 
 * Manages a local server socket to listen for incoming Processor connections, and launches a new
 * ProcessorReaderThread on each connection received. If the socket breaks, the whole Connector process is terminated.
 * This class implements rate-limiting to prevent denial-of-service attacks (but because the socket only
 * listens to localhost, the attacker would be another process running by some user on this machine).
 */
final class ProcessorListenerThread extends WorkerThread {
	
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
	
	protected void runInner() {
		try {
			while (true) {
				Socket sock = serverSocket.accept();
				new ProcessorReaderThread(master, sock, password).start();
				Thread.sleep(100);  // Safety delay
			}
		} catch (Throwable e) {
			// The only way to exit the loop is to throw an exception, and this section will always catch the exception.
			// Hence the flow control is equivalent to 'finally', but lets us also retrieve the exception for information.
			Utils.logger.log(Level.WARNING, "ProcessorListenerThread unhandled exception", e);
			master.terminateConnector("ProcessorListenerThread fault");
		}
	}
	
}
