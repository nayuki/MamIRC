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
import java.util.Objects;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import io.nayuki.mamirc.common.LineReader;
import io.nayuki.mamirc.common.OutputWriteWorker;


/* 
 * A worker thread that connects to an IRC server, reads lines, and relays them back to the master.
 * Additional functionality provided:
 * - Creates new socket, relays socket opened/closed events
 * - Creates and shuts down a writer thread
 * - Handles SSL functionality
 */
final class IrcServerReadWorker extends Thread {
	
	/*---- Fields ----*/
	
	// Outbound information
	private final MamircConnector master;
	private final int connectionId;
	// Connection parameters
	private final String hostname;
	private final int port;
	private final boolean useSsl;
	// My state
	private Socket socket = new Socket();
	
	
	
	/*---- Constructor ----*/
	
	// Note: This constructor only sets fields, and does not perform I/O.
	// The actual socket is created when the new worker thread executes run().
	public IrcServerReadWorker(MamircConnector master, int conId, String hostname, int port, boolean useSsl) {
		super("IRC Server Reader (conId=" + conId + ")");
		this.master = Objects.requireNonNull(master);
		this.connectionId = conId;
		this.hostname = Objects.requireNonNull(hostname);
		if (port < 0 || port > 0xFFFF)
			throw new IllegalArgumentException("Invalid TCP port number: " + port);
		this.port = port;
		this.useSsl = useSsl;
		start();
	}
	
	
	
	/*---- Methods ----*/
	
	public void run() {
		try {
			// Create socket connection
			socket.connect(new InetSocketAddress(hostname, port), CONNECTION_TIMEOUT);
			if (useSsl)
				socket = SsfHolder.SSL_SOCKET_FACTORY.createSocket(socket, hostname, port, true);
			
			// Successfully connected; make a writer worker thread
			OutputWriteWorker writer = new OutputWriteWorker("IRC Server Writer (conId=" + connectionId + ")",
				socket.getOutputStream(), new byte[]{'\r','\n'});  // IRC protocol mandates the use of CR+LF
			try {
				master.connectionOpened(connectionId, socket.getInetAddress(), writer);
				
				// Repeatedly read and relay lines until connection ends
				LineReader reader = new LineReader(socket.getInputStream(), 1000);
				while (true) {
					byte[] line = reader.readLine();
					if (line == null)
						break;
					boolean valid = true;
					for (byte b : line)
						valid &= b != '\0';
					if (valid)  // Ignore lines containing NUL character, disallowed by IRC RFC 1459
						master.messageReceived(connectionId, line);
				}
				
			} finally {
				writer.shutdown();
			}
		} catch (IOException e) {
		} finally {
			master.connectionClosed(connectionId);
			shutdown();
		}
	}
	
	
	// Aborts the current read operation (if any), closes the socket immediately, and causes the IrcServerReadWorker
	// and OutputWriteWorker to shut down cleanly very soon. Can be called from any thread, and is idempotent.
	public void shutdown() {
		try {
			socket.close();
		} catch (IOException e) {}
	}
	
	
	private static final int CONNECTION_TIMEOUT = 30000;  // In milliseconds
	
	
	
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
