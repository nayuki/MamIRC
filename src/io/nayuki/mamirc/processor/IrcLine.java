package io.nayuki.mamirc.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


// Immutable data structure.
final class IrcLine {
	
	/*---- Fields ----*/
	
	public final String prefix;            // Can be null
	public final String command;           // Not null
	public final List<String> parameters;  // Not null, and has length 0 or more
	
	
	/*---- Methods ----*/
	
	public IrcLine(String line) {
		// Parse prefix
		if (line.startsWith(":")) {
			int i = line.indexOf(' ');
			if (i == -1)
				throw new IllegalArgumentException("Missing command");
			prefix = line.substring(1, i);
			line = line.substring(i + 1);
		} else
			prefix = null;
		
		// Parse command
		if (line.startsWith(" "))
			throw new IllegalArgumentException("Extra spaces");
		List<String> params = new ArrayList<>();
		int i = line.indexOf(' ');
		if (i == -1) {
			command = line;
			if (command.length() == 0)
				throw new IllegalArgumentException("Empty command");
		} else {
			command = line.substring(0, i);
			if (command.length() == 0)
				throw new IllegalArgumentException("Empty command");
			line = line.substring(i + 1);
			
			// Parse parameters
			while (true) {
				if (line.startsWith(" "))
					throw new IllegalArgumentException("Invalid parameter");
				else if (line.startsWith(":")) {
					params.add(line.substring(1));
					break;
				} else {
					i = line.indexOf(' ');
					if (i == -1) {
						params.add(line);
						break;
					} else {
						params.add(line.substring(0, i));
						line = line.substring(i + 1);
					}
				}
			}
		}
		parameters = Collections.unmodifiableList(params);
	}
	
	
	
	/*---- Nested classes ----*/
	
	public static final class Prefix {
		
		public final String name;  // Not null. This is a user nickname or server name.
		public final String username;  // Can be null
		public final String hostname;  // Can be null, unless username is null
		
		
		public Prefix(String s) {
			int i = s.indexOf('@');
			if (i == -1) {
				name = s;
				username = null;
				hostname = null;
			} else {
				int j = s.lastIndexOf('!', i);
				if (j == -1) {
					name = s.substring(0, i);
					username = null;
				} else {
					name = s.substring(0, j);
					username = s.substring(j + 1, i);
				}
				hostname = s.substring(i + 1);
			}
		}
		
	}
	
}
