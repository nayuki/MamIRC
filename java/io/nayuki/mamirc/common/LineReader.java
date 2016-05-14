/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;


/* 
 * Returns a byte array for each line parsed from an input stream.
 */
public final class LineReader {
	
	/*---- Fields ----*/
	
	private final InputStream input;
	// Read buffering
	private byte[] readBuffer;
	private int readLength;
	private int readOffset;
	private boolean prevWasCr;
	// Current accumulating line
	private byte[] lineBuffer;
	private int lineLength;
	private final int maxLineLength;
	
	
	/*---- Constructor ----*/
	
	// Constructs a line reader over the given input stream with the default maximum line length of 1000 bytes.
	// The caller is responsible for closing the input stream after it is no longer needed.
	public LineReader(InputStream in) {
		this(in, 1000);
	}
	
	
	// Constructs a line reader over the given input stream with the given maximum line length in bytes.
	// The caller is responsible for closing the input stream after it is no longer needed.
	public LineReader(InputStream in, int maxLen) {
		if (in == null)
			throw new NullPointerException();
		if (maxLen <= 0)
			throw new IllegalArgumentException("Maximum line length must be positive");
		input = in;
		readBuffer = new byte[4096];
		readLength = 0;
		readOffset = 0;
		prevWasCr = false;
		lineBuffer = new byte[Math.min(1024, maxLen)];
		lineLength = 0;
		maxLineLength = maxLen;
	}
	
	
	/*---- Methods ----*/
	
	// Returns the next line of bytes read from the input stream. Has universal newline detection. Not thread-safe.
	// Each returned array is a unique object, contains no '\r' or '\n' characters (but may contain '\0'),
	// and has length in the range [0, maxLen]. Lines in the input that exceed maxLen are skipped.
	// Newlines are treated as separators rather than terminators, which means at least 1 line
	// is always returned for any stream, and the stream need not end with a newline sequence.
	public byte[] readLine() throws IOException {
		if (readBuffer == null)  // End of stream already reached previously
			return null;
		
		// Loop until we find a line or reach the end of stream
		while (true) {
			// Gather more input data if needed
			while (readOffset >= readLength) {  // Use loop in case read() returns 0
				readLength = input.read(readBuffer);
				readOffset = 0;
				if (readLength == -1) {  // End of stream reached just now
					byte[] result;
					if (lineLength == -1)
						result = null;
					else if (lineLength == 0)
						result = BLANK_EOF;
					else
						result = takeCurrentLine();
					readBuffer = null;
					lineBuffer = null;
					return result;
				}
			}
			
			// Consume characters in the read buffer
			while (readOffset < readLength) {
				byte b = readBuffer[readOffset];
				readOffset++;
				
				if (lineLength != -1) {
					switch (b) {
						case '\r':
							prevWasCr = true;
							return takeCurrentLine();
						
						case '\n':
							if (prevWasCr) {
								prevWasCr = false;
								break;
							} else
								return takeCurrentLine();
							
						default:
							prevWasCr = false;
							if (lineLength == maxLineLength) {
								lineLength = -1;  // Poison the current line
								continue;
							} else {
								if (lineLength == lineBuffer.length)
									lineBuffer = Arrays.copyOf(lineBuffer, Math.min(lineBuffer.length * 2, maxLineLength));
								lineBuffer[lineLength] = b;
								lineLength++;
								break;
							}
					}
					
				} else {
					switch (b) {
						case '\r':
							lineLength = 0;
							prevWasCr = true;
							break;
						case '\n':
							lineLength = 0;
							prevWasCr = false;
							break;
						default:
							// Keep lineLength == -1
							prevWasCr = false;
							break;
					}
				}
			}
		}
	}
	
	
	private byte[] takeCurrentLine() {
		byte[] result = Arrays.copyOf(lineBuffer, lineLength);
		lineLength = 0;
		return result;
	}
	
	
	// Same content as just byte[0], but usefully indicates that the next call will return null.
	public static final byte[] BLANK_EOF = {};
	
}
