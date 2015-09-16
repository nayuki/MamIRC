package io.nayuki.mamirc;

import java.io.IOException;
import java.net.Socket;


final class ServerReaderThread extends Thread {
	
	/*---- Fields ----*/
	
	private final MamircConnector master;
	private final int connectionId;
	private final String hostname;
	private final int port;
	private Socket socket;
	
	
	/*---- Constructor ----*/
	
	public ServerReaderThread(MamircConnector master, int conId, String hostname, int port) {
		if (master == null || hostname == null)
			throw new NullPointerException();
		if ((port & 0xFFFF) != port)
			throw new IllegalArgumentException();
		
		this.master = master;
		connectionId = conId;
		this.hostname = hostname;
		this.port = port;
	}
	
	
	/*---- Methods ----*/
	
	public void run() {
		OutputWriterThread writer = null;
		try {
			socket = new Socket(hostname, port);
			writer = new OutputWriterThread(socket.getOutputStream(), new byte[]{'\r','\n'});
			writer.start();
			master.connectionOpened(connectionId, socket, writer);
			
			LineReader reader = new LineReader(socket.getInputStream());
			while (true) {
				byte[] line = reader.readLine();
				if (line == null)
					break;
				master.receiveMessage(connectionId, line);
			}
			
		// Clean up
		} catch (IOException e) {}
		finally {
			if (socket != null) {
				try {
					socket.close();
				} catch (IOException e) {}
			}
			master.connectionClosed(connectionId);
		}
	}
	
}
