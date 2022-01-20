package io.nayuki.mamirc;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;


final class ConnectionState {
	
	public final long connectionId;
	public final IrcNetworkProfile profile;
	private final Charset charset;
	
	private boolean isRegistrationHandled = false;
	
	private Optional<String> currentNickname = Optional.empty();
	
	private Map<String,String> nicknamePrefixToMode = new HashMap<>();
	
	private Map<String,IrcChannel> joinedChannels = new HashMap<>();
	
	
	
	public ConnectionState(long connectionId, IrcNetworkProfile profile) {
		this.connectionId = connectionId;
		this.profile = profile;
		charset = Charset.forName(profile.characterEncoding);
	}
	
	
	public void handle(ConnectionEvent ev, IrcServerConnection con) {
		if (ev instanceof ConnectionEvent.Opened) {
			send(con, "NICK", profile.nicknames.get(0));
			send(con, "USER", profile.username, "0", "*", profile.realName);
		}
		
		else if (ev instanceof ConnectionEvent.LineReceived) {
			String line = new String(((ConnectionEvent.LineReceived)ev).line, charset);
			IrcMessage msg;
			try {
				msg = IrcMessage.parseLine(line);
			} catch (IllegalArgumentException e) {
				return;
			}
			Optional<IrcMessage.Prefix> prefix = msg.prefix;
			List<String> params = msg.parameters;
			int paramsLen = params.size();
			
			switch (msg.command) {
				case "JOIN": {
					if (prefix.isEmpty())
						throw new IrcSyntaxException("JOIN message expects prefix");
					if (paramsLen != 1 && paramsLen != 2)
						throw new IrcSyntaxException("JOIN message expects 1 or 2 parameters");
					String who = prefix.get().name;
					for (String chan : params.get(0).split(",", -1)) {
						chan = toCanonicalCase(chan);
						if (who.equals(currentNickname.get())) {
							if (!joinedChannels.containsKey(chan))
								joinedChannels.put(chan, new IrcChannel());
						} else {
							if (joinedChannels.containsKey(chan)) {
								IrcChannel chanState = joinedChannels.get(chan);
								if (!chanState.users.containsKey(who))
									chanState.users.put(who, new IrcUser());
							}
						}
					}
					break;
				}
				
				case "KICK": {
					if (paramsLen != 2 && paramsLen != 3)
						throw new IrcSyntaxException("KICK message expects 2 or 3 parameters");
					
					String[] chans = params.get(0).split(",", -1);
					String[] users = params.get(1).split(",", -1);
					if (chans.length == 1 && users.length > 1) {
						chans = Arrays.copyOf(chans, users.length);
						Arrays.fill(chans, chans[0]);
					}
					if (chans.length == 0 || chans.length != users.length)
						throw new IrcSyntaxException("KICK message expects 1 or n channels, and n users");
					
					for (int i = 0; i < chans.length; i++) {
						String chan = toCanonicalCase(chans[i]);
						String user = users[i];
						if (!user.equals(currentNickname.get())) {
							if (joinedChannels.containsKey(chan)) {
								IrcChannel chanState = joinedChannels.get(chan);
								if (chanState.users.containsKey(user))
									chanState.users.remove(user);
							}
						}
					}
					for (int i = 0; i < chans.length; i++) {
						String chan = toCanonicalCase(chans[i]);
						String user = users[i];
						if (user.equals(currentNickname.get())) {
							if (joinedChannels.containsKey(chan))
								joinedChannels.remove(chan);
						}
					}
					break;
				}
				
				case "NICK": {
					if (isRegistrationHandled) {
						if (prefix.isEmpty())
							throw new IrcSyntaxException("NICK message expects prefix");
						if (paramsLen != 1)
							throw new IrcSyntaxException("NICK message expects 1 parameter");
						String fromName = prefix.get().name;
						String toName = params.get(0);
						if (currentNickname.isEmpty())
							throw new IllegalStateException();
						if (fromName.equals(currentNickname.get())) {
							currentNickname = Optional.of(toName);
						}
						for (Map.Entry<String,IrcChannel> entry : joinedChannels.entrySet()) {
							IrcChannel chanState = entry.getValue();
							IrcUser userState = chanState.users.remove(fromName);
							if (userState != null) {
								chanState.users.put(toName, userState);
							}
						}
					}
					break;
				}
				
				case "PART": {
					if (prefix.isEmpty())
						throw new IrcSyntaxException("PART message expects prefix");
					if (paramsLen != 1 && paramsLen != 2)
						throw new IrcSyntaxException("PART message expects 1 or 2 parameters");
					String who = prefix.get().name;
					for (String chan : params.get(0).split(",", -1)) {
						chan = toCanonicalCase(chan);
						if (who.equals(currentNickname.get())) {
							if (joinedChannels.containsKey(chan))
								joinedChannels.remove(chan);
						} else {
							if (joinedChannels.containsKey(chan)) {
								IrcChannel chanState = joinedChannels.get(chan);
								if (chanState.users.containsKey(who))
									chanState.users.remove(who);
							}
						}
					}
					break;
				}
				
				case "QUIT": {
					if (prefix.isEmpty())
						throw new IrcSyntaxException("QUIT message expects prefix");
					if (paramsLen != 0)
						throw new IrcSyntaxException("QUIT message expects 0 parameters");
					String who = prefix.get().name;
					for (Map.Entry<String,IrcChannel> entry : joinedChannels.entrySet()) {
						entry.getValue().users.remove(who);
					}
					break;
				}
				
				case "PING": {
					if (paramsLen != 1)
						throw new IrcSyntaxException("PING message expects 1 parameter");
					send(con, "PONG", params.get(0));
					break;
				}
				
				case "001":  // RPL_WELCOME
				case "002":  // RPL_YOURHOST
				case "003":  // RPL_CREATED
				case "004":  // RPL_MYINFO
				case "005":  // RPL_BOUNCE (but instead, servers seem to use it for capability info)
				{
					if (!isRegistrationHandled) {
						for (IrcMessage outMsg : profile.afterRegistrationCommands)
							send(con, outMsg);
						isRegistrationHandled = true;
					}
					
					if (msg.command.equals("005")) {
						for (String param : params) {
							Matcher m = MODE_PREFIX_REGEX.matcher(param);
							if (m.matches()) {
								int[] modes = m.group(1).codePoints().toArray();
								int[] prefixes = m.group(2).codePoints().toArray();
								if (modes.length != prefixes.length ||
										modes.length != IntStream.of(modes).distinct().count() ||
										prefixes.length != IntStream.of(prefixes).distinct().count())
									return;
								if (!nicknamePrefixToMode.isEmpty())
									return;
								for (int i = 0; i < modes.length; i++) {
									nicknamePrefixToMode.put(
										new StringBuilder().appendCodePoint(prefixes[i]).toString(),
										new StringBuilder().appendCodePoint(modes[i]).toString());
								}
							}
						}
					}
					break;
				}
				
				case "331": {  // RPL_NOTOPIC
					if (paramsLen != 2)
						throw new IrcSyntaxException("331 message expects 2 parameters");
					String chan = toCanonicalCase(params.get(1));
					if (joinedChannels.containsKey(chan)) {
						IrcChannel chanState = joinedChannels.get(chan);
						chanState.topic = Optional.empty();
						chanState.topicSetter = Optional.empty();
						chanState.topicTimestamp = Optional.empty();
					}
					break;
				}
				
				case "332": {  // RPL_TOPIC
					if (paramsLen != 3)
						throw new IrcSyntaxException("332 message expects 3 parameters");
					String chan = toCanonicalCase(params.get(1));
					if (joinedChannels.containsKey(chan)) {
						IrcChannel chanState = joinedChannels.get(chan);
						chanState.topic = Optional.of(params.get(2));
						chanState.topicSetter = Optional.empty();
						chanState.topicTimestamp = Optional.empty();
					}
					break;
				}
				
				case "333": {  // Not documented in RFC 2812
					if (paramsLen != 4)
						throw new IrcSyntaxException("333 message expects 4 parameters");
					String chan = toCanonicalCase(params.get(1));
					if (joinedChannels.containsKey(chan)) {
						IrcChannel chanState = joinedChannels.get(chan);
						chanState.topicSetter = Optional.of(params.get(2));
						chanState.topicTimestamp = Optional.of(Long.parseLong(params.get(3)));
					}
					break;
				}
				
				case "353": {  // RPL_NAMREPLY
					if (paramsLen != 4)
						throw new IrcSyntaxException("353 message expects 4 parameters");
					String chan = toCanonicalCase(params.get(2));
					if (joinedChannels.containsKey(chan)) {
						Map<String,IrcUser> accum = joinedChannels.get(chan).namesAccumulator;
						for (String nick : params.get(3).split(" ", -1)) {
							IrcUser userState = new IrcUser();
							for (Map.Entry<String,String> entry : nicknamePrefixToMode.entrySet()) {
								if (nick.startsWith(entry.getKey())) {
									nick = nick.substring(entry.getKey().length());
									userState.modes.add(entry.getValue());
									break;
								}
							}
							if (!accum.containsKey(nick))
								accum.put(nick, userState);
						}
					}
					break;
				}
				
				case "366": {  // RPL_ENDOFNAMES
					if (paramsLen != 3)
						throw new IrcSyntaxException("366 message expects 3 parameters");
					String chan = toCanonicalCase(params.get(1));
					if (joinedChannels.containsKey(chan)) {
						IrcChannel chanState = joinedChannels.get(chan);
						chanState.users = chanState.namesAccumulator;
						chanState.namesAccumulator = new HashMap<>();
					}
					break;
				}
			}
		}
		
		else if (ev instanceof ConnectionEvent.LineSent) {
			String line = new String(((ConnectionEvent.LineSent)ev).line, charset);
			IrcMessage msg;
			try {
				msg = IrcMessage.parseLine(line);
			} catch (IllegalArgumentException e) {
				return;
			}
			List<String> params = msg.parameters;
			int paramsLen = params.size();
			
			switch (msg.command) {
				case "NICK": {
					if (!isRegistrationHandled) {
						if (paramsLen != 1)
							throw new IrcSyntaxException("NICK message expects 1 parameter");
						currentNickname = Optional.of(params.get(0));
					}
					break;
				}
			}
		}
	}
	
	
	private static final Pattern MODE_PREFIX_REGEX = Pattern.compile("PREFIX=\\((.*?)\\)(.*?)");
	
	
	private void send(IrcServerConnection con, String cmd, String... params) {
		send(con, IrcMessage.makeWithoutPrefix(cmd, params));
	}
	
	
	private void send(IrcServerConnection con, IrcMessage msg) {
		con.postWriteLine(msg.toString().getBytes(charset));
	}
	
	
	// For channel names and nicknames.
	private static String toCanonicalCase(String s) {
		return s.codePoints()
			.map(c -> {
				switch (c) {
					case '{':  return '[';
					case '}':  return ']';
					case '|':  return '\\';
					case '^':  return '~';
				}
				if ('A' <= c && c <= 'Z')
					return c - 'A' + 'a';
				return c;
			})
			.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
			.toString();
	}
	
}
