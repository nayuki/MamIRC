/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import io.nayuki.mamirc.common.Event;
import io.nayuki.mamirc.common.Event.Type;


// A structure that contains most of Event's fields, plus extra context, and with the data half-parsed.
final class ThickEvent {
	
	/*---- Event-related fields ----*/
	
	public final int connectionId;  // Immutable
	public final long timestamp;    // Immutable
	public final Type type;         // Immutable
	public final String rawLine;    // Immutable
	
	// If type is RECEIVE or SEND, then the following fields are not null.
	// Otherwise if type is CONNECTION, then the following fields are null.
	public final IrcLine ircLine;  // Immutable
	public final String command;   // Immutable, converted to uppercase
	
	
	/*---- Extra context fields ----*/
	
	public final SessionState session;  // Can be null. Underlying object is mutable.
	public final MessageSink messageSink;    // Not null. Underlying object is mutable.
	
	
	
	/*---- Constructors ----*/
	
	public ThickEvent(Event ev, SessionState session, MessageSink msgSink) {
		// Check arguments
		if (ev == null || msgSink == null || ev.type != Event.Type.CONNECTION && session == null)
			throw new NullPointerException();
		
		// Set simple event fields
		connectionId = ev.connectionId;
		timestamp = ev.timestamp;
		type = ev.type;
		rawLine = ev.line.getString();
		
		// Set parsed IRC line fields
		if (type == Event.Type.CONNECTION) {
			ircLine = null;
			command = null;
		} else if (type == Event.Type.RECEIVE || type == Event.Type.SEND) {
			ircLine = new IrcLine(rawLine);
			command = ircLine.command.toUpperCase();
		} else
			throw new AssertionError();
		
		// Set extra context fields
		this.session = session;
		messageSink = msgSink;
	}
	
	
	
	/*---- Methods ----*/
	
	public void addMessage(String party, String msgType, String... args) {
		messageSink.addMessage(session.profileName, party, connectionId, timestamp, msgType, args);
	}
	
}
