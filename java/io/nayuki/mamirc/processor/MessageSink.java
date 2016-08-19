/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import io.nayuki.mamirc.common.Event;


final class MessageSink {
	
	public void addMessage(NetworkProfile profile, String party, Event timestamp, String type, String... args) {
		if (profile == null || party == null || timestamp == null || type == null || args == null)
			throw new NullPointerException();
		String profName = profile.name;
		long tstamp = timestamp.timestamp;
	}
	
}
