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
		try {
			ThickEvent tev = new ThickEvent(ev, sessions.get(ev.connectionId), msgSink);
			super.processEvent(tev);
			switch (tev.type) {
				case CONNECTION:
					processConnection(tev, realtime);
					break;
				case RECEIVE:
					processReceive(tev, realtime);
					break;
				case SEND:
					processSend(tev, realtime);
					break;
				default:
					throw new AssertionError();
			}
		} catch (IrcSyntaxException e) {}
	}
	
	
	private void processConnection(ThickEvent ev, boolean realtime) {
		if (ev.rawLine.startsWith("opened ")) {
			OnlineSessionState session = (OnlineSessionState)ev.session;
			session.setRegistrationState(OnlineSessionState.RegState.OPENED);
		}
	}
	
	
	private void processReceive(ThickEvent ev, boolean realtime) {
		final OnlineSessionState session = (OnlineSessionState)ev.session;
		switch (ev.command) {
			
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
	
	
	private void processSend(ThickEvent ev, boolean realtime) {
		final OnlineSessionState session = (OnlineSessionState)ev.session;
		final IrcLine line = ev.ircLine;
		switch (ev.command) {
			
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
