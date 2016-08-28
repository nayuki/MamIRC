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


// A helper structure for EventProcessor, which contains most of Event's fields, plus extra context,
// and with the data half-parsed. All fields are final, and all simple data values are immutable.
// References to complex/active objects (e.g. SessionState) do have mutable state however.
final class ThickEvent {
	
	/*---- Fields ----*/
	
	/* All fields are immutable, but some underlying objects may be mutable. */
	
	// Basic event-related fields. All are fully immutable and not null.
	public final int connectionId;
	public final long timestamp;
	public final Type type;
	public final String rawLine;
	
	// Fields derived from rawLine. Fully immutable.
	// If type is RECEIVE or SEND, then the following fields are not null.
	// Otherwise if type is CONNECTION, then these fields are null.
	public final IrcLine ircLine;
	public final String command;
	
	// Extra context fields. All are mutable objects.
	public final SessionState session;  // Can be null
	private final MessageManager messageManager;  // Not null
	
	
	
	/*---- Constructors ----*/
	
	// Constructs an event based on the given values. Note that IrcSyntaxException can be thrown.
	public ThickEvent(Event ev, SessionState session, MessageManager msgMgr) {
		// Check arguments
		if (ev == null || msgMgr == null || session == null && ev.type != Event.Type.CONNECTION)
			throw new NullPointerException();
		
		// Set basic event fields
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
		this.messageManager = msgMgr;
	}
	
	
	
	/*---- Methods ----*/
	
	// Adds a window message with the given values to the message manager.
	public void addMessage(String party, String msgType, String... args) {
		messageManager.addMessage(session.profileName, party, connectionId, timestamp, msgType, args);
	}
	
}
