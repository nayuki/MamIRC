/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;


// Represents the state of an IRC client connected to an IRC server, for the duration of a single connection.
final class SessionState {
	
	/*---- Fields ----*/
	
	// Not null, immutable.
	public final String profileName;
	
	// Can be null when attempting to register, not null when REGISTERED.
	public String currentNickname;
	
	// Is null if and only if currentNickname is null.
	public Pattern nickflagDetector;
	
	// Not null, and can only progress forward in the enum order.
	public RegState registrationState;
	
	// Not null before successful registration, null thereafter.
	public Set<String> rejectedNicknames;
	
	// Null before successful registration, not null thereafter.
	public Map<CaselessString,ChannelState> currentChannels;
	
	public Map<Character,Character> namesReplyModeMap;
	
	
	
	/*---- Constructor ----*/
	
	public SessionState(String profName) {
		if (profName == null)
			throw new NullPointerException();
		profileName = profName;
		
		// Set initial values
		currentNickname = null;
		nickflagDetector = null;
		registrationState = RegState.CONNECTING;
		rejectedNicknames = new HashSet<>();
		currentChannels = null;
		
		// Default value
		namesReplyModeMap = new HashMap<>();
		namesReplyModeMap.put('+', 'v');
		namesReplyModeMap.put('@', 'o');
	}
	
	
	
	/*---- Methods ----*/
	
	public void setNickname(String name) {
		if (name == null) {
			if (registrationState == RegState.REGISTERED)
				throw new NullPointerException();
			nickflagDetector = null;
		} else
			nickflagDetector = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
		currentNickname = name;
	}
	
	
	// New state must be non-null, must advance over the previous state,
	// and if new state is REGISTERED then current nickname must be non-null.
	public void setRegistrationState(RegState newState) {
		if (newState == null)
			throw new NullPointerException();
		if (newState.ordinal() <= registrationState.ordinal())
			throw new IllegalArgumentException("Must advance the state");
		if (newState == RegState.REGISTERED) {
			if (currentNickname == null)
				throw new IllegalStateException("Nickname is currently null");
			rejectedNicknames = null;
			currentChannels = new HashMap<>();
		}
		registrationState = newState;
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
	
	public enum RegState {
		CONNECTING, OPENED, NICK_SENT, USER_SENT, REGISTERED;
	}
	
	
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
