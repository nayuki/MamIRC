package io.nayuki.mamirc.processor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.nayuki.mamirc.common.ConnectorConfiguration;
import io.nayuki.mamirc.common.Event;
import io.nayuki.mamirc.common.OutputWriterThread;
import io.nayuki.mamirc.processor.ProcessorConfiguration.IrcNetwork;


public final class MamircProcessor {
	
	/*---- Stub main program ----*/
	
	public static void main(String[] args) throws IOException {
		Logger.getLogger("com.almworks.sqlite4java").setLevel(Level.OFF);  // Prevent sqlite4java module from polluting stderr with debug messages
		new MamircProcessor(
			new ConnectorConfiguration(new File(args[0])),
			new ProcessorConfiguration(new File(args[1])));
	}
	
	
	
	/*---- Fields (global state) ----*/
	
	private final ProcessorConfiguration myConfiguration;
	
	private DatabaseLoggerThread databaseLogger;
	
	private OutputWriterThread writer;
	
	private final Map<Integer,ConnectionState> ircConnections;
	
	
	
	/*---- Constructor ----*/
	
	public MamircProcessor(ConnectorConfiguration conConfig, ProcessorConfiguration procConfig) {
		if (conConfig == null || procConfig == null)
			throw new NullPointerException();
		myConfiguration = procConfig;
		databaseLogger = null;
		writer = null;
		ircConnections = new HashMap<>();
		
		// Wait for database logger to be ready before connecting
		new DatabaseLoggerThread(this, myConfiguration.databaseFile).start();
		synchronized(this) {
			while (databaseLogger == null) {
				try {
					this.wait();
				} catch (InterruptedException e) {}
			}
		}
		new ConnectorReaderThread(this, conConfig).start();
	}
	
	
	/*---- Methods for manipulating global state ----*/
	
