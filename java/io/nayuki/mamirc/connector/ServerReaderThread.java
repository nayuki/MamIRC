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
import io.nayuki.mamirc.common.Utils;
import io.nayuki.mamirc.common.WorkerThread;


/* 
 * A worker thread that connects to an IRC server, reads lines, and relays them back to the master.
 * Additional functionality provided:
 * - Creates new socket, relays socket opened/closed events
 * - Creates and terminates a writer thread
 * - Handles SSL functionality
 */
final class ServerReaderThread extends WorkerThread {
	
	/*---- Fields ----*/
	
	// Outbound information
	private final MamircConnector master;
	private final int connectionId;
	// Connection parameters
	private final String hostname;
	private final int port;
	private final boolean useSsl;
	// My state
	private volatile Socket socket;
	
	
	
	/*---- Constructor ----*/
	
	// Note: This constructor only sets fields, and does not perform I/O.
	// The actual socket is created when the new worker thread executes run().
	public ServerReaderThread(MamircConnector master, int conId, String hostname, int port, boolean useSsl) {
		super("ServerReaderThread " + conId);
		if (master == null || hostname == null)
			throw new NullPointerException();
		Utils.checkPortNumber(port);
		
		this.master = master;
		connectionId = conId;
		this.hostname = hostname;
		this.port = port;
		this.useSsl = useSsl;
		socket = null;
	}
	
	
	
	/*---- Methods ----*/
	
	protected void runInner() throws IOException {
		socket = new Socket();
		OutputWriterThread writer = null;
		try {
			// Create socket
			socket.connect(new InetSocketAddress(hostname, port), 30000);
			if (useSsl)
				socket = SsfHolder.SSL_SOCKET_FACTORY.createSocket(socket, hostname, port, true);
			
			// Successfully connected; make a writer worker thread
			writer = new OutputWriterThread(socket.getOutputStream(), new byte[]{'\r','\n'});
			writer.setName("OutputWriterThread : " + this.getName());
			writer.start();
			master.connectionOpened(connectionId, socket.getInetAddress(), this, writer);
			
			// Read and relay lines
			LineReader reader = new LineReader(socket.getInputStream());
			while (true) {
				byte[] line = reader.readLine();
				if (line == LineReader.BLANK_EOF || line == null)
					break;
				boolean valid = true;
				for (byte b : line)
					valid &= b != '\0';
				if (valid)  // Ignore lines containing NUL character, disallowed by IRC RFC 1459
					master.receiveMessage(connectionId, new CleanLine(line, false));
			}
		}
		finally {  // Clean up the connection
			master.connectionClosed(connectionId);
			terminate();
			socket = null;
			if (writer != null)
				writer.terminate();  // This reader is exclusively responsible for terminating the writer
		}
	}
	
	
	// Aborts the current read operation (if any), closes the socket immediately, and causes the ServerReaderThread
	// and OutputWriterThread to terminate cleanly very soon. Can be called from any thread, and is idempotent.
	// However, this method must not be called if this worker has not called master.connectionOpened().
	public void terminate() {
		if (socket == null)
			throw new IllegalStateException();
		try {
			socket.close();
		} catch (IOException e) {}
	}
	
	
	
	/*---- Helper definitions ----*/
	
	// This class provides a singleton object that is initialized only if needed,
	// using the initialization-on-demand holder design pattern.
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
