package io.nayuki.mamirc;

import java.io.IOException;
import java.nio.charset.Charset;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


class ConnectionState {
	
	public final long connectionId;
	public final int profileId;
	private Optional<Charset> charset = Optional.empty();
	
	protected Core core;
	
	private boolean isRegistrationHandled = false;
	private Set<String> rejectedNicknames = new HashSet<>();
	
	private Optional<String> currentNickname = Optional.empty();
	
	private Map<String,String> nicknamePrefixToMode = new HashMap<>();
	
	private Map<String,ChannelModeType> channelModeTypes = new HashMap<>();
	
	private Map<String,IrcChannel> joinedChannels = new HashMap<>();
	
	private Archiver archiver;
	
	
	public ConnectionState(long connectionId, int profileId, Core core, Archiver archiver) {
		this.connectionId = connectionId;
		this.profileId = profileId;
		this.core = Objects.requireNonNull(core);
		this.archiver = Objects.requireNonNull(archiver);
	}
	
	
	public void handle(ConnectionEvent ev) {
		if (ev instanceof ConnectionEvent.Opening) {
			charset = Optional.of(Charset.forName(((ConnectionEvent.Opening)ev).characterEncoding));
		} else if (ev instanceof ConnectionEvent.Opened) {
			try (Database db = new Database(core.getDatabaseFile())) {
				send("NICK", db.getProfileNicknames(profileId).get(0));
				send("USER", db.getProfileUsername(profileId), "0", "*", db.getProfileRealName(profileId));
			} catch (IOException|SQLException e) {
				e.printStackTrace();
				close();
			}
		} else if (ev instanceof ConnectionEvent.LineReceived) {
			String line = new String(((ConnectionEvent.LineReceived)ev).line, charset.get());
			handleLineReceived(IrcMessage.parseLine(line), ev);
		} else if (ev instanceof ConnectionEvent.LineSent) {
			String line = new String(((ConnectionEvent.LineSent)ev).line, charset.get());
			handleLineSent(IrcMessage.parseLine(line), ev);
		}
	}
	
	
	private void handleLineReceived(IrcMessage msg, ConnectionEvent ev) {
		Optional<IrcMessage.Prefix> prefix = msg.prefix;
		List<String> params = msg.parameters;
		int paramsLen = params.size();
		
		boolean suppressServerReplyDefaultMessage = false;
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
					addMessage(chan, ev, "R_JOIN", prefix.get().toString(), (isMe ? "me" : "other"));
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
				List<String> dataParts = new ArrayList<>();
				Collections.addAll(dataParts, "R_KICK", user, (isMe ? "me" : "other"), prefix.get().toString());
				if (paramsLen == 3)
					dataParts.add(params.get(2));
				addMessage(chan, ev, dataParts);
				if (isMe)
					joinedChannels.remove(canonChan);
				break;
			}
			
