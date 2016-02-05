/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.common;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;


/* 
 * A worker thread that receives line objects from other threads, and writes bytes to an output stream until terminated.
 * This class exists because write operations might block with large and varying delay (especially in low-bandwidth or
 * high-loss environments), but the thread that requested the write operation wants to continue processing more data.
 */
public final class OutputWriterThread extends Thread {
	
	/*---- Fields ----*/
	
	private final OutputStream output;
	private final byte[] newline;
	private BlockingQueue<CleanLine> queue;
	
	
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
				CleanLine line = queue.take();
				if (line == TERMINATOR)
					break;
				
				// Ugly logic for merely: output.write(line + newline)
				byte[] b = line.getDataNoCopy();
				int totalLen = b.length + newline.length;
				if (totalLen > buf.length)
					buf = new byte[totalLen];
				System.arraycopy(b, 0, buf, 0, b.length);
				System.arraycopy(newline, 0, buf, b.length, newline.length);
				output.write(buf, 0, totalLen);
			}
		} catch (IOException e) {}
		catch (InterruptedException e) {}
		finally {  // Clean up
			try {
				output.close();
			} catch (IOException e) {}
		}
	}
	
	
	// Can be called from any thread. Must not be called after terminate().
	// Caller must never change the values inside the array after it is passed into this method.
	public void postWrite(CleanLine line) {
		if (line == null)
			throw new NullPointerException();
		try {
			queue.put(line);
		} catch (InterruptedException e) {}
	}
	
	
	// 'line' must not contain '\0', '\r', or '\n'. It is converted to bytes in UTF-8.
	// Can be called safely from any thread. Must not be called after terminate().
	public void postWrite(String line) {
		if (line == null)
			throw new NullPointerException();
		postWrite(new CleanLine(line));
	}
	
	
	// Can be called safely from any thread.
	public void terminate() {
		try {
			queue.put(TERMINATOR);
		} catch (InterruptedException e) {}
	}
	
	
	/*---- Helper definitions ----*/
	
	private static final CleanLine TERMINATOR = new CleanLine("");
	
}
