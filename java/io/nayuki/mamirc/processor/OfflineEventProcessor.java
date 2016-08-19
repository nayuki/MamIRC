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
	
	
	
	public OfflineEventProcessor(Map<String,NetworkProfile> profiles) {
		this.profiles = profiles;
		sessions = new HashMap<>();
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
			if (!profiles.containsKey(profileName))
				throw new IllegalStateException("No profile: " + profileName);
			sessions.put(conId, new IrcSession(profiles.get(profileName)));
			
		} else if (line.startsWith("opened ")) {
			state.setRegistrationState(IrcSession.RegState.OPENED);
		} else if (line.equals("disconnect")) {
			// Do nothing
		} else if (line.equals("closed")) {
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
				if (fromname.equals(state.getCurrentNickname()))
					state.setNickname(toname);
				break;
			}
			
			default:  // No action needed for other commands
				break;
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
			
			default:  // No action needed for other commands
				break;
		}
	}
	
}