			case "MODE": {
				if (prefix.isEmpty())
					throw new IrcSyntaxException("MODE message expects prefix");
				if (paramsLen < 1)
					throw new IrcSyntaxException("MODE message expects at least 1 parameter");
				String target = params.get(0);
				String canonChan = toCanonicalCase(target);
				IrcChannel chanState = joinedChannels.get(canonChan);
				List<String[]> modes = new ArrayList<>();
				for (int i = 1; i < params.size(); ) {
					String letters = params.get(i);
					i++;
					if (letters.length() < 1)
						throw new IrcSyntaxException("MODE message expects +/- syntax");
					String sign = letters.substring(0, 1);
					if (!sign.equals("+") && !sign.equals("-"))
						throw new IrcSyntaxException("MODE message expects +/- syntax");
					for (int j = 1; j < letters.length(); j++) {
						String mode = letters.substring(j, j + 1);
						ChannelModeType type = channelModeTypes.get(mode);
						if (chanState == null || type == ChannelModeType.NO_PARAMETER || type == ChannelModeType.PARAMETER_WHEN_SET && sign.equals("-"))
							modes.add(new String[]{sign, mode});
						else if (type == ChannelModeType.NICKNAME_OR_ADDRESS_PARAMETER || type == ChannelModeType.SETTING_PARAMETER || type == ChannelModeType.PARAMETER_WHEN_SET && sign.equals("+")) {
							if (i >= params.size())
								throw new IrcSyntaxException("MODE message expects more parameters");
							modes.add(new String[]{sign, mode, params.get(i)});
							i++;
						} else
							throw new IrcSyntaxException("MODE message has unknown mode");
					}
				}
				
				if (chanState != null) {
					for (String[] mode : modes) {
						String letter = mode[1];
						if (channelModeTypes.get(letter) == ChannelModeType.SETTING_PARAMETER && nicknamePrefixToMode.containsValue(letter) && mode.length == 3) {
							String sign = mode[0];
							String nickname = mode[2];
							IrcChannel.User userState = chanState.users.get(nickname);
							if (userState == null)
								throw new IrcStateException("MODE " + nickname + " not in " + target);
							if (sign.equals("+") && !userState.modes.add(letter))
								throw new IrcStateException("MODE " + nickname + " already has +" + letter);
							if (sign.equals("-") && !userState.modes.remove(letter))
								throw new IrcStateException("MODE " + nickname + " does not have +" + letter);
						}
					}
				}
				
				List<String> dataParts = modes
					.stream()
					.map(arr -> String.join(" ", arr))
					.collect(Collectors.toCollection(ArrayList::new));
				if (chanState != null) {
					dataParts.add(0, "R_MODE_CHANNEL");
					addMessage(target, ev, dataParts);
				} else if (target.equals(currentNickname.get())) {
					dataParts.add(0, "R_MODE_ME");
					addMessage(SERVER_WINDOW_NAME, ev, dataParts);
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
					boolean isMe = fromName.equals(currentNickname.get());
					String[] dataParts = {"R_NICK", fromName, toName, (isMe ? "me" : "other")};
					if (isMe) {
						currentNickname = Optional.of(toName);
						addMessage(SERVER_WINDOW_NAME, ev, dataParts);
					}
					for (Map.Entry<String,IrcChannel> entry : joinedChannels.entrySet()) {
						IrcChannel chanState = entry.getValue();
						IrcChannel.User userState = chanState.users.remove(fromName);
						if (userState != null) {
							chanState.users.put(toName, userState);
							addMessage(entry.getKey(), ev, dataParts);
						}
					}
				}
				break;
			}
			
			case "NOTICE": {
				if (paramsLen != 2)
					throw new IrcSyntaxException("NOTICE message expects 2 parameters");
				String from = params.get(0);
				if (currentNickname.isPresent() && currentNickname.get().equals(from) && prefix.isPresent())
					from = prefix.get().name;
				String text = params.get(1);
				addMessage(from, ev, "R_NOTICE", from, text);
				break;
			}
			
			case "PART": {
				if (prefix.isEmpty())
					throw new IrcSyntaxException("PART message expects prefix");
				if (paramsLen != 1 && paramsLen != 2)
					throw new IrcSyntaxException("PART message expects 1 or 2 parameters");
				String who = prefix.get().name;
				boolean isMe = who.equals(currentNickname.get());
				for (String chan : params.get(0).split(",", -1)) {
					String canonChan = toCanonicalCase(chan);
					IrcChannel chanState = joinedChannels.get(canonChan);
					if (chanState == null)
						throw new IrcStateException("PART " + who + " from " + chan + " which myself is not in");
					if (chanState.users.remove(who) == null)
						throw new IrcStateException("PART " + who + " not in " + chan);
					List<String> dataParts = new ArrayList<>();
					Collections.addAll(dataParts, "R_PART", prefix.get().toString(), (isMe ? "me" : "other"));
					if (paramsLen == 2)
						dataParts.add(params.get(1));
					addMessage(chan, ev, dataParts);
					if (isMe)
						joinedChannels.remove(canonChan);
				}
				break;
			}
			
			case "PING": {
				if (paramsLen != 1)
					throw new IrcSyntaxException("PING message expects 1 parameter");
				send("PONG", params.get(0));
				break;
			}
			
