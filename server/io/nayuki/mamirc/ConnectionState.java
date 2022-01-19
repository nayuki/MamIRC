package io.nayuki.mamirc;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


final class ConnectionState {
	
	public final long connectionId;
	public final IrcNetworkProfile profile;
	private final Charset charset;
	
	private boolean isRegistrationHandled = false;
	
	private Optional<String> currentNickname = Optional.empty();
	
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
					if (prefix.isPresent() && 0 < paramsLen) {
						String who = prefix.get().name;
						for (String chan : params.get(0).split(",", -1)) {
							chan = toCanonicalCase(chan);
							if (who.equals(currentNickname.get())) {
								if (!joinedChannels.containsKey(chan))
									joinedChannels.put(chan, new IrcChannel());
							}
						}
					}
					break;
				}
				
				case "KICK": {
					if (1 < paramsLen) {
						String[] chans = params.get(0).split(",", -1);
						String[] users = params.get(1).split(",", -1);
						if (chans.length == 1 && users.length > 1) {
							chans = Arrays.copyOf(chans, users.length);
							Arrays.fill(chans, chans[0]);
						}
						if (!(1 <= chans.length && chans.length == users.length))
							return;
						
						for (int i = 0; i < chans.length; i++) {
							String chan = toCanonicalCase(chans[i]);
							String user = users[i];
							if (user.equals(currentNickname.get())) {
								if (joinedChannels.containsKey(chan))
									joinedChannels.remove(chan);
							}
						}
					}
					break;
				}
				
				case "NICK": {
					if (isRegistrationHandled) {
						if (prefix.isPresent() && 0 < paramsLen) {
							String fromName = prefix.get().name;
							String toName = params.get(0);
							if (currentNickname.isEmpty())
								throw new IllegalStateException();
							if (fromName.equals(currentNickname.get())) {
								currentNickname = Optional.of(toName);
							}
						}
					}
					break;
				}
				
				case "PART": {
					if (prefix.isPresent() && 0 < paramsLen) {
						String who = prefix.get().name;
						for (String chan : params.get(0).split(",", -1)) {
							chan = toCanonicalCase(chan);
							if (who.equals(currentNickname.get())) {
								if (joinedChannels.containsKey(chan))
									joinedChannels.remove(chan);
							}
						}
					}
					break;
				}
				
				case "PING": {
					if (paramsLen == 1)
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
						if (0 < paramsLen)
							currentNickname = Optional.of(params.get(0));
					}
					break;
				}
			}
		}
	}
	
	
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
