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
import io.nayuki.mamirc.common.LineReader;
import io.nayuki.mamirc.common.OutputWriterThread;


final class ServerReaderThread extends Thread {
	
	/*---- Fields ----*/
	
	private final MamircConnector master;
	private final int connectionId;
	private final String hostname;
	private final int port;
	private final boolean useSsl;
	private Socket socket;
	
	
	/*---- Constructor ----*/
	
	public ServerReaderThread(MamircConnector master, int conId, String hostname, int port, boolean useSsl) {
		if (master == null || hostname == null)
			throw new NullPointerException();
		if ((port & 0xFFFF) != port)
			throw new IllegalArgumentException();
		
		this.master = master;
		connectionId = conId;
		this.hostname = hostname;
		this.port = port;
		this.useSsl = useSsl;
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
			writer.start();
			master.connectionOpened(connectionId, socket, writer);
			
			// Read and forward lines
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
				if (writer != null)
					writer.terminate();  // This reader is exclusively responsible for terminating the writer
			}
			master.connectionClosed(connectionId);
		}
	}
	
	
	// Initialization-on-demand holder idiom
	private static final class SsfHolder {
		public static final SSLSocketFactory SSL_SOCKET_FACTORY;
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
