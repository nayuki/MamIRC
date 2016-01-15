/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * http://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;


// Represents the state of an IRC client connected to an IRC server, for the duration of a single connection.
final class IrcSession {
	
	/*---- Fields ----*/
	
	// Not null, immutable
	public final UserConfiguration.IrcNetwork profile;
	
	
	/* Fields used during registration */
	
	// Not null, and can only progress forward in the enum order
	private RegState registrationState;
	
	// Not null before successful registration, null thereafter
	private Set<String> rejectedNicknames;
	
	// Only used for processing archived events and finishing catch-up; unused for real-time processing
	private boolean sentNickservPassword;
	
	
	/* General state fields */
	
	// Can be null when attempting to register, not null when REGISTERED
	private String currentNickname;
	
	// Is null if and only if currentNickname is null
	private Pattern nickflagDetector;
	
	// Not null, size at least 0. The string argument is case-insensitive but case-preserving.
	private Map<String,ChannelState> currentChannels;
	
	// A Unix timestamp in milliseconds, for outbound throttling.
	private long nextLineSendTime;
	
	// Must be positive, for outbound throttling.
	private final int numBurstLines = 4;
	
	// In milliseconds, for outbound throttling.
	private final int sendInterval = 2000;
	
	// In milliseconds, for outbound throttling.
	private final int gracePeriod = (numBurstLines - 1) * sendInterval;
	
	// Raw lines to send to the Connector, for outbound throttling.
	private Queue<String> queuedLines;
	
	
	
	/*---- Constructor ----*/
	
	public IrcSession(UserConfiguration.IrcNetwork profile) {
		if (profile == null)
			throw new NullPointerException();
		this.profile = profile;
		
		// Set initial values
		registrationState = RegState.CONNECTING;
		rejectedNicknames = new HashSet<>();
		sentNickservPassword = false;
		currentNickname = null;
		nickflagDetector = null;
		currentChannels = new CaseInsensitiveTreeMap<>();
		nextLineSendTime = System.currentTimeMillis() - gracePeriod;
		queuedLines = new ArrayDeque<>();
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
	
	
	// Returns a boolean value.
	public boolean getSentNickservPassword() {
		return sentNickservPassword;
	}
	
	
	// If registration is REGISTERED, result is not null. Otherwise it can be null.
	public String getCurrentNickname() {
		return currentNickname;
	}
	
	
	// If registration is REGISTERED, result is not null. Otherwise it can be null.
	public Pattern getNickflagDetector() {
		return nickflagDetector;
	}
	
	
	// Returns the map (mutable), which is not null.
	public Map<String,ChannelState> getCurrentChannels() {
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
		nickflagDetector = null;
	}
	
	
	// The state can only change from false to true, and is idempotent thereafter.
	public void setSentNickservPassword() {
		sentNickservPassword = true;
	}
	
	
	// If registration state is REGISTERED, name must not be null. Otherwise it can be null.
	public void setNickname(String name) {
		if (name == null) {
			if (registrationState == RegState.REGISTERED)
				throw new NullPointerException();
			nickflagDetector = null;
		} else
			nickflagDetector = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
		currentNickname = name;
	}
	
	
	// Enqueues the given raw line and returns the amount of time to delay
	// sending this line (zero or positive). Also modifies/increments the
	// internal state for subsequent calls to this method.
	public int enqueueLineAndGetDelay(String rawLine) {
		if (rawLine == null)
			throw new NullPointerException();
		queuedLines.add(rawLine);
		long now = System.currentTimeMillis();
		nextLineSendTime = Math.max(now - gracePeriod, nextLineSendTime);
		int result = Math.max((int)(nextLineSendTime - now), 0);
		nextLineSendTime += sendInterval;
		return result;
	}
	
	
	// Removes and returns the next raw line to be sent.
	public String dequeueLine() {
		return queuedLines.remove();
	}
	
	
	
	/*---- Nested classes ----*/
	
	public enum RegState {
		CONNECTING, OPENED, NICK_SENT, USER_SENT, REGISTERED;
	}
	
	
	public static final class ChannelState {
		public final Set<String> members;  // Not null, size at least 0
		public boolean processingNamesReply;
		public String topic;  // Null initially and when explicitly known to be empty, otherwise non-null
		
		public ChannelState() {
			members = new TreeSet<>();
			processingNamesReply = false;
		}
	}
	
}
