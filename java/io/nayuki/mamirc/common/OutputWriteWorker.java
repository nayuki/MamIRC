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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;


public final class OutputWriteWorker extends WorkerThread {
	
	/*---- Fields ----*/
	
	private final OutputStream output;
	private final byte[] newline;
	private final BlockingQueue<byte[]> queue;
	
	
	
	/*---- Constructor ----*/
	
	// Can customize the newline sequence as "\n", "\r\n", etc.
	public OutputWriteWorker(String threadName, OutputStream out, byte[] newline) {
		super(threadName);
		this.output  = Objects.requireNonNull(out);
		this.newline = Objects.requireNonNull(newline);
		queue = new ArrayBlockingQueue<>(1000);
		start();
	}
	
	
	
	/*---- Methods ----*/
	
	protected void runInner() throws IOException, InterruptedException {
		try (OutputStream out = output) {
			while (true) {
				byte[] line = queue.take();
				if (line == TERMINATOR)
					break;
				byte[] temp = Arrays.copyOf(line, line.length + newline.length);
				System.arraycopy(newline, 0, temp, line.length, newline.length);
				out.write(temp);
			}
		}
	}
	
	
	// Can be called from any thread. Must not be called after shutdown().
	// Caller must not change the values inside the array after it is passed into this method.
	public void writeLine(byte[] line) {
		Objects.requireNonNull(line);
		try {
			queue.put(line);
		} catch (InterruptedException e) {}
	}
	
	
	// 'line' must not contain '\0', '\r', or '\n'. It is converted to bytes in UTF-8.
	// Can be called safely from any thread. Must not be called after shutdown().
	public void writeLine(String line) {
		Objects.requireNonNull(line);
		writeLine(line.getBytes(StandardCharsets.UTF_8));
	}
	
	
	// Asynchronously requests this worker thread to write all previous queued lines,
	// and stop execution. This does not close the output stream; the reader which
	// started this writer is responsible for closing it. Can be called from any thread.
	public void shutdown() {
		try {
			queue.put(TERMINATOR);
		} catch (InterruptedException e) {}
	}
	
	
	
	/*---- Constant ----*/
	
	// A sentinel object that is only compared by reference. Its dereferenced values are never used.
	private static final byte[] TERMINATOR = {};
	
}
