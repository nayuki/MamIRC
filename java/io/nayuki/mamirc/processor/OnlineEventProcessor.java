/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import io.nayuki.mamirc.common.Event;


final class OnlineEventProcessor extends BasicEventProcessor {
	
	/*---- Constructors ----*/
	
	public OnlineEventProcessor(MessageSink msgSink) {
		super(msgSink);
	}
	
	
	
	/*---- Methods ----*/
	
	public void processEvent(Event ev) {
		throw new UnsupportedOperationException();
	}
	
	
	public void processEvent(Event ev, boolean realtime) {
		super.processEvent(ev);
		try {
			switch (ev.type) {
				case CONNECTION:
					processConnection(ev, realtime);
					break;
				case RECEIVE:
					processReceive(ev, realtime);
					break;
				case SEND:
					processSend(ev, realtime);
					break;
				default:
					throw new AssertionError();
			}
		} catch (IrcSyntaxException e) {}
	}
	
	
	private void processConnection(Event ev, boolean realtime) {
		if (ev.line.getString().startsWith("opened ")) {
			OnlineSessionState session = (OnlineSessionState)sessions.get(ev.connectionId);
			session.setRegistrationState(OnlineSessionState.RegState.OPENED);
		}
	}
	
	
	private void processReceive(Event ev, boolean realtime) {
		final OnlineSessionState session = (OnlineSessionState)sessions.get(ev.connectionId);
		final IrcLine line = new IrcLine(ev.line.getString());
		switch (line.command.toUpperCase()) {
			
			case "001":  // RPL_WELCOME and various welcome messages
			case "002":
			case "003":
			case "004":
			case "005": {
				if (session.getRegistrationState() != OnlineSessionState.RegState.REGISTERED)
					session.setRegistrationState(OnlineSessionState.RegState.REGISTERED);
				break;
			}
			
			case "432":  // ERR_ERRONEUSNICKNAME
			case "433": {  // ERR_NICKNAMEINUSE
				if (session.getRegistrationState() != OnlineSessionState.RegState.REGISTERED)
					session.moveNicknameToRejected();
				break;
			}
			
			default:  // No action needed for other commands
				break;
		}
	}
	
	
	private void processSend(Event ev, boolean realtime) {
		final OnlineSessionState session = (OnlineSessionState)sessions.get(ev.connectionId);
		final IrcLine line = new IrcLine(ev.line.getString());
		switch (line.command.toUpperCase()) {
			
			case "NICK": {
				if (session.getRegistrationState() == OnlineSessionState.RegState.OPENED)
					session.setRegistrationState(OnlineSessionState.RegState.NICK_SENT);
				if (session.getRegistrationState() != OnlineSessionState.RegState.REGISTERED)
					session.setNickname(line.getParameter(0));
				// Otherwise when registered, rely on receiving NICK from the server
				break;
			}
			
			case "USER": {
				if (session.getRegistrationState() == OnlineSessionState.RegState.NICK_SENT)
					session.setRegistrationState(OnlineSessionState.RegState.USER_SENT);
				break;
			}
			
			default:  // No action needed for other commands
				break;
		}
	}
	
	
	protected OnlineSessionState createNewSessionState(String profName) {
		return new OnlineSessionState(profName);
	}
	
}