			case "PRIVMSG": {
				if (prefix.isEmpty())
					throw new IrcSyntaxException("PRIVMSG message expects prefix");
				if (paramsLen != 2)
					throw new IrcSyntaxException("PRIVMSG message expects 2 parameters");
				String target = params.get(0);
				String text = params.get(1);
				addMessage(target, ev, "R_PRIVMSG", prefix.get().toString(), text);
				break;
			}
			
			case "QUIT": {
				if (prefix.isEmpty())
					throw new IrcSyntaxException("QUIT message expects prefix");
				if (paramsLen != 0 && paramsLen != 1)
					throw new IrcSyntaxException("QUIT message expects 0 or 1 parameters");
				String who = prefix.get().name;
				boolean isMe = who.equals(currentNickname.get());
				List<String> dataParts = new ArrayList<>();
				Collections.addAll(dataParts, "R_QUIT", prefix.get().toString(), (isMe ? "me" : "other"));
				if (paramsLen == 1)
					dataParts.add(params.get(0));
				if (isMe)
					addMessage(SERVER_WINDOW_NAME, ev, dataParts);
				for (Map.Entry<String,IrcChannel> entry : joinedChannels.entrySet()) {
					if (entry.getValue().users.remove(who) != null)
						addMessage(entry.getKey(), ev, dataParts);
				}
				break;
			}
			
			case "001":  // RPL_WELCOME
			case "002":  // RPL_YOURHOST
			case "003":  // RPL_CREATED
			case "004": {  // RPL_MYINFO
				if (!isRegistrationHandled) {
					try (Database db = new Database(core.getDatabaseFile())) {
						for (String cmd : db.getProfileAfterRegistrationCommands(profileId))
							send(IrcMessage.parseLine(cmd));
					} catch (IOException|SQLException e) {
						e.printStackTrace();
					}
					isRegistrationHandled = true;
					rejectedNicknames.clear();
				}
				break;
			}
			
