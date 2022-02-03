package io.nayuki.mamirc;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


final class ConnectionState {
	
	public final long connectionId;
	public final IrcNetworkProfile profile;
	private final Charset charset;
	
	private boolean isRegistrationHandled = false;
	
	private Optional<String> currentNickname = Optional.empty();
	
	private Map<String,String> nicknamePrefixToMode = new HashMap<>();
	
	private Map<String,IrcChannel> joinedChannels = new HashMap<>();
	
	private Archiver archiver;
	
	
	public ConnectionState(long connectionId, IrcNetworkProfile profile, Archiver archiver) {
		this.connectionId = connectionId;
		this.profile = profile;
		charset = Charset.forName(profile.characterEncoding);
		this.archiver = Objects.requireNonNull(archiver);
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
					boolean isMe = who.equals(currentNickname.get());
					for (String chan : params.get(0).split(",", -1)) {
						String canonChan = toCanonicalCase(chan);
						if (isMe && joinedChannels.put(canonChan, new IrcChannel()) != null)
							throw new IrcStateException("JOIN myself already in " + chan);
						IrcChannel chanState = joinedChannels.get(canonChan);
						if (chanState == null)
							throw new IrcStateException("JOIN " + who + " to " + chan + " which myself is not in");
						if (chanState.users.put(who, new IrcChannel.User()) != null)
							throw new IrcStateException("JOIN " + who + " already in " + chan);
						archiver.postMessage(profile.id, chan, String.join("\n", "R_JOIN", prefix.get().toString(), (isMe ? "me" : "other")));
					}
					break;
				}
				
