/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.common;


/* 
 * Represents an array of zero or more bytes, none of which are '\0' (NUL), '\r' (CR), or '\n' (LF).
 * This is intended to represent a raw line of text before a character encoding interpretation is
 * applied to it (such as UTF-8, ISO-8859-1, Shift JIS, etc.). (Objects are intended to be immutable, but
 * the property cannot be enforced due to the no-copy constructor and accessor provided for performance.)
 */
public final class CleanLine {
	
	/*---- Fields ----*/
	
	private final byte[] data;
	
	
	/*---- Constructors ----*/
	
	// Constructs a clean line object based on a defensive copy of the given byte array.
	// If in doubt, this version of the constructor is always safe to use.
	public CleanLine(byte[] arr) {
		this(arr, true);
	}
	
	
	// Constructs a clean line object based on the given byte array, making a defensive copy iff indicated.
	// This version is provided to avoid an allocation and copy if the byte array is known to be private.
	public CleanLine(byte[] arr, boolean copy) {
		if (arr == null)
			throw new NullPointerException();
		data = copy ? arr.clone() : arr;
		for (byte b : data) {
			if (b == '\0' || b == '\r' || b == '\n')
				throw new IllegalArgumentException("Invalid characters in line");
		}
	}
	
	
	// Constructs a clean line object based on the given string encoded into UTF-8.
	public CleanLine(String str) {
		this(Utils.toUtf8(str), false);
	}
	
	
	/*---- Methods ----*/
	
	// Returns a new copy of the underlying byte array.
	public byte[] getData() {
		return data.clone();
	}
	
	
	// Returns the underlying byte array directly, without copying.
	public byte[] getDataNoCopy() {
		return data;
	}
	
	
	// Returns the data interpreted as a UTF-8 string.
	public String getString() {
		return Utils.fromUtf8(data);
	}
	
}
