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


final class OfflineEventProcessor {
	
	private Map<String,NetworkProfile> profiles;
	
	private Map<Integer,IrcSession> sessions;
	
	private MessageSink msgSink;
	
	
	
	public OfflineEventProcessor(Map<String,NetworkProfile> profiles, MessageSink msgSink) {
		this.profiles = profiles;
		sessions = new HashMap<>();
		this.msgSink = msgSink;
	}
	
	
	
	public void processEvent(Event ev) {
		if (ev == null)
			throw new NullPointerException();
		try {
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
		} catch (IrcSyntaxException e) {}
	}
	
	
	private void processConnection(Event ev) {
		if (ev.type != Event.Type.CONNECTION)
			throw new IllegalArgumentException();
		int conId = ev.connectionId;
		IrcSession state = sessions.get(conId);  // Possibly null
		NetworkProfile profile = state != null ? state.profile : null;  // Possibly null
		String line = ev.line.getString();
		
		if (line.startsWith("connect ")) {
			String[] parts = line.split(" ", 5);
			String profileName = parts[4];
			if (!profiles.containsKey(profileName))
				throw new IllegalStateException("No profile: " + profileName);
			profile = profiles.get(profileName);
			state = new IrcSession(profile);
			sessions.put(conId, state);
			msgSink.addMessage(profile, "", ev, "CONNECT", parts[1], parts[2], parts[3]);
			
		} else if (line.startsWith("opened ")) {
			state.setRegistrationState(IrcSession.RegState.OPENED);
			msgSink.addMessage(profile, "", ev, "OPENED", line.split(" ", 2)[1]);
			
		} else if (line.equals("disconnect")) {
			msgSink.addMessage(profile, "", ev, "DISCONNECT");
			
		} else if (line.equals("closed")) {
			msgSink.addMessage(profile, "", ev, "CLOSED");
			if (state != null)
				sessions.remove(conId);
			
		} else
			throw new AssertionError();
	}
	
	
	private void processReceive(Event ev) {
		if (ev.type != Event.Type.RECEIVE)
			throw new IllegalArgumentException();
		int conId = ev.connectionId;
		IrcSession state = sessions.get(conId);
		if (state == null)
			throw new AssertionError();
		NetworkProfile profile = state.profile;
		IrcLine msg = new IrcLine(ev.line.getString());
		switch (msg.command.toUpperCase()) {
			
			case "001":  // RPL_WELCOME and various welcome messages
			case "002":
			case "003":
			case "004":
			case "005": {
				if (state.getRegistrationState() != IrcSession.RegState.REGISTERED) {
					// This piece of workaround logic handles servers that silently truncate your proposed nickname at registration time
					String feedbackNick = msg.getParameter(0);
					if (state.getCurrentNickname().startsWith(feedbackNick))
						state.setNickname(feedbackNick);
					state.setRegistrationState(IrcSession.RegState.REGISTERED);
				}
				break;
			}
			
			case "432":  // ERR_ERRONEUSNICKNAME
			case "433": {  // ERR_NICKNAMEINUSE
				if (state.getRegistrationState() != IrcSession.RegState.REGISTERED)
					state.moveNicknameToRejected();
				break;
			}
			
			case "NICK": {
				String fromname = msg.prefixName;
				String toname = msg.getParameter(0);
				if (fromname.equals(state.getCurrentNickname())) {
					state.setNickname(toname);
					msgSink.addMessage(profile, "", ev, "NICK", fromname, toname);
				}
				break;
			}
			
			case "PRIVMSG": {
				String from = msg.prefixName;
				String target = msg.getParameter(0);
				String party = target;
				if (target.length() == 0 || (target.charAt(0) != '#' && target.charAt(0) != '&'))
					party = from;  // Target is not a channel, and is therefore a private message to me
				String text = msg.getParameter(1);
				msgSink.addMessage(profile, party, ev, "PRIVMSG", from, text);
				break;
			}
			
			case "NOTICE": {
				String from = msg.prefixName;
				String target = msg.getParameter(0);
				String party = target;
				if (target.length() == 0 || (target.charAt(0) != '#' && target.charAt(0) != '&'))
					party = from;  // Target is not a channel, and is therefore a private message to me
				String text = msg.getParameter(1);
				msgSink.addMessage(profile, party, ev, "NOTICE", from, text);
				break;
			}
			
			case "JOIN": {
				String who = msg.prefixName;
				String chan = msg.getParameter(0);
				msgSink.addMessage(profile, chan, ev, "JOIN", who);
				break;
			}
			
			case "PART": {
				String who = msg.prefixName;
				String chan = msg.getParameter(0);
				msgSink.addMessage(profile, chan, ev, "PART", who);
				break;
			}
			
			case "KICK": {
				String from = msg.prefixName;
				String chan = msg.getParameter(0);
				String target = msg.getParameter(1);
				String reason = msg.getParameter(2);
				msgSink.addMessage(profile, chan, ev, "KICK", from, target, reason);
				break;
			}
			
			case "MODE": {
				String from = msg.prefixName;
				String target = msg.getParameter(0);
				String party = target;
				if (target.length() == 0 || (target.charAt(0) != '#' && target.charAt(0) != '&'))
					party = "";  // Target is not a channel, and thus this is a user mode message
				StringBuilder sb = new StringBuilder();
				for (int i = 1; i < msg.parameters.size(); i++) {
					if (sb.length() > 0)
						sb.append(" ");
					sb.append(msg.getParameter(i));
				}
				msgSink.addMessage(profile, party, ev, "MODE", from, sb.toString());
				break;
			}
			
			default:  // No action needed for other commands
				break;
		}
		
		// Show some types of server numeric replies
		if (msg.command.matches("\\d{3}")) {
			switch (Integer.parseInt(msg.command)) {
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
					for (int i = 1; i < msg.parameters.size(); i++) {
						if (sb.length() > 0)
							sb.append(" ");
						sb.append(msg.getParameter(i));
					}
					msgSink.addMessage(profile, "", ev, "SERVRPL", sb.toString());
					break;
				}
			}
		}
	}
	
	
	private void processSend(Event ev) {
		if (ev.type != Event.Type.SEND)
			throw new IllegalArgumentException();
		int conId = ev.connectionId;
		IrcSession state = sessions.get(conId);
		if (state == null)
			throw new AssertionError();
		NetworkProfile profile = state.profile;
		IrcLine line = new IrcLine(ev.line.getString());
		switch (line.command.toUpperCase()) {
			
			case "NICK": {
				if (state.getRegistrationState() == IrcSession.RegState.OPENED)
					state.setRegistrationState(IrcSession.RegState.NICK_SENT);
				if (state.getRegistrationState() != IrcSession.RegState.REGISTERED)
					state.setNickname(line.getParameter(0));
				// Otherwise when registered, rely on receiving NICK from the server
				break;
			}
			
			case "USER": {
				if (state.getRegistrationState() == IrcSession.RegState.NICK_SENT)
					state.setRegistrationState(IrcSession.RegState.USER_SENT);
				break;
			}
			
			case "PRIVMSG": {
				String from = state.getCurrentNickname();
				String party = line.getParameter(0);
				String text = line.getParameter(1);
				msgSink.addMessage(profile, party, ev, "PRIVMSG+OUTGOING", from, text);
				break;
			}
			
			case "NOTICE": {
				String from = state.getCurrentNickname();
				String party = line.getParameter(0);
				String text = line.getParameter(1);
				msgSink.addMessage(profile, party, ev, "NOTICE+OUTGOING", from, text);
				break;
			}
			
			default:  // No action needed for other commands
				break;
		}
	}
	
}
