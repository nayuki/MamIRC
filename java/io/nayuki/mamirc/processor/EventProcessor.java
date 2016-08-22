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
import io.nayuki.mamirc.common.Event;


final class EventProcessor {
	
	/*---- Fields ----*/
	
	public Map<Integer,SessionState> sessions;
	
	protected MessageSink msgSink;
	
	private UpdateManager updateMgr;
	
	
	
	/*---- Constructors ----*/
	
	public EventProcessor(MessageSink msgSink, UpdateManager updateMgr) {
		sessions = new HashMap<>();
		this.msgSink = msgSink;
		this.updateMgr = updateMgr;
	}
	
	
	
	/*---- Methods ----*/
	
	public void processEvent(Event ev) {
		if (ev == null)
			throw new NullPointerException();
		try {
			processEvent(new ThickEvent(ev, sessions.get(ev.connectionId), msgSink));
		} catch (IrcSyntaxException e) {}
	}
	
	
	protected void processEvent(ThickEvent ev) {
		switch (ev.type) {
			case CONNECTION:
				processConnection(ev);
				break;
			case RECEIVE:
				processReceive(ev);
				break;
			case SEND:
				processSend(ev);
				break;
			default:
				throw new AssertionError();
		}
	}
	
	
	private void processConnection(ThickEvent ev) {
		if (ev.type != Event.Type.CONNECTION)
			throw new IllegalArgumentException();
		final String line = ev.rawLine;
		
		if (line.startsWith("connect ")) {
			String[] parts = line.split(" ", 5);
			String profileName = parts[4];
			sessions.put(ev.connectionId, new SessionState(profileName));
			ev.addMessage("", "CONNECT", parts[1], parts[2], parts[3]);
			
		} else if (line.startsWith("opened ")) {
			ev.session.setRegistrationState(SessionState.RegState.OPENED);
			if (updateMgr != null)
				updateMgr.addUpdate("ONLINE", ev.session.profileName);
			ev.addMessage("", "OPENED", line.split(" ", 2)[1]);
			
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
		if (ev.type != Event.Type.RECEIVE)
			throw new IllegalArgumentException();
		final SessionState session = ev.session;
		if (session == null)
			throw new AssertionError();
		final IrcLine line = ev.ircLine;
		switch (line.command.toUpperCase()) {
			
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
					session.currentNickname = feedbackNick;
					if (updateMgr != null)
						updateMgr.addUpdate("MYNICK", feedbackNick);
				}
				break;
			}
			
			case "432":  // ERR_ERRONEUSNICKNAME
			case "433": {  // ERR_NICKNAMEINUSE
				if (session.registrationState != SessionState.RegState.REGISTERED) {
					session.rejectedNicknames.add(session.currentNickname);
					session.currentNickname = null;
				}
				break;
			}
			
			case "NICK": {
				String fromname = line.prefixName;
				String toname   = line.getParameter(0);
				if (fromname.equals(session.currentNickname)) {
					session.currentNickname = toname;
					if (updateMgr != null)
						updateMgr.addUpdate("MYNICK", toname);
					ev.addMessage("", "NICK", fromname, toname);
				}
				for (Map.Entry<CaselessString,SessionState.ChannelState> entry : session.currentChannels.entrySet()) {
					SessionState.ChannelState state = entry.getValue();
					if (state.members.contains(fromname)) {
						CaselessString chan = entry.getKey();
						session.partChannel(chan, fromname);
						session.joinChannel(chan, toname);
						ev.addMessage(chan.properCase, "NICK", fromname, toname);
					}
				}
				break;
			}
			
			case "PRIVMSG": {
				String from   = line.prefixName;
				String target = line.getParameter(0);
				String party = target;
				if (!isChannelName(target))
					party = from;
				String text = line.getParameter(1);
				ev.addMessage(party, "PRIVMSG", from, text);
				break;
			}
			
			case "NOTICE": {
				String from   = line.prefixName;
				String target = line.getParameter(0);
				String party = target;
				if (!isChannelName(target))
					party = from;
				String text = line.getParameter(1);
				ev.addMessage(party, "NOTICE", from, text);
				break;
			}
			
			case "JOIN": {
				String who  = line.prefixName;
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
				String who  = line.prefixName;
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
				String from   = line.prefixName;
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
				String who    = line.prefixName;
				String reason = line.getParameter(0);
				for (Map.Entry<CaselessString,SessionState.ChannelState> entry : session.currentChannels.entrySet()) {
					SessionState.ChannelState state = entry.getValue();
					if (state.members.contains(who)) {
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
					channel.members.clear();
				}
				for (String name : line.getParameter(3).split(" ")) {
					char head = name.length() > 0 ? name.charAt(0) : '\0';
					if (head == '@' || head == '+' || head == '!' || head == '%' || head == '&' || head == '~')
						name = name.substring(1);
					channel.members.add(name);
				}
				break;
			}
			
			case "366": {  // RPL_ENDOFNAMES
				for (Map.Entry<CaselessString,SessionState.ChannelState> entry : session.currentChannels.entrySet()) {
					SessionState.ChannelState channel = entry.getValue();
					if (channel.isProcessingNamesReply) {
						channel.isProcessingNamesReply = false;
						String[] names = channel.members.toArray(new String[0]);
						ev.addMessage(entry.getKey().properCase, "NAMES", names);
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
				String who  = line.prefixName;
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
				String from   = line.prefixName;
				String target = line.getParameter(0);
				String party = target;
				if (!isChannelName(target))
					party = "";  // Assume it is a mode message for my user
				StringBuilder sb = new StringBuilder();
				for (int i = 1; i < line.parameters.size(); i++) {
					if (sb.length() > 0)
						sb.append(" ");
					sb.append(line.getParameter(i));
				}
				ev.addMessage(party, "MODE", from, sb.toString());
				break;
			}
			
			default:  // No action needed for other commands
				break;
		}
		
		// Show some types of server numeric replies
		if (line.command.matches("\\d{3}")) {
			switch (Integer.parseInt(line.command)) {
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
					ev.addMessage("", "SERVRPL", sb.toString());
					break;
				}
			}
		}
	}
	
	
	private void processSend(ThickEvent ev) {
		if (ev.type != Event.Type.SEND)
			throw new IllegalArgumentException();
		final SessionState session = ev.session;
		if (session == null)
			throw new AssertionError();
		final IrcLine line = ev.ircLine;
		switch (ev.command) {
			
			case "NICK": {
				if (session.registrationState == SessionState.RegState.OPENED)
					session.setRegistrationState(SessionState.RegState.NICK_SENT);
				if (session.registrationState != SessionState.RegState.REGISTERED)
					session.currentNickname = line.getParameter(0);
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
	
	
	private static boolean isChannelName(String target) {
		if (target == null)
			throw new NullPointerException();
		return target.length() > 0 && (target.charAt(0) == '#' || target.charAt(0) == '&');
	}
	
}
