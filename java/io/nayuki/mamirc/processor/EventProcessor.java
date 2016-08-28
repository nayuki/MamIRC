/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.almworks.sqlite4java.SQLiteException;
import io.nayuki.mamirc.common.Event;
import io.nayuki.mamirc.common.Utils;


final class EventProcessor {
	
	/*---- Fields ----*/
	
	private Map<Integer,SessionState> sessions;  // Not null
	
	private boolean isRealtime;  // Initially false
	
	private MessageManager msgSink;  // Not null
	
	private UpdateManager updateMgr;  // Can be null
	
	private MamircProcessor master;  // Can be null
	
	private Map<String,NetworkProfile> profiles;
	
	
	
	/*---- Constructors ----*/
	
	public EventProcessor(MessageManager msgSink) {
		this(msgSink, null, null);
	}
	
	
	public EventProcessor(MessageManager msgSink, UpdateManager updateMgr, MamircProcessor master) {
		if (msgSink == null)
			throw new NullPointerException();
		sessions = new HashMap<>();
		isRealtime = false;
		this.msgSink = msgSink;
		this.updateMgr = updateMgr;
		this.master = master;
	}
	
	
	
	/*---- Methods ----*/
	
	public void processEvent(Event ev) {
		if (ev == null)
			throw new NullPointerException();
		try {
			ThickEvent tev = new ThickEvent(ev, sessions.get(ev.connectionId), msgSink);
			switch (tev.type) {
				case CONNECTION:
					processConnection(tev);
					break;
				case RECEIVE:
					processReceive(tev);
					break;
				case SEND:
					processSend(tev);
					break;
				default:
					throw new AssertionError();
			}
		} catch (IrcSyntaxException e) {}
	}
	
	
	private void processConnection(ThickEvent ev) {
		assert ev.type == Event.Type.CONNECTION;
		final String line = ev.rawLine;
		
		if (line.startsWith("connect ")) {
			String[] parts = line.split(" ", 5);
			String profileName = parts[4];
			sessions.put(ev.connectionId, new SessionState(profileName));
			if (!isRealtime) {
				try {
					msgSink.clearLaterMessages(profileName, ev.connectionId);
				} catch (SQLiteException e) {
					e.printStackTrace();
				}
			}
			msgSink.addMessage(profileName, "", ev.connectionId, ev.timestamp, "CONNECT", parts[1], parts[2], parts[3]);
			
		} else if (line.startsWith("opened ")) {
			ev.session.setRegistrationState(SessionState.RegState.OPENED);
			if (updateMgr != null)
				updateMgr.addUpdate("ONLINE", ev.session.profileName);
			ev.addMessage("", "OPENED", line.split(" ", 2)[1]);
			if (isRealtime) {
				String nickname = profiles.get(ev.session.profileName).nicknames.get(0);
				master.sendCommand("send " + ev.connectionId + " NICK " + nickname);
			}
			
		} else if (line.equals("disconnect")) {
			ev.addMessage("", "DISCONNECT");
			if (updateMgr != null)
				updateMgr.addUpdate("OFFLINE", ev.session.profileName);
			
		} else if (line.equals("closed")) {
			ev.addMessage("", "CLOSED");
			if (updateMgr != null)
				updateMgr.addUpdate("OFFLINE", ev.session.profileName);
			if (ev.session != null)
				sessions.remove(ev.connectionId);
			
		} else
			throw new AssertionError();
	}
	
	
	private void processReceive(ThickEvent ev) {
		assert ev.type == Event.Type.RECEIVE && ev.session != null;
		final SessionState session = ev.session;
		final IrcLine line = ev.ircLine;
		switch (ev.command) {
			
			case "001":  // RPL_WELCOME and various welcome messages
			case "002":
			case "003":
			case "004":
			case "005": {
				if (session.registrationState != SessionState.RegState.REGISTERED) {
					session.setRegistrationState(SessionState.RegState.REGISTERED);
					if (isRealtime) {
						NetworkProfile profile = profiles.get(ev.session.profileName);
						for (String chanName : profile.channels) {
							String[] parts = chanName.split(" ", 2);
							String keyStr = "";
							if (parts.length == 2) {
								chanName = parts[0];
								keyStr = ":" + parts[1];
							}
							if (!session.currentChannels.containsKey(new CaselessString(chanName)))
								master.sendCommand("send " + ev.connectionId + " JOIN " + chanName + keyStr);
						}
					}
				}
				// This piece of workaround logic handles servers that silently truncate your proposed nickname at registration time
				String feedbackNick = line.getParameter(0);
				if (session.currentNickname.startsWith(feedbackNick)) {
					session.setNickname(feedbackNick);
					if (updateMgr != null)
						updateMgr.addUpdate("MYNICK", feedbackNick);
				}
				if (ev.command.equals("005")) {
					// Try to parse some capabilities
					for (int i = 1; i < line.parameters.size(); i++) {
						String capa = line.getParameter(i);
						if (capa.startsWith("PREFIX=")) {  // Looks like "PREFIX=(ohv)@%+"
							Pattern p = Pattern.compile("PREFIX=\\(([a-z]*)\\)([^A-Za-z0-9]*)");
							Matcher m = p.matcher(capa);
							if (!m.matches())
								break;
							String letters = m.group(1);
							String symbols = m.group(2);
							if (letters.length() != symbols.length())
								break;
							session.namesReplyModeMap.clear();
							for (int j = 0; j < letters.length(); j++)
								session.namesReplyModeMap.put(symbols.charAt(j), letters.charAt(j));
						}
					}
				}
				break;
			}
			
			case "432":  // ERR_ERRONEUSNICKNAME
			case "433": {  // ERR_NICKNAMEINUSE
				if (session.registrationState != SessionState.RegState.REGISTERED) {
					session.rejectedNicknames.add(session.currentNickname);
					session.setNickname(null);
					if (isRealtime) {
						boolean found = false;
						NetworkProfile profile = profiles.get(ev.session.profileName);
						for (String nickname : profile.nicknames) {
							if (!session.rejectedNicknames.contains(nickname)) {
								master.sendCommand("send " + ev.connectionId + " NICK " + nickname);
								found = true;
								break;
							}
						}
						if (!found)
							master.sendCommand("disconnect " + ev.connectionId);
					}
				}
				break;
			}
			
			case "NICK": {
				String fromname = line.getPrefixName();
				String toname   = line.getParameter(0);
				if (fromname.equals(session.currentNickname)) {
					session.setNickname(toname);
					if (updateMgr != null)
						updateMgr.addUpdate("MYNICK", toname);
					ev.addMessage("", "NICK", fromname, toname);
				}
				for (Map.Entry<CaselessString,SessionState.ChannelState> entry : session.currentChannels.entrySet()) {
					SessionState.ChannelState chstate = entry.getValue();
					SessionState.ChannelState.MemberState mbrstate = chstate.members.remove(fromname);
					if (mbrstate != null) {
						chstate.members.put(toname, mbrstate);
						ev.addMessage(entry.getKey().properCase, "NICK", fromname, toname);
					}
				}
				break;
			}
			
			case "PRIVMSG": {
				String from   = line.getPrefixName();
				String target = line.getParameter(0);
				String party = target;
				if (!isChannelName(target))
					party = from;
				String text = line.getParameter(1);
				String command = "PRIVMSG";
				if (session.nickflagDetector.matcher(text).find())
					command += "+NICKFLAG";
				ev.addMessage(party, command, from, text);
				break;
			}
			
			case "NOTICE": {
				String from   = line.getPrefixName();
				String target = line.getParameter(0);
				String party = target;
				if (!isChannelName(target))
					party = from;
				String text = line.getParameter(1);
				ev.addMessage(party, "NOTICE", from, text);
				break;
			}
			
			case "JOIN": {
				String who  = line.getPrefixName();
				String user = line.prefixUsername;
				String host = line.prefixHostname;
				if (user == null)
					user = "";
				if (host == null)
					host = "";
				CaselessString chan = new CaselessString(line.getParameter(0));
				if (who.equals(session.currentNickname)) {
					session.joinChannel(chan);
					if (updateMgr != null)
						updateMgr.addUpdate("JOINCHAN", session.profileName, chan.properCase);
				} else
					session.joinChannel(chan, who);
				ev.addMessage(chan.properCase, "JOIN", who, user, host);
				break;
			}
			
			case "PART": {
				String who  = line.getPrefixName();
				CaselessString chan = new CaselessString(line.getParameter(0));
				if (who.equals(session.currentNickname)) {
					session.partChannel(chan);
					if (updateMgr != null)
						updateMgr.addUpdate("PARTCHAN", session.profileName, chan.properCase);
				} else
					session.partChannel(chan, who);
				ev.addMessage(chan.properCase, "PART", who);
				break;
			}
			
			case "KICK": {
				String from   = line.getPrefixName();
				CaselessString chan   = new CaselessString(line.getParameter(0));
				String target = line.getParameter(1);
				String reason = line.getParameter(2);
				if (target.equals(session.currentNickname)) {
					session.partChannel(chan);
					if (updateMgr != null)
						updateMgr.addUpdate("PARTCHAN", session.profileName, chan.properCase);
				} else
					session.partChannel(chan, target);
				ev.addMessage(chan.properCase, "KICK", from, target, reason);
				break;
			}
			
			case "QUIT": {
				String who    = line.getPrefixName();
				String reason = line.getParameter(0);
				for (Map.Entry<CaselessString,SessionState.ChannelState> entry : session.currentChannels.entrySet()) {
					SessionState.ChannelState state = entry.getValue();
					if (state.members.containsKey(who)) {
						CaselessString chan = entry.getKey();
						session.partChannel(chan, who);
						ev.addMessage(chan.properCase, "QUIT", who, reason);
					}
				}
				if (who.equals(session.currentNickname) && updateMgr != null)
					updateMgr.addUpdate("OFFLINE", session.profileName);
				break;
			}
			
			case "353": {  // RPL_NAMREPLY
				SessionState.ChannelState channel = session.currentChannels.get(new CaselessString(line.getParameter(2)));
				if (channel == null)
					break;
				if (!channel.isProcessingNamesReply) {
					channel.isProcessingNamesReply = true;
					channel.oldMembers = channel.members;
					channel.members = new TreeMap<>();
				}
				boolean missing = false; 
				for (String name : line.getParameter(3).split(" ")) {
					// Handle and strip prefixes
					String modes = "";
					while (name.length() > 0) {
						Character temp = session.namesReplyModeMap.get(name.charAt(0));
						if (temp == null)
							break;
						if (modes.indexOf(temp) == -1)
							modes += temp;
						name = name.substring(1);
					}
					SessionState.ChannelState.MemberState mbrstate = channel.oldMembers.remove(name);
					if (mbrstate == null) {
						mbrstate = new SessionState.ChannelState.MemberState();
						missing = true;
					}
					mbrstate.modes = modes;
					channel.members.put(name, mbrstate);
				}
				if (channel.receivedInitialNames && missing)
					Utils.logger.warning("Desynchronization of the set of channel members between IRC server and client");
				break;
			}
			
			case "366": {  // RPL_ENDOFNAMES
				for (Map.Entry<CaselessString,SessionState.ChannelState> entry : session.currentChannels.entrySet()) {
					SessionState.ChannelState channel = entry.getValue();
					if (channel.isProcessingNamesReply) {
						channel.isProcessingNamesReply = false;
						String[] names = channel.members.keySet().toArray(new String[0]);
						ev.addMessage(entry.getKey().properCase, "NAMES", names);
						if (channel.receivedInitialNames && channel.oldMembers.size() > 0)
							Utils.logger.warning("Desynchronization of the set of channel members between IRC server and client");
						channel.receivedInitialNames = true;
						channel.oldMembers = null;
					}
				}
				break;
			}
			
			case "331": {  // RPL_NOTOPIC
				String chan = line.getParameter(1);
				SessionState.ChannelState channel = session.currentChannels.get(new CaselessString(chan));
				if (channel == null)
					break;
				channel.topicText = "";
				ev.addMessage(chan, "NOTOPIC");
				break;
			}
			
			case "332": {  // RPL_TOPIC
				String chan = line.getParameter(1);
				String text = line.getParameter(2);
				SessionState.ChannelState channel = session.currentChannels.get(new CaselessString(chan));
				if (channel == null)
					break;
				channel.topicText = text;
				ev.addMessage(chan, "HASTOPIC", text);
				break;
			}
			
			case "333": {  // RPL_TOPICWHOTIME (not specified in any RFC)
				String chan = line.getParameter(1);
				String who  = line.getParameter(2);
				String time = line.getParameter(3);  // Unix time in seconds
				SessionState.ChannelState channel = session.currentChannels.get(new CaselessString(chan));
				if (channel == null)
					break;
				channel.topicSetBy = who;
				channel.topicSetAt = Long.parseLong(time) * 1000;
				ev.addMessage(chan, "TOPICSET", who, time);
				break;
			}
			
			case "TOPIC": {
				String who  = line.getPrefixName();
				String chan = line.getParameter(0);
				String text = line.getParameter(1);
				SessionState.ChannelState channel = session.currentChannels.get(new CaselessString(chan));
				if (channel == null)
					break;
				channel.topicText = text;
				channel.topicSetBy = who;
				channel.topicSetAt = ev.timestamp;
				ev.addMessage(chan, "TOPIC", who, text);
				break;
			}
			
			case "MODE": {
				String from   = line.getPrefixName();
				String target = line.getParameter(0);
				String party = target;
				if (isChannelName(target)) {
					SessionState.ChannelState chan = session.currentChannels.get(new CaselessString(target));
					if (chan != null)
						handleReceiveMode(chan, line);
				} else  // Assume it is a mode message for my user
					party = "";
				StringBuilder sb = new StringBuilder();
				for (int i = 1; i < line.parameters.size(); i++) {
					if (sb.length() > 0)
						sb.append(" ");
					sb.append(line.getParameter(i));
				}
				ev.addMessage(party, "MODE", from, sb.toString());
				break;
			}
			
			case "INVITE": {
				ev.addMessage("", "INVITE", line.getPrefixName(), line.getParameter(1));
				break;
			}
			
			case "ERROR": {
				ev.addMessage("", "ERROR", line.getParameter(0));
				break;
			}
			
			default:  // No action needed for other commands
				break;
		}
		
		// Show some types of server numeric replies
		if (ev.command.matches("\\d{3}")) {
			switch (Integer.parseInt(ev.command)) {
				case 331:
				case 332:
				case 333:
				case 353:
				case 366: {
					// Do nothing
					break;
				}
				
				default: {
					// Note: Parameter 0 should be my current nickname, which isn't very useful information
					StringBuilder sb = new StringBuilder();
					for (int i = 1; i < line.parameters.size(); i++) {
						if (sb.length() > 0)
							sb.append(" ");
						sb.append(line.getParameter(i));
					}
					ev.addMessage("", "SERVRPL+" + ev.command, sb.toString());
					break;
				}
			}
		}
	}
	
	
	private void processSend(ThickEvent ev) {
		assert ev.type == Event.Type.SEND && ev.session != null;
		final SessionState session = ev.session;
		final IrcLine line = ev.ircLine;
		switch (ev.command) {
			
			case "NICK": {
				if (session.registrationState == SessionState.RegState.OPENED) {
					session.setRegistrationState(SessionState.RegState.NICK_SENT);
					if (isRealtime) {
						NetworkProfile profile = profiles.get(ev.session.profileName);
						master.sendCommand("send " + ev.connectionId + " USER " + profile.username + " 0 * :" + profile.realname);
					}
				}
				if (session.registrationState != SessionState.RegState.REGISTERED)
					session.setNickname(line.getParameter(0));
				// Otherwise when registered, rely on receiving NICK from the server
				break;
			}
			
			case "USER": {
				if (session.registrationState == SessionState.RegState.NICK_SENT)
					session.setRegistrationState(SessionState.RegState.USER_SENT);
				break;
			}
			
			case "PRIVMSG": {
				String from = session.currentNickname;
				String party = line.getParameter(0);
				String text  = line.getParameter(1);
				if (party.equalsIgnoreCase("NickServ")) {  // Censor the password sent to NickServ
					Pattern p = Pattern.compile("(IDENTIFY ).+", Pattern.CASE_INSENSITIVE);
					Matcher m = p.matcher(text);
					if (m.matches())
						text = m.group(1) + "********";
				}
				ev.addMessage(party, "PRIVMSG+OUTGOING", from, text);
				break;
			}
			
			case "NOTICE": {
				String from = session.currentNickname;
				String party = line.getParameter(0);
				String text  = line.getParameter(1);
				ev.addMessage(party, "NOTICE+OUTGOING", from, text);
				break;
			}
			
			case "JOIN": {
				if (isRealtime) {
					String temp = line.getParameter(0);
					if (line.parameters.size() == 2)
						temp += " " + line.getParameter(1);  // Channel key
					if (line.parameters.size() > 2)
						return;
					profiles.get(session.profileName).channels.add(temp);
				}
				break;
			}
			
			case "PART": {
				if (isRealtime) {
					String chan = line.getParameter(0);
					profiles.get(session.profileName).channels.remove(chan);
				}
				break;
			}
			
			case "QUIT": {
				if (isRealtime)
					profiles.get(session.profileName).connect = false;
				break;
			}
			
			default:  // No action needed for other commands
				break;
		}
	}
	
	
	// For example, line = :ChanServ MODE #chat -b+ov *!*@* Alice Bob
	private void handleReceiveMode(SessionState.ChannelState chan, IrcLine line) {
		// Iterate over every character in the flags parameter, while consuming
		// subsequent parameters for each flag that requires a parameter
		String flags = line.getParameter(1);
		int sign = 0;
		for (int i = 0, j = 2; i < flags.length(); i++) {
			char c = flags.charAt(i);
			if (c == '+')
				sign = +1;
			else if (c == '-')
				sign = -1;
			else {
				if (sign == 0)
					throw new IrcSyntaxException("No sign set for mode flag");
				switch (c) {
					case 'a':  // Admin
					case 'h':  // Halfop
					case 'o':  // Operator
					case 'q':  // Owner
					case 'v':  // Voice
						String nick = line.getParameter(j);
						SessionState.ChannelState.MemberState mbrstate = chan.members.get(nick);
						if (mbrstate != null) {
							if (sign == +1)
								mbrstate.addMode(c);
							else
								mbrstate.removeMode(c);
						}
						j++;
						break;
					case 'b':  // Simply consume a parameter without processing it
						j++;
						break;
					default:  // Ignore unknown mode flags
						break;
				}
			}
		}
	}
	
	
	public void finishCatchup(Map<String,NetworkProfile> profiles) {
		if (master == null || isRealtime)
			throw new IllegalStateException();
		isRealtime = true;
		this.profiles = profiles;
		
		// Detect and disconnect multiple current connections to a profile, the ones without the highest connection ID
		Set<Integer> disconnectConIds = new HashSet<>();
		Map<String,Integer> profNameToConId = new HashMap<>();  // Connections to keep
		for (Map.Entry<Integer,SessionState> entry : sessions.entrySet()) {
			int conId = entry.getKey();
			String profName = entry.getValue().profileName;
			if (!profNameToConId.containsKey(profName))  // First seen connection for a given profile name
				profNameToConId.put(profName, conId);
			else {  // Keep only the higher connection ID value
				int oldConId = profNameToConId.get(profName);
				if (oldConId < conId) {
					disconnectConIds.add(oldConId);
					profNameToConId.put(profName, conId);
				} else if (conId < oldConId)
					disconnectConIds.add(conId);
				else
					throw new AssertionError("Duplicate connection ID");
			}
		}
		for (int conId : disconnectConIds)  // Disconnect the unwanted connections
			master.sendCommand("disconnect " + conId);
		
		applyNetworkProfiles(disconnectConIds);
		
		// Resume the registration logic for existing connections
		for (Map.Entry<Integer,SessionState> entry : sessions.entrySet()) {
			int conId = entry.getKey();
			SessionState session = entry.getValue();
			NetworkProfile profile = profiles.get(session.profileName);
			if (disconnectConIds.contains(conId))
				continue;  // Ignore connections that are already condemned
			if (profile == null)
				throw new AssertionError();
			
			switch (session.registrationState) {
				case CONNECTING:
					break;
				case OPENED: {
					String nickname = profiles.get(session.profileName).nicknames.get(0);
					master.sendCommand("send " + conId + " NICK " + nickname);
					break;
				}
				case NICK_SENT:
				case USER_SENT: {
					if (session.currentNickname == null) {
						boolean found = false;
						for (String nickname : profile.nicknames) {
							if (!session.rejectedNicknames.contains(nickname)) {
								master.sendCommand("send " + conId + " NICK " + nickname);
								found = true;
								break;
							}
						}
						if (!found)
							master.sendCommand("disconnect " + conId);
					} else if (session.registrationState == SessionState.RegState.NICK_SENT)
						master.sendCommand("send " + conId + " USER " + profile.username + " 0 * :" + profile.realname);
					break;
				}
				case REGISTERED:
					break;
				default:
					throw new AssertionError();
			}
		}
	}
	
	
	public void applyNetworkProfiles(Set<Integer> disconnectConIds) {
		Set<String> activeProfNames = new HashSet<>();
		for (Map.Entry<Integer,SessionState> entry : sessions.entrySet()) {
			int conId = entry.getKey();
			SessionState session = entry.getValue();
			if (disconnectConIds.contains(conId))
				continue;  // Ignore connections that already have disconnect requested
			
			NetworkProfile profile = profiles.get(session.profileName);
			if (profile == null || !profile.connect) {
				// Disconnect connections that have no known profile or whose profile has connect disabled
				master.sendCommand("disconnect " + conId);
				disconnectConIds.add(conId);
				
			} else {
				activeProfNames.add(session.profileName);
				if (session.registrationState == SessionState.RegState.REGISTERED) {
					// Join channels in current connections, as specified in profiles
					for (String chanName : profile.channels) {
						String[] parts = chanName.split(" ", 2);
						String keyStr = "";
						if (parts.length == 2) {
							chanName = parts[0];
							keyStr = ":" + parts[1];
						}
						if (!session.currentChannels.containsKey(new CaselessString(chanName)))
							master.sendCommand("send " + conId + " JOIN " + chanName + keyStr);
					}
				}
			}
		}
		
		// Connect to profiles that are enabled but not currently connected
		for (NetworkProfile profile : profiles.values()) {
			if (!activeProfNames.contains(profile.name) && profile.connect && profile.servers.size() > 0) {
				NetworkProfile.Server serv = profile.servers.get(0);
				master.sendCommand("connect " + serv.hostnamePort.getHostString() + " " + serv.hostnamePort.getPort() + " " + serv.useSsl + " " + profile.name);
			}
		}
	}
	
	
	private static boolean isChannelName(String target) {
		if (target == null)
			throw new NullPointerException();
		return target.length() > 0 && (target.charAt(0) == '#' || target.charAt(0) == '&');
	}
	
}
