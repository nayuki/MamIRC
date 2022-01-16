/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.almworks.sqlite4java.SQLiteException;
import io.nayuki.mamirc.common.Event;
import io.nayuki.mamirc.common.Utils;


class EventProcessor {
	
	/*---- Fields ----*/
	
	// Maps connection ID to session state. The map, keys, and values are all not null.
	protected Map<Integer,SessionState> sessions;
	
	// The downstream object that receives new window messages as events are processed. Not null.
	protected MessageManager msgSink;
	
	// The downstream object that receives state updates as events are processed. Not null.
	protected StateUpdateHistory updateMgr;
	
	
	
	/*---- Constructors ----*/
	
	// Constructs an event processor with the given message manager and a default no-op state update history.
	public EventProcessor(MessageManager msgSink) {
		if (msgSink == null || updateMgr == null)
			throw new NullPointerException();
		sessions = new HashMap<>();
		this.msgSink = msgSink;
		this.updateMgr = new StateUpdateHistory();
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
	
	
	protected void processConnection(ThickEvent ev) {
		assert ev.type == Event.Type.CONNECTION;
		final String line = ev.rawLine;
		
		if (line.startsWith("connect ")) {
			String[] parts = line.split(" ", 5);
			String profileName = parts[4];
			sessions.put(ev.connectionId, new SessionState(profileName));
			try {
				msgSink.clearLaterMessages(profileName, ev.connectionId);
			} catch (SQLiteException e) {
				e.printStackTrace();
			}
			msgSink.addMessage(profileName, "", ev.connectionId, ev.timestamp, "CONNECT", parts[1], parts[2], parts[3]);
			
		} else if (line.startsWith("opened ")) {
			ev.session.setRegistrationState(SessionState.RegState.OPENED);
			updateMgr.addUpdate("ONLINE", ev.session.profileName);
			ev.addMessage("", "OPENED", line.split(" ", 2)[1]);
			
		} else if (line.equals("disconnect")) {
			ev.addMessage("", "DISCONNECT");
			updateMgr.addUpdate("OFFLINE", ev.session.profileName);
			
		} else if (line.equals("closed")) {
			ev.addMessage("", "CLOSED");
			updateMgr.addUpdate("OFFLINE", ev.session.profileName);
			if (ev.session != null)
				sessions.remove(ev.connectionId);
			
		} else
			throw new AssertionError();
	}
	
	
	protected void processReceive(ThickEvent ev) {
		assert ev.type == Event.Type.RECEIVE && ev.session != null;
		final SessionState session = ev.session;
		final IrcLine line = ev.ircLine;
		switch (ev.command) {
			
			case "001":  // RPL_WELCOME and various welcome messages
			case "002":
			case "003":
			case "004":
			case "005": {
				if (session.registrationState != SessionState.RegState.REGISTERED)
					session.setRegistrationState(SessionState.RegState.REGISTERED);
				// This piece of workaround logic handles servers that silently truncate your proposed nickname at registration time
				String feedbackNick = line.getParameter(0);
				if (session.currentNickname.startsWith(feedbackNick)) {
					session.setNickname(feedbackNick);
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
				}
				break;
			}
			
			case "NICK": {
				String fromname = line.getPrefixName();
				String toname   = line.getParameter(0);
				if (fromname.equals(session.currentNickname)) {
					session.setNickname(toname);
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
				if (who.equals(session.currentNickname))
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
	
	
	protected void processSend(ThickEvent ev) {
		assert ev.type == Event.Type.SEND && ev.session != null;
		final SessionState session = ev.session;
		final IrcLine line = ev.ircLine;
		switch (ev.command) {
			
			case "NICK": {
				if (session.registrationState == SessionState.RegState.OPENED)
					session.setRegistrationState(SessionState.RegState.NICK_SENT);
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
	
	
	// Tests whether the given string represents a channel name (as opposed to a user name).
	// For example, "#politics" and "&help" are channel names, whereas "Alice" and "_bob" are user names.
	private static boolean isChannelName(String target) {
		if (target == null)
			throw new NullPointerException();
		return target.length() > 0 && (target.charAt(0) == '#' || target.charAt(0) == '&');
	}
	
}
