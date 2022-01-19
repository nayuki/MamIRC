package io.nayuki.mamirc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;


final class IrcServerConnection {
	
	private final IrcNetworkProfile profile;
	
	private final Core consumer;
	
	private Socket socket = null;
	private boolean closeRequested = false;
	
	
	public IrcServerConnection(IrcNetworkProfile profile, Core consumer) {
		this.profile = profile;
		this.consumer = consumer;
		new Thread(this::readWorker).start();
	}
	
	
	private void postEvent(ConnectionEvent ev) {
		consumer.postEvent(this, ev);
	}
	
	
	public void close() throws IOException {
		synchronized(this) {
			if (socket == null)
				closeRequested = true;
			else {
				postEvent(new ConnectionEvent.Closing());
				socket.close();
			}
		}
		writeQueue.add(Optional.empty());
		writeQueueLength.release();
	}
	
	
	
	/*---- Reader members ----*/
	
	private void readWorker() {
		IrcNetworkProfile.Server serv = profile.servers.get(0);
		postEvent(new ConnectionEvent.Opening(serv.hostname, serv.port, profile.characterEncoding));
		try (Socket sock = new Socket(serv.hostname, serv.port)) {
			postEvent(new ConnectionEvent.Opened(sock.getInetAddress()));
			synchronized(this) {
				socket = sock;
				if (closeRequested) {
					postEvent(new ConnectionEvent.Closing());
					socket.close();
					return;
				}
			}
			
			new Thread(this::writeWorker).start();
			InputStream in = sock.getInputStream();
			byte[] readBuf = new byte[READ_BUFFER_SIZE];
			byte prevByte = 0;
			byte[] lineBuf = new byte[Math.addExact(MAX_LINE_SIZE, 1)];
			int lineLen = 0;
			
			while (true) {
				int readLen = in.read(readBuf);
				if (readLen == -1)
					break;
				
				for (int i = 0; i < readLen; i++) {
					byte b = readBuf[i];
					if (b == '\n' && prevByte == '\r');  // Ignore
					else if (b == '\r' || b == '\n') {
						if (lineLen <= MAX_LINE_SIZE)
							postEvent(new ConnectionEvent.LineReceived(Arrays.copyOf(lineBuf, lineLen)));
						lineLen = 0;
					} else if (lineLen < lineBuf.length) {
						lineBuf[lineLen] = b;
						lineLen++;
					}
					prevByte = b;
				}
			}
			
		} catch (IOException e) {
			postEvent(new ConnectionEvent.ReadException(e.getMessage()));
		} finally {
			postEvent(new ConnectionEvent.Closed());
		}
	}
	
	
	private static final int READ_BUFFER_SIZE = 4096;  // Can be any positive number
	private static final int MAX_LINE_SIZE = 1000;  // In bytes, excluding newlines
	
	
	
	/*---- Writer members ----*/
	
	private Queue<Optional<byte[]>> writeQueue = new ConcurrentLinkedQueue<>();
	private Semaphore writeQueueLength = new Semaphore(0);
	
	
	public void postWriteLine(byte[] line) {
		writeQueue.add(Optional.of(line));
		writeQueueLength.release();
	}
	
	
	private void writeWorker() {
		try {
			socket.setTcpNoDelay(false);
			OutputStream out = socket.getOutputStream();
			byte[] lineBuf = new byte[Math.addExact(MAX_LINE_SIZE, 2)];
			
			while (true) {
				writeQueueLength.acquire();
				Optional<byte[]> item = writeQueue.remove();
				if (item.isEmpty())
					break;
				
				byte[] line = item.get();
				int lineLen = line.length;
				if (lineLen <= lineBuf.length - 2) {
					System.arraycopy(line, 0, lineBuf, 0, lineLen);
					lineBuf[lineLen++] = '\r';
					lineBuf[lineLen++] = '\n';
					out.write(lineBuf, 0, lineLen);
					postEvent(new ConnectionEvent.LineSent(Arrays.copyOf(lineBuf, lineLen - 2)));
				}
			}
			
		} catch (IOException e) {
			postEvent(new ConnectionEvent.WriteException(e.getMessage()));
		} catch (InterruptedException e) {}
	}
	
}
