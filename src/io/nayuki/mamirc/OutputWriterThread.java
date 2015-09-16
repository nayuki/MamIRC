package io.nayuki.mamirc;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;


final class OutputWriterThread extends Thread {
	
	/*---- Fields ----*/
	
	private final OutputStream output;
	private final BlockingQueue<byte[]> queue;
	private final byte[] newline;
	
	
	/*---- Constructor ----*/
	
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
			try {
				output.close();
			} catch (IOException e) {}
		}
	}
	
	
	// Can be called safely from any thread.
	public void postWrite(byte[] line) {
		if (line == null)
			throw new NullPointerException();
		queue.add(line);
	}
	
	
	// Can be called safely from any thread.
	public void postWrite(String line) {
		postWrite(line.getBytes(UTF8_CHARSET));
	}
	
	
	// Can be called safely from any thread.
	public void terminate() {
		queue.add(TERMINATOR);
	}
	
	
	/*---- Helper definitions ----*/
	
	private static final byte[] TERMINATOR = new byte[0];
	
	static final Charset UTF8_CHARSET = Charset.forName("UTF-8");
	
}
