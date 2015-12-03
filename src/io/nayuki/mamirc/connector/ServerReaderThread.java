package io.nayuki.mamirc.connector;

import java.io.IOException;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import io.nayuki.mamirc.common.CleanLine;
import io.nayuki.mamirc.common.LineReader;
import io.nayuki.mamirc.common.OutputWriterThread;


// Reads each line from a socket and relays it back to the master.
// Also handles the connection initialization and clean-up.
final class ServerReaderThread extends Thread {
	
	/*---- Fields ----*/
	
	private final MamircConnector master;
	private final int connectionId;
	private final String hostname;
	private final int port;
	private final boolean useSsl;
	private Socket socket;
	
	
	/*---- Constructor ----*/
	
	// Note: The socket is created on the new thread, not on the caller's thread.
	public ServerReaderThread(MamircConnector master, int conId, String hostname, int port, boolean useSsl) {
		super("ServerReaderThread " + conId);
		if (master == null || hostname == null)
			throw new NullPointerException();
		if ((port & 0xFFFF) != port)
			throw new IllegalArgumentException("Invalid TCP port number");
		
		this.master = master;
		connectionId = conId;
		this.hostname = hostname;
		this.port = port;
		this.useSsl = useSsl;
		socket = null;
	}
	
	
	/*---- Methods ----*/
	
	public void run() {
		OutputWriterThread writer = null;
		try {
			// Create socket
			if (useSsl)
				socket = SsfHolder.SSL_SOCKET_FACTORY.createSocket(hostname, port);
			else
				socket = new Socket(hostname, port);
			
			// Initialize stuff
			writer = new OutputWriterThread(socket.getOutputStream(), new byte[]{'\r','\n'});
			writer.setName("OutputWriterThread : " + getName());
			writer.start();
			master.connectionOpened(connectionId, socket.getInetAddress(), this, writer);
			
			// Read and forward lines
			LineReader reader = new LineReader(socket.getInputStream());
			while (true) {
				byte[] line = reader.readLine();
				if (line == LineReader.BLANK_EOF || line == null)
					break;
				boolean valid = true;
				for (byte b : line)
					valid &= b != '\0';
				if (valid)
					master.receiveMessage(connectionId, new CleanLine(line, false));
			}
			
		// Clean up
		} catch (IOException e) {}
		finally {
			master.connectionClosed(connectionId);
			terminate();
			if (writer != null)
				writer.terminate();  // This reader is exclusively responsible for terminating the writer
		}
	}
	
	
	// Can be called from any thread.
	public void terminate() {
		try {
			if (socket != null)
				socket.close();
		} catch (IOException e) {}
	}
	
	
	// Initialization-on-demand holder idiom
	private static final class SsfHolder {
		public static final SSLSocketFactory SSL_SOCKET_FACTORY;  // Singleton
		static {
			SSLSocketFactory result = null;
			try {
				// From "How to bypass SSL security check": https://code.google.com/p/misc-utils/wiki/JavaHttpsUrl
				TrustManager[] trustAllCerts = {
					new X509TrustManager() {  // A trust manager that does not validate certificate chains
						public void checkClientTrusted(X509Certificate[] chain, String authType) {}
						public void checkServerTrusted(X509Certificate[] chain, String authType) {}
						public X509Certificate[] getAcceptedIssuers() { return null; }
					}
				};
				SSLContext sslContext = SSLContext.getInstance("SSL");
				sslContext.init(null, trustAllCerts, new SecureRandom());  // Install the all-trusting trust manager
				result = sslContext.getSocketFactory();  // Create factory with our all-trusting manager
			} catch (KeyManagementException e) {
				e.printStackTrace();
			} catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
			}
			SSL_SOCKET_FACTORY = result;
		}
	}
	
}