	public synchronized void processEvent(Event ev, boolean realtime) {
		switch (ev.type) {
			case CONNECTION:
				processConnection(ev, realtime);
				break;
			case RECEIVE:
				processReceive(ev, realtime);
				break;
			case SEND:
				processSend(ev, realtime);
				break;
			default:
				throw new AssertionError();
		}
	}
	
	
	// Must only be called by processEvent().
	private void processConnection(Event ev, boolean realtime) {
		int conId = ev.connectionId;
		ConnectionState state = ircConnections.get(conId);  // Possibly null
		String line = new String(ev.getLine(), OutputWriterThread.UTF8_CHARSET);
		
		if (line.startsWith("connect ")) {
			String metadata = line.substring(8);
			if (!myConfiguration.ircNetworks.containsKey(metadata))
				throw new IllegalStateException("No profile: " + metadata);
			ircConnections.put(conId, new ConnectionState(myConfiguration.ircNetworks.get(metadata)));
			
		} else if (line.equals("opened")) {
			state.registrationState = ConnectionState.RegState.OPENED;
			if (realtime)
				send(conId, "NICK", state.profile.nicknames.get(0));
			
		} else if (line.equals("disconnect") || line.equals("closed"))
			ircConnections.remove(conId);
	}
	
	
	// Must only be called by processEvent().
	private void processReceive(Event ev, boolean realtime) {
		int conId = ev.connectionId;
		ConnectionState state = ircConnections.get(conId);  // Not null
		IrcNetwork profile = state.profile;
		IrcLine msg = new IrcLine(new String(ev.getLine(), OutputWriterThread.UTF8_CHARSET));
		switch (msg.command) {
			
			case "PING": {
				String text = msg.parameters.get(0);
				if (realtime)
					send(conId, "PONG", text);
				else
					state.queuedPongs.add(text);
				break;
			}
			
			case "NICK": {
				if (new IrcLine.Prefix(msg.prefix).name.equals(state.currentNickname))
					state.currentNickname = msg.parameters.get(0);
				break;
			}
			
			case "JOIN": {
				IrcLine.Prefix prefix = new IrcLine.Prefix(msg.prefix);
				if (prefix.name.equals(state.currentNickname))
					state.currentChannels.add(msg.parameters.get(0));
				String line = msg.command + " " + prefix.name;
				databaseLogger.postMessage(conId, ev.sequence, ev.timestamp, profile.name, msg.parameters.get(0), line);
				break;
			}
			
			case "PART": {
				IrcLine.Prefix prefix = new IrcLine.Prefix(msg.prefix);
				if (prefix.name.equals(state.currentNickname))
					state.currentChannels.remove(msg.parameters.get(0));
				String line = msg.command + " " + prefix.name;
				databaseLogger.postMessage(conId, ev.sequence, ev.timestamp, profile.name, msg.parameters.get(0), line);
				break;
			}
			
			case "PRIVMSG": {
				String src = new IrcLine.Prefix(msg.prefix).name;
				String party = msg.parameters.get(0);
				if (party.charAt(0) != '#' && party.charAt(0) != '&')  // Not a channel, and is therefore a private message to me
					party = src;
				String text = msg.parameters.get(1);
				String line = msg.command + " " + src + " " + text;
				databaseLogger.postMessage(conId, ev.sequence, ev.timestamp, profile.name, party, line);
				break;
			}
			
			case "433": {  // ERR_NICKNAMEINUSE
				if (state.registrationState != ConnectionState.RegState.REGISTERED) {
					state.rejectedNicknames.add(state.currentNickname);
					if (realtime) {
						boolean found = false;
						for (String nickname : profile.nicknames) {
							if (!state.rejectedNicknames.contains(nickname)) {
								send(conId, "NICK", nickname);
								found = true;
								break;
							}
						}
						if (!found)
							writer.postWrite("disconnect " + conId);
					} else
						state.currentNickname = null;
				}
				break;
			}
			
			case "001":  // RPL_WELCOME and various welcome messages
			case "002":
			case "003":
			case "004":
			case "005": {
				if (state.registrationState != ConnectionState.RegState.REGISTERED) {
					state.registrationState = ConnectionState.RegState.REGISTERED;
					state.rejectedNicknames = null;
					if (realtime) {
						if (profile.nickservPassword != null && !state.sentNickservPassword)
							send(conId, "PRIVMSG", "NickServ", "IDENTIFY", profile.nickservPassword);
						for (String chan : profile.channels)
							send(conId, "JOIN", chan);
					}
				}
				break;
			}
			
			default:
				break;  // Ignore event
		}
	}
	
	
	// Must only be called by processEvent().
	private void processSend(Event ev, boolean realtime) {
		int conId = ev.connectionId;
		ConnectionState state = ircConnections.get(conId);  // Not null
		IrcNetwork profile = state.profile;
		IrcLine msg = new IrcLine(new String(ev.getLine(), OutputWriterThread.UTF8_CHARSET));
		switch (msg.command) {
			
			case "PONG": {
				String text = msg.parameters.get(0);
				if (state.queuedPongs != null && !state.queuedPongs.isEmpty() && state.queuedPongs.element().equals(text))
					state.queuedPongs.remove();
				break;
			}
			
			case "NICK": {
				if (state.registrationState == ConnectionState.RegState.OPENED) {
					state.registrationState = ConnectionState.RegState.NICK_SENT;
					if (realtime)
						send(conId, "USER", profile.username, "0", "*", profile.realname);
				}
				if (state.registrationState != ConnectionState.RegState.REGISTERED)
					state.currentNickname = msg.parameters.get(0);
				break;
			}
			
			case "USER": {
				if (state.registrationState == ConnectionState.RegState.NICK_SENT)
					state.registrationState = ConnectionState.RegState.USER_SENT;
				break;
			}
			
			case "PRIVMSG": {
				if (msg.parameters.size() == 3 && msg.parameters.get(0).equals("NickServ") && msg.parameters.get(1).equals("IDENTIFY"))
					state.sentNickservPassword = true;
				String src = state.currentNickname;
				String party = msg.parameters.get(0);
				String text = msg.parameters.get(1);
				String line = msg.command + " " + src + " " + text;
				databaseLogger.postMessage(conId, ev.sequence, ev.timestamp, profile.name, party, line);
				break;
			}
			
			default:
				break;  // Ignore event
		}
	}
	
	
	public synchronized void finishCatchup() {
		for (int conId : ircConnections.keySet()) {
			ConnectionState state = ircConnections.get(conId);
			while (!state.queuedPongs.isEmpty())
				send(conId, "PONG", state.queuedPongs.remove());
			state.queuedPongs = null;
			
			IrcNetwork profile = state.profile;
			switch (state.registrationState) {
				case CONNECTING:
					break;
				
				case OPENED: {
					send(conId, "NICK", profile.nicknames.get(0));
					break;
				}
					
				case NICK_SENT: {
					if (state.currentNickname != null)
						send(conId, "USER", profile.username, "0", "*", profile.realname);
					else {
						boolean found = false;
						for (String nickname : profile.nicknames) {
							if (!state.rejectedNicknames.contains(nickname)) {
								send(conId, "NICK", nickname);
								found = true;
								break;
							}
						}
						if (!found)
							writer.postWrite("disconnect " + conId);
					}
					break;
				}
					
				case USER_SENT:
					break;
					
				case REGISTERED: {
					if (profile.nickservPassword != null && !state.sentNickservPassword)
						send(conId, "PRIVMSG", "NickServ", "IDENTIFY", profile.nickservPassword);
					for (String chan : profile.channels) {
						if (!state.currentChannels.contains(chan))
							send(conId, "JOIN", chan);
					}
					break;
				}
				
				default:
					throw new AssertionError();
			}
		}
	}
	
	
	// Must be called from one of the synchronized methods above.
	private void send(int conId, String cmd, String... params) {
		StringBuilder sb = new StringBuilder("send ").append(conId).append(' ').append(cmd);
		for (int i = 0; i < params.length; i++) {
			sb.append(' ');
			if (i == params.length - 1)
				sb.append(':');
			sb.append(params[i]);
		}
		writer.postWrite(sb.toString());
	}
	
	
	public synchronized void databaseLoggerReady(DatabaseLoggerThread logger) {
		if (databaseLogger != null)
			throw new IllegalStateException();
		databaseLogger = logger;
		this.notify();
	}
	
	
	public synchronized void attachConnectorWriter(OutputWriterThread writer) {
		if (this.writer != null)
			throw new IllegalStateException();
		this.writer = writer;
	}
	
	
	public synchronized void terminate() {
		databaseLogger.terminate();
	}
	
	
	
	/*---- Nested classes ----*/
	
	private static final class ConnectionState {
		
		public IrcNetwork profile;
		public RegState registrationState;
		public Set<String> rejectedNicknames;
		public String currentNickname;
		public Set<String> currentChannels;
		
		// The fields below are only used when processing archived events
		// and during catch-up; they are unused during real-time processing.
		public Queue<String> queuedPongs;
		public boolean sentNickservPassword;
		
		
		public ConnectionState(IrcNetwork profile) {
			this.profile = profile;
			registrationState = RegState.CONNECTING;
			rejectedNicknames = new HashSet<>();
			currentNickname = null;
			currentChannels = new TreeSet<>();
			queuedPongs = new ArrayDeque<>();
			sentNickservPassword = false;
		}
		
		
		public enum RegState {
			CONNECTING, OPENED, NICK_SENT, USER_SENT, REGISTERED;
		}
		
	}
	
}
