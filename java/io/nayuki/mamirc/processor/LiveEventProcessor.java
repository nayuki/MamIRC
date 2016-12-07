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
import java.util.Timer;
import java.util.TimerTask;


final class LiveEventProcessor extends EventProcessor {
	
	/*---- Fields ----*/
	
	private Map<String,ConnectionAttemptState> attempts;  // Not null
	
	private boolean isRealtime;  // Initially false
	
	private MamircProcessor master;
	
	private Timer timer;
	
	
	
	/*---- Constructors ----*/
	
	public LiveEventProcessor(MessageManager msgSink, MamircProcessor master) {
		super(msgSink);
		if (master == null)
			throw new NullPointerException();
		attempts = new HashMap<>();
		isRealtime = false;
		this.msgSink = msgSink;
		this.master = master;
		timer = new Timer();
	}
	
	
	
	/*---- Methods ----*/
	
	protected void processConnection(ThickEvent ev) {
		master.checkLock();
		super.processConnection(ev);
		final String line = ev.rawLine;
		
		if (isRealtime && line.startsWith("opened ")) {
			String nickname = profiles.get(ev.session.profileName).nicknames.get(0);
			master.sendCommand("send " + ev.connectionId + " NICK " + nickname);
			
		} else if (isRealtime && line.equals("closed")) {
			final String profileName = ev.session.profileName;
			if (profiles.containsKey(profileName) && profiles.get(profileName).connect) {
				if (!attempts.containsKey(profileName)) {
					ConnectionAttemptState attempt = new ConnectionAttemptState();
					attempts.put(profileName, attempt);
					tryConnect(profileName, attempt.serverIndex);
				} else {
					final ConnectionAttemptState attempt = attempts.get(profileName);
					int delay = attempt.nextAttempt();
					attempt.timerTask = new TimerTask() {
						public void run() {
							master.lock.lock();
							try {
								if (attempt.timerTask != this)
									return;
								attempt.timerTask = null;
								tryConnect(profileName, attempt.serverIndex);
							} finally {
								master.lock.unlock();
							}
						}
					};
					timer.schedule(attempt.timerTask, delay);
				}
			}
			
		}
	}
	
	
	protected void processReceive(ThickEvent ev) {
		master.checkLock();
		super.processReceive(ev);
		final SessionState session = ev.session;
		switch (ev.command) {
			
			case "001":  // RPL_WELCOME and various welcome messages
			case "002":
			case "003":
			case "004":
			case "005": {
				if (isRealtime && session.registrationState != SessionState.RegState.REGISTERED) {
					attempts.remove(session.profileName);
					NetworkProfile profile = profiles.get(ev.session.profileName);
					for (String chanName : profile.channels) {
						String[] parts = chanName.split(" ", 2);
						String keyStr = "";
						if (parts.length == 2) {
							chanName = parts[0];
							keyStr = " :" + parts[1];
						}
						if (!session.currentChannels.containsKey(new CaselessString(chanName)))
							master.sendCommand("send " + ev.connectionId + " JOIN " + chanName + keyStr);
					}
				}
				break;
			}
			
			case "432":  // ERR_ERRONEUSNICKNAME
			case "433": {  // ERR_NICKNAMEINUSE
				if (isRealtime && session.registrationState != SessionState.RegState.REGISTERED) {
					if (isRealtime) {
						boolean found = false;
						NetworkProfile profile = profiles.get(ev.session.profileName);
						for (String nickname : profile.nicknames) {
							if (!session.rejectedNicknames.contains(nickname)) {
								master.sendCommand("send " + ev.connectionId + " NICK " + nickname);
								found = true;
								break;
							}
						}
						if (!found)
							master.sendCommand("disconnect " + ev.connectionId);
					}
				}
				break;
			}
			
			default:  // No action needed for other commands
				break;
		}
	}
	
	
	protected void processSend(ThickEvent ev) {
		master.checkLock();
		super.processSend(ev);
		final SessionState session = ev.session;
		final IrcLine line = ev.ircLine;
		switch (ev.command) {
			
			case "NICK": {
				if (isRealtime && session.registrationState == SessionState.RegState.OPENED) {
					NetworkProfile profile = profiles.get(ev.session.profileName);
					master.sendCommand("send " + ev.connectionId + " USER " + profile.username + " 0 * :" + profile.realname);
				}
				break;
			}
			
			case "JOIN": {
				if (isRealtime) {
					String temp = line.getParameter(0);
					if (line.parameters.size() == 2)
						temp += " " + line.getParameter(1);  // Channel key
					if (line.parameters.size() > 2)
						return;
					profiles.get(session.profileName).channels.add(temp);
				}
				break;
			}
			
			case "PART": {
				if (isRealtime) {
					String chan = line.getParameter(0);
					profiles.get(session.profileName).channels.remove(chan);
				}
				break;
			}
			
			case "QUIT": {
				if (isRealtime)
					profiles.get(session.profileName).connect = false;
				break;
			}
			
			default:  // No action needed for other commands
				break;
		}
	}
	
	
	public void finishCatchup(Map<String,NetworkProfile> profiles) {
		master.checkLock();
		if (isRealtime)
			throw new IllegalStateException();
		isRealtime = true;
		this.profiles = profiles;
		
		// Detect and disconnect multiple current connections to a profile, the ones without the highest connection ID
		Set<Integer> disconnectConIds = new HashSet<>();
		Map<String,Integer> profNameToConId = new HashMap<>();  // Connections to keep
		for (Map.Entry<Integer,SessionState> entry : sessions.entrySet()) {
			int conId = entry.getKey();
			String profName = entry.getValue().profileName;
			if (!profNameToConId.containsKey(profName))  // First seen connection for a given profile name
				profNameToConId.put(profName, conId);
			else {  // Keep only the higher connection ID value
				int oldConId = profNameToConId.get(profName);
				if (oldConId < conId) {
					disconnectConIds.add(oldConId);
					profNameToConId.put(profName, conId);
				} else if (conId < oldConId)
					disconnectConIds.add(conId);
				else
					throw new AssertionError("Duplicate connection ID");
			}
		}
		for (int conId : disconnectConIds)  // Disconnect the unwanted connections
			master.sendCommand("disconnect " + conId);
		
		applyNetworkProfiles(disconnectConIds);
		
		// Resume the registration logic for existing connections
		for (Map.Entry<Integer,SessionState> entry : sessions.entrySet()) {
			int conId = entry.getKey();
			SessionState session = entry.getValue();
			NetworkProfile profile = profiles.get(session.profileName);
			if (disconnectConIds.contains(conId))
				continue;  // Ignore connections that are already condemned
			if (profile == null)
				throw new AssertionError();
			
			switch (session.registrationState) {
				case CONNECTING:
					break;
				case OPENED: {
					String nickname = profiles.get(session.profileName).nicknames.get(0);
					master.sendCommand("send " + conId + " NICK " + nickname);
					break;
				}
				case NICK_SENT:
				case USER_SENT: {
					if (session.currentNickname == null) {
						boolean found = false;
						for (String nickname : profile.nicknames) {
							if (!session.rejectedNicknames.contains(nickname)) {
								master.sendCommand("send " + conId + " NICK " + nickname);
								found = true;
								break;
							}
						}
						if (!found)
							master.sendCommand("disconnect " + conId);
					} else if (session.registrationState == SessionState.RegState.NICK_SENT)
						master.sendCommand("send " + conId + " USER " + profile.username + " 0 * :" + profile.realname);
					break;
				}
				case REGISTERED:
					break;
				default:
					throw new AssertionError();
			}
		}
	}
	
	
	public void applyNetworkProfiles(Set<Integer> disconnectConIds) {
		master.checkLock();
		
		// Cancel all pending connection attempts
		for (ConnectionAttemptState attempt : attempts.values()) {
			if (attempt.timerTask != null) {
				attempt.timerTask.cancel();
				attempt.timerTask = null;
			}
		}
		attempts.clear();
		
		Set<String> activeProfNames = new HashSet<>();
		for (Map.Entry<Integer,SessionState> entry : sessions.entrySet()) {
			int conId = entry.getKey();
			SessionState session = entry.getValue();
			if (disconnectConIds.contains(conId))
				continue;  // Ignore connections that already have disconnect requested
			
			NetworkProfile profile = profiles.get(session.profileName);
			if (profile == null || !profile.connect) {
				// Disconnect connections that have no known profile or whose profile has connect disabled
				master.sendCommand("disconnect " + conId);
				disconnectConIds.add(conId);
				
			} else {
				activeProfNames.add(session.profileName);
				if (session.registrationState == SessionState.RegState.REGISTERED) {
					// Join channels in current connections, as specified in profiles
					for (String chanName : profile.channels) {
						String[] parts = chanName.split(" ", 2);
						String keyStr = "";
						if (parts.length == 2) {
							chanName = parts[0];
							keyStr = " :" + parts[1];
						}
						if (!session.currentChannels.containsKey(new CaselessString(chanName)))
							master.sendCommand("send " + conId + " JOIN " + chanName + keyStr);
					}
				}
			}
		}
		
		// Connect to profiles that are enabled but not currently connected
		for (NetworkProfile profile : profiles.values()) {
			if (!activeProfNames.contains(profile.name) && profile.connect && profile.servers.size() > 0) {
				NetworkProfile.Server serv = profile.servers.get(0);
				master.sendCommand("connect " + serv.hostnamePort.getHostString() + " " + serv.hostnamePort.getPort() + " " + serv.useSsl + " " + profile.name);
			}
		}
	}
	
	
	private void tryConnect(String profileName, int serverIndex) {
		master.checkLock();
		NetworkProfile profile = profiles.get(profileName);
		if (profile == null || !profile.connect)
			return;
		NetworkProfile.Server serv = profile.servers.get(serverIndex % profile.servers.size());
		master.sendCommand("connect " + serv.hostnamePort.getHostString() + " " + serv.hostnamePort.getPort() + " " + serv.useSsl + " " + profile.name);
	}
	
}
