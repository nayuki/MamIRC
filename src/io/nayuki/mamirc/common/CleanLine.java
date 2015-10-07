package io.nayuki.mamirc.common;


// Represents an immutable sequence of bytes not containing '\0', '\r', or '\n'.
public final class CleanLine {
	
	private final byte[] data;
	
	
	
	public CleanLine(byte[] arr) {
		if (arr == null)
			throw new IllegalArgumentException();
		data = arr.clone();
		for (byte b : data) {
			if (b == '\0' || b == '\r' || b == '\n')
				throw new IllegalArgumentException("Invalid characters in line");
		}
	}
	
	
	// Uses UTF-8 as the default character encoding.
	public CleanLine(String str) {
		this(Utils.toUtf8(str));
	}
	
	
	
	public byte[] getData() {
		return data.clone();
	}
	
}
