package io.nayuki.mamirc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


// Immutable.
final class IrcMessage {
	
	public final Optional<Prefix> prefix;
	public final String command;
	public final List<String> parameters;
	
	
	// Assumes that the input params is immutable.
	private IrcMessage(Optional<Prefix> pfx, String cmd, List<String> params) {
		prefix = Objects.requireNonNull(pfx);
		
		command = Objects.requireNonNull(cmd);
		if (!COMMAND_REGEX.matcher(cmd).matches())
			throw new IllegalArgumentException("Invalid command");
		
		Objects.requireNonNull(params);
		for (int i = 0; i < params.size(); i++) {
			String param = Objects.requireNonNull(params.get(i));
			if (i < params.size() - 1 && (param.startsWith(":") || param.contains(" ")))
				throw new IllegalArgumentException("Invalid parameter");
		}
		parameters = Collections.unmodifiableList(params);
	}
	
	
	public static IrcMessage makeWithoutPrefix(String cmd, String... params) {
		return new IrcMessage(Optional.empty(), cmd, new ArrayList<>(Arrays.asList(params)));
	}
	
	
	public static IrcMessage parseLine(String line) {
		Objects.requireNonNull(line);
		if (line.contains("\r") || line.contains("\n"))
			throw new IllegalArgumentException("Syntax error");
		int start = 0;
		
		// Prefix
		Optional<Prefix> prefix = Optional.empty();
		if (line.charAt(start) == ':') {
			int end = line.indexOf(' ', start);
			if (end == -1)
				throw new IllegalArgumentException("Syntax error");
			prefix = Optional.of(Prefix.parse(line.substring(start + 1, end)));
			start = end + 1;
		}
		
		// Command
		String command;
		{
			int end = line.indexOf(' ', start);
			if (end == -1)
				end = line.length();
			command = line.substring(start, end);
			if (!COMMAND_REGEX.matcher(command).matches())
				throw new IllegalArgumentException("Syntax error");
			start = end;
		}
		
		// Parameters
		List<String> parameters = new ArrayList<>();
		while (start < line.length()) {
			if (line.charAt(start) != ' ')
				throw new AssertionError();
			start++;
			if (start >= line.length())
				throw new IllegalArgumentException("Syntax error");
			if (line.charAt(start) == ':') {
				parameters.add(line.substring(start + 1));
				break;
			} else {
				int end = line.indexOf(' ', start);
				if (end == -1)
					end = line.length();
				parameters.add(line.substring(start, end));
				start = end;
			}
		}
		
		return new IrcMessage(prefix, command, parameters);
	}
	
	private static final Pattern COMMAND_REGEX =
		Pattern.compile("([A-Za-z]+|[0-9]{3})(.*)");
	
	
	public String toString() {
		StringBuilder sb = new StringBuilder();
		prefix.ifPresent(p -> sb.append(":").append(p).append(" "));
		sb.append(command);
		
		for (int i = 0; i < parameters.size(); i++) {
			String param = parameters.get(i);
			if (i < parameters.size() - 1)
				sb.append(" ").append(param);
			else
				sb.append(" :").append(param);
		}
		return sb.toString();
	}
	
	
	
	/*---- Child data structure ----*/
	
	// Immutable.
	public static final class Prefix {
		
		public final String name;  // Represents servername or nickname
		public final Optional<String> username;  // If this is present then hostname must be present
		public final Optional<String> hostname;
		
		
		private Prefix(String nm, Optional<String> user, Optional<String> host) {
			name = Objects.requireNonNull(nm);
			username = Objects.requireNonNull(user);
			hostname = Objects.requireNonNull(host);
			if (username.isPresent() && hostname.isEmpty())
				throw new IllegalArgumentException("Hostname required");
		}
		
		
		public static Prefix parse(String prefix) {
			Objects.requireNonNull(prefix);
			Matcher m = REGEX.matcher(prefix);
			if (!m.matches())
				throw new IllegalArgumentException("Syntax error");
			String name = m.group(1);
			
			Optional<String> username = Optional.empty();
			if (m.group(2) != null)
				username = Optional.of(m.group(2));
			
			Optional<String> hostname = Optional.empty();
			if (m.group(3) != null)
				hostname = Optional.of(m.group(3));
			
			return new Prefix(name, username, hostname);
		}
		
		private static final Pattern REGEX =
			Pattern.compile("([^!@]+)(?:(?:!([^!@]+))?@([^!@]+))?");
		
		
		public String toString() {
			StringBuilder sb = new StringBuilder(name);
			username.ifPresent(s -> sb.append("!").append(s));
			hostname.ifPresent(s -> sb.append("@").append(s));
			return sb.toString();
		}
		
	}
	
}
