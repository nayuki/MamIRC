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


// Represents the state of an IRC client connected to an IRC server, for the duration of a single connection.
final class IrcSession {
	
	/*---- Fields ----*/
	
	// Not null, immutable.
	public final String profileName;
	
	
	/* Fields used during registration */
	
	// Not null, and can only progress forward in the enum order.
	private RegState registrationState;
	
	// Not null before successful registration, null thereafter.
	private Set<String> rejectedNicknames;
	
	
	/* General state fields */
	
	// Can be null when attempting to register, not null when REGISTERED.
	private String currentNickname;
	
	private final Map<CaselessString,ChannelState> currentChannels;
	
	
	
	/*---- Constructor ----*/
	
	public IrcSession(String profName) {
		if (profName == null)
			throw new NullPointerException();
		profileName = profName;
		
		// Set initial values
		registrationState = RegState.CONNECTING;
		rejectedNicknames = new HashSet<>();
		currentNickname = null;
		currentChannels = new HashMap<>();
	}
	
	
	
	/*---- Getter methods ----*/
	
	// Result is not null.
	public RegState getRegistrationState() {
		return registrationState;
	}
	
	
	// Returns a boolean value, as long as the state is not REGISTERED.
	public boolean isNicknameRejected(String name) {
		if (rejectedNicknames == null)
			throw new IllegalStateException();
		return rejectedNicknames.contains(name);
	}
	
	
	// If registration is REGISTERED, result is not null. Otherwise it can be null.
	public String getCurrentNickname() {
		return currentNickname;
	}
	
	
	public Map<CaselessString,ChannelState> getChannels() {
		return currentChannels;
	}
	
	
	/*---- Setter/mutation methods ----*/
	
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
		}
		registrationState = newState;
	}
	
	
	// Returns silently if okay, otherwise throws an exception for various conditions.
	public void moveNicknameToRejected() {
		if (currentNickname == null)
			throw new IllegalStateException("Current nickname is null");
		if (registrationState == RegState.REGISTERED)
			throw new IllegalStateException("Not tracking rejected nicknames when registered");
		rejectedNicknames.add(currentNickname);
		currentNickname = null;
	}
	
	
	// If registration state is REGISTERED, name must not be null. Otherwise it can be null.
	public void setNickname(String name) {
		if (name == null && registrationState == RegState.REGISTERED)
			throw new NullPointerException();
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
	
	public enum RegState {
		CONNECTING, OPENED, NICK_SENT, USER_SENT, REGISTERED;
	}
	
	
	public static final class ChannelState {
		
		public final Set<String> members;  // Not null, size at least 0
		
		public boolean isProcessingNamesReply;
		
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
