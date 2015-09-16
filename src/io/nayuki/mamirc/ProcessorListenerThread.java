package io.nayuki.mamirc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;


final class ProcessorListenerThread extends Thread {
	
	/*---- Fields ----*/
	
	private final MamircConnector master;
	public final ServerSocket serverSocket;
	
	
	/*---- Constructor ----*/
	
	public ProcessorListenerThread(MamircConnector master, int port) throws IOException {
		if (master == null)
			throw new NullPointerException();
		if ((port & 0xFFFF) != port)
			throw new IllegalArgumentException();
		
		this.master = master;
		serverSocket = new ServerSocket();
		serverSocket.bind(new InetSocketAddress("localhost", port), 2);
	}
	
	
	/*---- Methods ----*/
	
	public void run() {
		try {
			while (true) {
				Socket sock = serverSocket.accept();
				new ProcessorReaderThread(master, sock).start();
				Thread.sleep(1000);  // Safety delay
			}
			
		// Clean up
		} catch (IOException e) {}
		catch (InterruptedException e) {}
		finally {
			try {
				serverSocket.close();
			} catch (IOException e) {}
		}
	}
	
}
