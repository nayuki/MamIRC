package io.nayuki.mamirc.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


// Represents a line received from IRC server or sent from IRC client. Immutable data structure.
// A raw IRC line can look like these examples (with no leading spaces):
//   :server.example.com 001 UserNickname :Welcome to IRC!
//   JOIN #channel
//   :Oldname NICK Newname
//   :nickname!username@hostname PRIVMSG #channel :Hello!
//   :optionalPrefix COMMAND noSpaceParam anotherparam :Trailing parameter with spaces allowed
final class IrcLine {
	
	/*---- Fields ----*/
	
	public final String prefix;   // Can be null
	public final String command;  // Not null
	public final List<String> parameters;  // Not null; immutable, length at least 0, elements not null.
	
	
	/*---- Constructor ----*/
	
	// Parses the given raw string line into parts. Throws an exception on syntax error.
	public IrcLine(String line) {
		// Parse prefix and command
		Matcher m = PREFIX_COMMAND_REGEX.matcher(line);
		if (!m.matches())
			throw new IllegalArgumentException("Syntax error in prefix or command");
		if (m.start(1) != -1)
			prefix = line.substring(m.start(1) + 1, m.end(1) - 1);
		else
			prefix = null;
		command = m.group(2);
		
		// Parse any number of parameters
		String rest = m.group(3);
		List<String> params = new ArrayList<>();
		while (rest.length() > 0) {
			if (rest.charAt(0) != ' ')
				throw new AssertionError();
			else if (rest.startsWith(" :")) {
				params.add(rest.substring(2));
				rest = "";
			} else {
				int i = rest.indexOf(' ', 1);
				if (i == -1)
					i = rest.length();
				if (i == 1)
					throw new IllegalArgumentException("Multiple spaces between parameters");
				params.add(rest.substring(1, i));
				rest = rest.substring(i);
			}
		}
		parameters = Collections.unmodifiableList(params);
	}
	
	
	/*---- Constants ----*/
	
	private static final Pattern PREFIX_COMMAND_REGEX = Pattern.compile("(:[^ ]+ )?([^ ]+)(.*)");
	
	
	
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
