package io.nayuki.mamirc.common;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;


// Receives lines from other threads and writes them to an output stream until terminated.
public final class OutputWriterThread extends Thread {
	
	/*---- Fields ----*/
	
	private final OutputStream output;
	private final byte[] newline;
	private BlockingQueue<byte[]> queue;
	
	
	/*---- Constructor ----*/
	
	// Can customize the newline sequence as "\n", "\r\n", etc.
	public OutputWriterThread(OutputStream out, byte[] newline) {
		if (out == null || newline == null)
			throw new NullPointerException();
		output = out;
		this.newline = newline.clone();
		queue = new ArrayBlockingQueue<>(1000);
	}
	
	
	/*---- Methods ----*/
	
	public void run() {
		try {
			byte[] buf = new byte[1024];  // Allocate buffer outside of loop for efficiency
			while (true) {
				byte[] line = queue.take();
				if (line == TERMINATOR)
					break;
				
				// Ugly logic for merely: output.write(line + newline)
				int totalLen = line.length + newline.length;
				if (totalLen > buf.length)
					buf = new byte[totalLen];
				System.arraycopy(line, 0, buf, 0, line.length);
				System.arraycopy(newline, 0, buf, line.length, newline.length);
				output.write(buf, 0, totalLen);
			}
			
		// Clean up
		} catch (IOException e) {}
		catch (InterruptedException e) {}
		finally {
			queue = null;  // Not thread-safe, but is a best-effort attempt to detect invalid usage
			try {
				output.close();
			} catch (IOException e) {}
		}
	}
	
	
	// Can be called safely from any thread. Must not be called after terminate().
	// Caller must never change the values inside the array after it is passed into this method.
	public void postWrite(byte[] line) {
		if (line == null)
			throw new NullPointerException();
		try {
			queue.put(line);
		} catch (InterruptedException e) {}
	}
	
	
	// Can be called safely from any thread. Must not be called after terminate().
	public void postWrite(String line) {
		if (line == null)
			throw new NullPointerException();
		postWrite(line.getBytes(UTF8_CHARSET));
	}
	
	
	// Can be called safely from any thread.
	public void terminate() {
		try {
			queue.put(TERMINATOR);
		} catch (InterruptedException e) {}
	}
	
	
	/*---- Helper definitions ----*/
	
	private static final byte[] TERMINATOR = new byte[0];
	
	public static final Charset UTF8_CHARSET = Charset.forName("UTF-8");
	
}
