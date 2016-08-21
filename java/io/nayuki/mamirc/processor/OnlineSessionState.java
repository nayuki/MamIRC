/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.util.HashSet;
import java.util.Set;


final class OnlineSessionState extends BasicSessionState {
	
	/*---- Fields ----*/
	
	// Not null, and can only progress forward in the enum order.
	private RegState registrationState;
	
	// Not null before successful registration, null thereafter.
	private Set<String> rejectedNicknames;
	
	
	
	/*---- Constructor ----*/
	
	public OnlineSessionState(String profName) {
		super(profName);
		// Set initial values
		registrationState = RegState.CONNECTING;
		rejectedNicknames = new HashSet<>();
	}
	
	
	
	/*---- Methods ----*/
	
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
	
	
	
	/*---- Nested classes ----*/
	
	public enum RegState {
		CONNECTING, OPENED, NICK_SENT, USER_SENT, REGISTERED;
	}
	
}