				case "KICK": {
					if (prefix.isEmpty())
						throw new IrcSyntaxException("KICK message expects prefix");
					if (paramsLen != 2 && paramsLen != 3)
						throw new IrcSyntaxException("KICK message expects 2 or 3 parameters");
					String chan = params.get(0);
					String user = params.get(1);
					if (chan.contains(",") || user.contains(","))
						throw new IrcSyntaxException("KICK message expects 1 channel and 1 user");
					String canonChan = toCanonicalCase(chan);
					IrcChannel chanState = joinedChannels.get(canonChan);
					if (chanState == null)
						throw new IrcStateException("KICK " + user + " from " + chan + " which myself is not in");
					if (chanState.users.remove(user) == null)
						throw new IrcStateException("KICK " + user + " not in " + chan);
					boolean isMe = user.equals(currentNickname.get());
					if (paramsLen == 2)
						archiver.postMessage(profile.id, chan, String.join("\n", "R_KICK", user, (isMe ? "me" : "other"), prefix.get().toString()));
					else if (paramsLen == 3)
						archiver.postMessage(profile.id, chan, String.join("\n", "R_KICK", user, (isMe ? "me" : "other"), prefix.get().toString(), params.get(2)));
					else
						throw new AssertionError();
					if (isMe)
						joinedChannels.remove(canonChan);
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
						boolean isMe = fromName.equals(currentNickname.get());
						if (isMe) {
							currentNickname = Optional.of(toName);
						}
						for (Map.Entry<String,IrcChannel> entry : joinedChannels.entrySet()) {
							IrcChannel chanState = entry.getValue();
							IrcChannel.User userState = chanState.users.remove(fromName);
							if (userState != null) {
								chanState.users.put(toName, userState);
								archiver.postMessage(profile.id, entry.getKey(), String.join("\n", "R_NICK", fromName, toName, (isMe ? "me" : "other")));
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
						String canonChan = toCanonicalCase(chan);
						IrcChannel chanState = joinedChannels.get(canonChan);
						if (chanState == null)
							throw new IrcStateException("PART " + who + " from " + chan + " which myself is not in");
						if (chanState.users.remove(who) == null)
							throw new IrcStateException("PART " + who + " not in " + chan);
						boolean isMe = who.equals(currentNickname.get());
						if (paramsLen == 1)
							archiver.postMessage(profile.id, chan, String.join("\n", "R_PART", prefix.get().toString(), (isMe ? "me" : "other")));
						else if (paramsLen == 2)
							archiver.postMessage(profile.id, chan, String.join("\n", "R_PART", prefix.get().toString(), (isMe ? "me" : "other"), params.get(1)));
						else
							throw new AssertionError();
						if (isMe)
							joinedChannels.remove(canonChan);
					}
					break;
				}
				
				case "PRIVMSG": {
					if (prefix.isEmpty())
						throw new IrcSyntaxException("PRIVMSG message expects prefix");
					if (paramsLen != 2)
						throw new IrcSyntaxException("PRIVMSG message expects 2 parameters");
					String target = params.get(0);
					String text = params.get(1);
					archiver.postMessage(profile.id, target, String.join("\n", "R_PRIVMSG", prefix.get().toString(), text));
					break;
				}
				
				case "QUIT": {
					if (prefix.isEmpty())
						throw new IrcSyntaxException("QUIT message expects prefix");
					if (paramsLen != 0 && paramsLen != 1)
						throw new IrcSyntaxException("QUIT message expects 0 or 1 parameters");
					String who = prefix.get().name;
					boolean isMe = who.equals(currentNickname.get());
					for (Map.Entry<String,IrcChannel> entry : joinedChannels.entrySet()) {
						if (entry.getValue().users.remove(who) != null) {
							if (paramsLen == 0)
								archiver.postMessage(profile.id, entry.getKey(), String.join("\n", "R_QUIT", prefix.get().toString(), (isMe ? "me" : "other")));
							else if (paramsLen == 1)
								archiver.postMessage(profile.id, entry.getKey(), String.join("\n", "R_QUIT", prefix.get().toString(), (isMe ? "me" : "other"), params.get(0)));
							else
								throw new AssertionError();
						}
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
					String chan = params.get(1);
					IrcChannel chanState = joinedChannels.get(toCanonicalCase(chan));
					if (chanState == null)
						throw new IrcStateException("331 myself not in " + chan);
					chanState.topic = Optional.empty();
					chanState.topicSetter = Optional.empty();
					chanState.topicTimestamp = Optional.empty();
					archiver.postMessage(profile.id, chan, String.join("\n", "R_TOPIC_NONE"));
					break;
				}
				
				case "332": {  // RPL_TOPIC
					if (paramsLen != 3)
						throw new IrcSyntaxException("332 message expects 3 parameters");
					String chan = params.get(1);
					IrcChannel chanState = joinedChannels.get(toCanonicalCase(chan));
					if (chanState == null)
						throw new IrcStateException("332 myself not in " + chan);
					String topic = params.get(2);
					chanState.topic = Optional.of(topic);
					chanState.topicSetter = Optional.empty();
					chanState.topicTimestamp = Optional.empty();
					archiver.postMessage(profile.id, chan, String.join("\n", "R_TOPIC_SET", topic));
					break;
				}
				
				case "333": {  // Not documented in RFC 2812
					if (paramsLen != 4)
						throw new IrcSyntaxException("333 message expects 4 parameters");
					String chan = params.get(1);
					String setter = params.get(2);
					Long timestamp = Long.valueOf(params.get(3));
					IrcChannel chanState = joinedChannels.get(toCanonicalCase(chan));
					if (chanState == null)
						throw new IrcStateException("333 myself not in " + chan);
					chanState.topicSetter = Optional.of(setter);
					chanState.topicTimestamp = Optional.of(timestamp);
					archiver.postMessage(profile.id, chan, String.join("\n", "R_TOPIC_SETTER", setter, timestamp.toString()));
					break;
				}
				
				case "353": {  // RPL_NAMREPLY
					if (paramsLen != 4)
						throw new IrcSyntaxException("353 message expects 4 parameters");
					String chan = params.get(2);
					IrcChannel chanState = joinedChannels.get(toCanonicalCase(chan));
					if (chanState == null)
						throw new IrcStateException("353 myself not in " + chan);
					Map<String,IrcChannel.User> accum = chanState.namesAccumulator;
					for (String nick : params.get(3).split(" ", -1)) {
						IrcChannel.User userState = new IrcChannel.User();
						for (Map.Entry<String,String> entry : nicknamePrefixToMode.entrySet()) {
							if (nick.startsWith(entry.getKey())) {
								nick = nick.substring(entry.getKey().length());
								userState.modes.add(entry.getValue());
								break;
							}
						}
						if (accum.put(nick, userState) != null)
							throw new IrcStateException("353 " + nick + " already in " + chan);
					}
					break;
				}
				
				case "366": {  // RPL_ENDOFNAMES
					if (paramsLen != 3)
						throw new IrcSyntaxException("366 message expects 3 parameters");
					String chan = params.get(1);
					IrcChannel chanState = joinedChannels.get(toCanonicalCase(chan));
					if (chanState == null)
						throw new IrcStateException("366 myself not in " + chan);
					chanState.users = chanState.namesAccumulator;
					chanState.namesAccumulator = new HashMap<>();
					List<String> messageParts = new ArrayList<>();
					messageParts.add("R_NAMES");
					new TreeMap<>(chanState.users).forEach((nick, userState) -> {
						messageParts.add(nick);
						List<String> modeParts = userState.modes
							.stream()
							.sorted()
							.map(s -> "+" + s)
							.collect(Collectors.toList());
						messageParts.add(String.join(" ", modeParts));
					});
					archiver.postMessage(profile.id, chan, String.join("\n", messageParts));
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
				
				case "PRIVMSG": {
					if (paramsLen != 2)
						throw new IrcSyntaxException("PRIVMSG message expects 2 parameters");
					String target = params.get(0);
					String text = params.get(1);
					archiver.postMessage(profile.id, target, String.join("\n", "S_PRIVMSG", currentNickname.get(), text));
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
	public static String toCanonicalCase(String s) {
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
