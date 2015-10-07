package io.nayuki.mamirc.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;


// Returns a byte array for each line parsed from an input stream.
// Not thread-safe. Parent is responsible for closing the stream.
public final class LineReader {
	
	/*---- Fields ----*/
	
	private final InputStream input;
	
	// Current accumulating line
	private byte[] lineBuffer;
	private int lineLength;
	
	private byte[] readBuffer;
	private int readLength;
	private int readOffset;
	private boolean prevWasCr;
	
	
	/*---- Constructor ----*/
	
	public LineReader(InputStream in) {
		if (in == null)
			throw new NullPointerException();
		input = in;
		lineBuffer = new byte[1024];
		lineLength = 0;
		readBuffer = new byte[4096];
		readLength = 0;
		readOffset = 0;
		prevWasCr = false;
	}
	
	
	/*---- Methods ----*/
	
	// Has universal newline detection. Each returned array is a unique instance,
	// and contains no '\r' or '\n' characters. Lines have 0 or more characters.
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
					byte[] result = takeCurrentLine();
					readBuffer = null;
					lineBuffer = null;
					if (result.length == 0)
						return BLANK_EOF;
					else
						return result;
				}
			}
			
			// Consume characters in the read buffer
			while (readOffset < readLength) {
				byte b = readBuffer[readOffset];
				readOffset++;
				switch (b) {
					case '\r':
					{
						prevWasCr = true;
						return takeCurrentLine();
					}
					
					case '\n':
						if (prevWasCr) {
							prevWasCr = false;
							break;
						} else
							return takeCurrentLine();
						
					default:
						if (lineLength == lineBuffer.length)
							lineBuffer = Arrays.copyOf(lineBuffer, lineBuffer.length * 2);
						lineBuffer[lineLength] = b;
						lineLength++;
						prevWasCr = false;
						break;
				}
			}
		}
	}
	
	
	private byte[] takeCurrentLine() {
		byte[] result = Arrays.copyOf(lineBuffer, lineLength);
		lineLength = 0;
		return result;
	}
	
	
	// Same content as just byte[0], but usefully indicates that the next call will return null
	public static final byte[] BLANK_EOF = new byte[0];
	
}
