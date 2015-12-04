package io.nayuki.mamirc.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/*
 * Represents a line received from IRC server or sent from IRC client. Immutable data structure.
 * A raw IRC line can look like these examples (with no leading spaces):
 *   :server.example.com 001 UserNickname :Welcome to IRC!
 *   JOIN #channel
 *   :Oldname NICK Newname
 *   :nickname!username@hostname PRIVMSG #channel :Hello!
 *   :optionalPrefix COMMAND noSpaceParam anotherparam :Trailing parameter with spaces allowed
 */
final class IrcLine {
	
	/*---- Fields ----*/
	
	public final String prefixName;        // Can be null
	public final String prefixHostname;    // Can be null, but would require prefixName to be null
	public final String prefixUsername;    // Can be null, but would require prefixHostname to be null
	public final String command;           // Not null
	public final List<String> parameters;  // Not null; immutable, length at least 0, elements not null.
	
	
	/*---- Constructor ----*/
	
	// Parses the given raw string line into parts. Throws an exception on syntax error.
	public IrcLine(String line) {
		// Parse prefix and command
		Matcher m = PREFIX_COMMAND_REGEX.matcher(line);
		if (!m.matches())
			throw new IrcSyntaxException("Syntax error in prefix or command");
		if (m.start(1) != -1) {
			String prefix = line.substring(m.start(1) + 1, m.end(1) - 1);
			int i = prefix.indexOf('@');
			if (i == -1) {
				prefixName = prefix;
				prefixUsername = null;
				prefixHostname = null;
			} else {
				int j = prefix.lastIndexOf('!', i);
				if (j == -1) {
					prefixName = prefix.substring(0, i);
					prefixUsername = null;
				} else {
					prefixName = prefix.substring(0, j);
					prefixUsername = prefix.substring(j + 1, i);
				}
				prefixHostname = prefix.substring(i + 1);
			}
		} else {
			prefixName = null;
			prefixHostname = null;
			prefixUsername = null;
		}
		command = m.group(2);
		
		// Parse any number of parameters
		String rest = m.group(3);
		List<String> params = new ArrayList<>();
		while (rest.length() > 0) {
			int i = 0;
			while (i < rest.length() && rest.charAt(i) == ' ')
				i++;
			if (i == 0)
				throw new AssertionError();
			
			if (i == rest.length())
				break;
			else if (rest.charAt(i) == ':') {
				params.add(rest.substring(i + 1));
				break;
			} else {
				int j = rest.indexOf(' ', i);
				if (j == -1)
					j = rest.length();
				params.add(rest.substring(i, j));
				rest = rest.substring(j);
			}
		}
		parameters = Collections.unmodifiableList(params);
	}
	
	
	/*---- Methods ----*/
	
	// Returns the parameter at the given index if possible. Throws IrcSyntaxException if the index is greater than or
	// equal to the highest available parameter index (which means a parameter at the index was expected but not present),
	// or throws IndexOutOfBoundsException if index < 0 (which is always a programming error),
	public String getParameter(int index) {
		if (index < 0)
			throw new IndexOutOfBoundsException();
		else if (index >= parameters.size())
			throw new IrcSyntaxException("Missing expected parameter at index " + index);
		else
			return parameters.get(index);
	}
	
	
	/*---- Constants ----*/
	
	private static final Pattern PREFIX_COMMAND_REGEX = Pattern.compile("(:[^ ]+ +)?([^ :][^ ]*)(.*)");
	
}
