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
	
	private Map<Integer,IrcSession> sessions;
	
	private MessageSink msgSink;
	
	
	
	public OfflineEventProcessor(MessageSink msgSink) {
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
		String line = ev.line.getString();
		
		if (line.startsWith("connect ")) {
			String[] parts = line.split(" ", 5);
			String profileName = parts[4];
			state = new IrcSession(profileName);
			sessions.put(conId, state);
			msgSink.addMessage(state, "", ev, "CONNECT", parts[1], parts[2], parts[3]);
			
		} else if (line.startsWith("opened ")) {
			state.setRegistrationState(IrcSession.RegState.OPENED);
			msgSink.addMessage(state, "", ev, "OPENED", line.split(" ", 2)[1]);
			
		} else if (line.equals("disconnect")) {
			msgSink.addMessage(state, "", ev, "DISCONNECT");
			
		} else if (line.equals("closed")) {
			msgSink.addMessage(state, "", ev, "CLOSED");
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
		IrcLine line = new IrcLine(ev.line.getString());
		switch (line.command.toUpperCase()) {
			
			case "001":  // RPL_WELCOME and various welcome messages
			case "002":
			case "003":
			case "004":
			case "005": {
				if (state.getRegistrationState() != IrcSession.RegState.REGISTERED) {
					// This piece of workaround logic handles servers that silently truncate your proposed nickname at registration time
					String feedbackNick = line.getParameter(0);
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
				String fromname = line.prefixName;
				String toname = line.getParameter(0);
				if (fromname.equals(state.getCurrentNickname())) {
					state.setNickname(toname);
					msgSink.addMessage(state, "", ev, "NICK", fromname, toname);
				}
				break;
			}
			
			case "PRIVMSG": {
				String from = line.prefixName;
				String target = line.getParameter(0);
				String party = target;
				if (target.length() == 0 || (target.charAt(0) != '#' && target.charAt(0) != '&'))
					party = from;  // Target is not a channel, and is therefore a private message to me
				String text = line.getParameter(1);
				msgSink.addMessage(state, party, ev, "PRIVMSG", from, text);
				break;
			}
			
			case "NOTICE": {
				String from = line.prefixName;
				String target = line.getParameter(0);
				String party = target;
				if (target.length() == 0 || (target.charAt(0) != '#' && target.charAt(0) != '&'))
					party = from;  // Target is not a channel, and is therefore a private message to me
				String text = line.getParameter(1);
				msgSink.addMessage(state, party, ev, "NOTICE", from, text);
				break;
			}
			
			case "JOIN": {
				String who = line.prefixName;
				String chan = line.getParameter(0);
				msgSink.addMessage(state, chan, ev, "JOIN", who);
				break;
			}
			
			case "PART": {
				String who = line.prefixName;
				String chan = line.getParameter(0);
				msgSink.addMessage(state, chan, ev, "PART", who);
				break;
			}
			
			case "KICK": {
				String from = line.prefixName;
				String chan = line.getParameter(0);
				String target = line.getParameter(1);
				String reason = line.getParameter(2);
				msgSink.addMessage(state, chan, ev, "KICK", from, target, reason);
				break;
			}
			
			case "MODE": {
				String from = line.prefixName;
				String target = line.getParameter(0);
				String party = target;
				if (target.length() == 0 || (target.charAt(0) != '#' && target.charAt(0) != '&'))
					party = "";  // Target is not a channel, and thus this is a user mode message
				StringBuilder sb = new StringBuilder();
				for (int i = 1; i < line.parameters.size(); i++) {
					if (sb.length() > 0)
						sb.append(" ");
					sb.append(line.getParameter(i));
				}
				msgSink.addMessage(state, party, ev, "MODE", from, sb.toString());
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
					msgSink.addMessage(state, "", ev, "SERVRPL", sb.toString());
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
				msgSink.addMessage(state, party, ev, "PRIVMSG+OUTGOING", from, text);
				break;
			}
			
			case "NOTICE": {
				String from = state.getCurrentNickname();
				String party = line.getParameter(0);
				String text = line.getParameter(1);
				msgSink.addMessage(state, party, ev, "NOTICE+OUTGOING", from, text);
				break;
			}
			
			default:  // No action needed for other commands
				break;
		}
	}
	
}
