/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;


public final class LineReader {
	
	/*---- Fields ----*/
	
	private final InputStream input;
	private final int maxLineLength;
	private int previousByte = -1;
	
	
	
	/*---- Constructor ----*/
	
	public LineReader(InputStream in, int maxLineLen) {
		if (maxLineLen <= 0)
			throw new IllegalArgumentException("Maximum line length must be positive");
		input = Objects.requireNonNull(in);
		maxLineLength = maxLineLen;
	}
	
	
	
	/*---- Methods ----*/
	
	public byte[] readLine() throws IOException {
		while (true) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			inner0: for (int length = 0; ; length++) {
				int b = readByteWithLineConversion();
				switch (b) {
					case -1:
						return null;
					case '\n':
						return out.toByteArray();
					default:
						if (length >= maxLineLength)
							break inner0;
						out.write(b);
						break;
				}
			}
			
			// Discard rest of over-long line
			inner1: while (true) {
				switch (readByteWithLineConversion()) {
					case -1:
						return null;
					case '\n':
						break inner1;
				}
			}
		}
	}
	
	
	// Maps any encountered {"\r", "\n", "\r\n"} to "\n".
	private int readByteWithLineConversion() throws IOException {
		while (true) {
			int b = input.read();
			if (b == -1)
				return -1;
			int prev = previousByte;
			previousByte = b;
			if (b == '\r' || b == '\n' && prev != '\r')
				return '\n';
			if (b != '\n')
				return b;
		}
	}
	
}