			case "005":  // RPL_ISUPPORT
			{
				for (String param : params) {
					Matcher m = Pattern.compile("PREFIX=\\((.*?)\\)(.*?)").matcher(param);
					if (m.matches()) {
						int[] modes = m.group(1).codePoints().toArray();
						int[] prefixes = m.group(2).codePoints().toArray();
						if (modes.length != prefixes.length ||
								modes.length != IntStream.of(modes).distinct().count() ||
								prefixes.length != IntStream.of(prefixes).distinct().count())
							throw new IrcSyntaxException("Invalid prefix-mode-mapping string");
						if (!nicknamePrefixToMode.isEmpty())
							throw new IrcStateException("Duplicate prefix-mode-mapping string");
						for (int i = 0; i < modes.length; i++) {
							String mode = new StringBuilder().appendCodePoint(modes[i]).toString();
							nicknamePrefixToMode.put(
								new StringBuilder().appendCodePoint(prefixes[i]).toString(),
								mode);
							channelModeTypes.put(mode, ChannelModeType.SETTING_PARAMETER);
						}
					}
					
					{
						m = Pattern.compile("CHANMODES=([A-Za-z]*),([A-Za-z]*),([A-Za-z]*),([A-Za-z]*)").matcher(param);
						if (m.matches()) {
							int i = 1;
							for (ChannelModeType type : ChannelModeType.values()) {
								m.group(i).codePoints().forEach(c ->
									channelModeTypes.put(
										new StringBuilder().appendCodePoint(c).toString(),
										type));
								i++;
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
				addMessage(chan, ev, "R_TOPIC_NONE");
				suppressServerReplyDefaultMessage = true;
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
				addMessage(chan, ev, "R_TOPIC_SET", topic);
				suppressServerReplyDefaultMessage = true;
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
				addMessage(chan, ev, "R_TOPIC_SETTER", setter, timestamp.toString());
				suppressServerReplyDefaultMessage = true;
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
				suppressServerReplyDefaultMessage = true;
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
				List<String> dataParts = new ArrayList<>();
				dataParts.add("R_NAMES");
				new TreeMap<>(chanState.users).forEach((nick, userState) -> {
					dataParts.add(nick);
					List<String> modeParts = userState.modes
						.stream()
						.sorted()
						.map(s -> "+" + s)
						.collect(Collectors.toList());
					dataParts.add(String.join(" ", modeParts));
				});
				addMessage(chan, ev, dataParts);
				suppressServerReplyDefaultMessage = true;
				break;
			}
			
			case "432":  // ERR_ERRONEUSNICKNAME
			case "433": {  // ERR_NICKNAMEINUSE
				if (!isRegistrationHandled) {
					if (currentNickname.isEmpty())
						throw new IrcStateException("ERR_NICKNAMEINUSE/ERR_ERRONEUSNICKNAME without current nickname");
					rejectedNicknames.add(currentNickname.get());
					List<String> nicknames;
					try (Database db = new Database(core.getDatabaseFile())) {
						nicknames = db.getProfileNicknames(profileId);
					} catch (IOException|SQLException e) {
						e.printStackTrace();
						close();
						return;
					}
					Optional<String> nextNick = nicknames.stream()
						.filter(s -> !rejectedNicknames.contains(s)).findFirst();
					if (nextNick.isEmpty())
						close();
					send("NICK", nextNick.get());
				}
				break;
			}
		}
		
		if (msg.command.matches("[0-9]{3}") && !suppressServerReplyDefaultMessage) {
			List<String> dataParts = new ArrayList<>();
			dataParts.add("R_RPL");
			dataParts.add(msg.command);
			dataParts.add(prefix.get().toString());
			dataParts.addAll(params.subList(1, params.size()));
			addMessage(SERVER_WINDOW_NAME, ev, dataParts);
		}
	}
	
	
	private void handleLineSent(IrcMessage msg, ConnectionEvent ev) {
		List<String> params = msg.parameters;
		int paramsLen = params.size();
		
		switch (msg.command) {
			case "LIST": {
				if (paramsLen != 0)
					throw new IrcSyntaxException("LIST message expects 0 parameters");
				addMessage(SERVER_WINDOW_NAME, ev, "S_LIST");
				break;
			}
			
			case "NICK": {
				if (!isRegistrationHandled) {
					if (paramsLen != 1)
						throw new IrcSyntaxException("NICK message expects 1 parameter");
					String toName = params.get(0);
					currentNickname = Optional.of(toName);
					addMessage(SERVER_WINDOW_NAME, ev, "S_NICK", toName);
				}
				break;
			}
			
			case "PRIVMSG": {
				if (paramsLen != 2)
					throw new IrcSyntaxException("PRIVMSG message expects 2 parameters");
				String target = params.get(0);
				String text = params.get(1);
				addMessage(target, ev, "S_PRIVMSG", currentNickname.get(), text);
				break;
			}
			
			case "USER": {
				if (paramsLen != 4)
					throw new IrcSyntaxException("USER message expects 4 parameters");
				String username = params.get(0);
				String mode = params.get(1);
				String unused = params.get(2);
				String realName = params.get(3);
				addMessage(SERVER_WINDOW_NAME, ev, "S_USER", username, mode, unused, realName);
				break;
			}
		}
	}
	
	
	private void send(String cmd, String... params) {
		send(IrcMessage.makeWithoutPrefix(cmd, params));
	}
	
	
	private void send(IrcMessage msg) {
		send(msg.toString().getBytes(charset.get()));
	}
	
	
	protected void send(byte[] line) {}
	
	
	protected void close() {}
	
	
	private void addMessage(String windowDisplayName, ConnectionEvent ev, String... dataParts) {
		addMessage(windowDisplayName, ev, Arrays.asList(dataParts));
	}
		
		
	private void addMessage(String windowDisplayName, ConnectionEvent ev, List<String> dataParts) {
		if (Objects.requireNonNull(dataParts).size() == 0)
			throw new IllegalArgumentException("Empty data parts");
		archiver.postMessage(
			profileId,
			Objects.requireNonNull(windowDisplayName),
			Objects.requireNonNull(ev).timestampUnixMs,
			String.join("\n", dataParts));
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
	
	
	private static final String SERVER_WINDOW_NAME = "";  // Special value, do not modify
	
	
	
	private enum ChannelModeType {
		NICKNAME_OR_ADDRESS_PARAMETER,
		SETTING_PARAMETER,
		PARAMETER_WHEN_SET,
		NO_PARAMETER,
	}
	
}
