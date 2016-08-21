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
import java.util.Set;
import java.util.TreeSet;


// Represents the state of an IRC client connected to an IRC server, for the duration of a single connection.
class BasicSessionState {
	
	/*---- Fields ----*/
	
	// Not null, immutable.
	public final String profileName;
	
	// Can be null when attempting to register, not null when REGISTERED.
	protected String currentNickname;
	
	protected final Map<CaselessString,ChannelState> currentChannels;
	
	
	
	/*---- Constructor ----*/
	
	public BasicSessionState(String profName) {
		if (profName == null)
			throw new NullPointerException();
		profileName = profName;
		
		// Set initial values
		currentNickname = null;
		currentChannels = new HashMap<>();
	}
	
	
	
	/*---- Getter methods ----*/
	
	// If registration is REGISTERED, result is not null. Otherwise it can be null.
	public String getCurrentNickname() {
		return currentNickname;
	}
	
	
	public Map<CaselessString,ChannelState> getChannels() {
		return currentChannels;
	}
	
	
	/*---- Setter/mutation methods ----*/
	
	// If registration state is REGISTERED, name must not be null. Otherwise it can be null.
	public void setNickname(String name) {
		currentNickname = name;
	}
	
	
	public void joinChannel(CaselessString channel) {
		if (currentChannels.containsKey(channel))
			return;
		currentChannels.put(channel, new ChannelState());
		joinChannel(channel, currentNickname);
	}
	
	
	public void joinChannel(CaselessString channel, String nickname) {
		if (!currentChannels.containsKey(channel))
			return;
		currentChannels.get(channel).members.add(nickname);
	}
	
	
	public void partChannel(CaselessString channel) {
		currentChannels.remove(channel);
	}
	
	
	public void partChannel(CaselessString channel, String nickname) {
		if (!currentChannels.containsKey(channel))
			return;
		currentChannels.get(channel).members.remove(nickname);
	}
	
	
	
	/*---- Nested classes ----*/
	
	public static final class ChannelState {
		
		public final Set<String> members;  // Not null, size at least 0
		
		public boolean isProcessingNamesReply;  // Usually false, unless received RPL_NAMREPLY but not RPL_ENDOFNAMES yet
		
		public String topicText;   // Null if not known, "" if RPL_NOTOPIC, otherwise a non-empty string
		public String topicSetBy;  // Null if not known or topicText is null
		public long   topicSetAt;  // Unix time in milliseconds, valid only if topicText is not null
		
		
		public ChannelState() {
			members = new TreeSet<>();
			isProcessingNamesReply = false;
			topicText  = null;
			topicSetBy = null;
			topicSetAt = 0;
		}
		
	}
	
}
