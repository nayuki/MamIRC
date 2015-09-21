package io.nayuki.mamirc.processor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
	
	// Immutable
	private final ProcessorConfiguration myConfiguration;
	
	// Current worker threads
	private DatabaseLoggerThread databaseLogger;
	private ConnectorReaderThread reader;
	private OutputWriterThread writer;
	
	private MessageHttpServer server;
	
	// Mutable current state
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
			// That thread will call this.databaseLoggerReady()
			while (databaseLogger == null) {
				try {
					this.wait();
				} catch (InterruptedException e) {}
			}
		}
		
		reader = new ConnectorReaderThread(this, conConfig);
		reader.start();
		try {
			server = new MessageHttpServer(this);
			server.server.start();
		} catch (IOException e) {
			e.printStackTrace();
			terminate();
		}
	}
	
	
	/*---- Methods for manipulating global state ----*/
	
	public synchronized void processEvent(Event ev, boolean realtime) {
		if (ev == null)
			throw new NullPointerException();
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
		List<String> params = msg.parameters;
		Map<String,ConnectionState.ChannelState> curchans = state.currentChannels;
		switch (msg.command) {
			
			case "NICK": {
				String fromname = msg.prefixName;
				String toname = params.get(0);
				if (fromname.equals(state.currentNickname))
					state.currentNickname = toname;
				for (Map.Entry<String,ConnectionState.ChannelState> entry : curchans.entrySet()) {
					Set<String> members = entry.getValue().members;
					if (members.remove(fromname)) {
						members.add(toname);
						String line = msg.command + " " + fromname + " " + toname;
						postMessage(conId, ev.sequence, ev.timestamp, profile.name, entry.getKey(), line);
					}
				}
				break;
			}
			
			case "JOIN": {
				String who = msg.prefixName;
				String chan = params.get(0);
				if (who.equals(state.currentNickname) && !curchans.containsKey(chan))
					curchans.put(chan, new ConnectionState.ChannelState());
				if (curchans.containsKey(chan) && curchans.get(chan).members.add(who)) {
					String line = msg.command + " " + who;
					postMessage(conId, ev.sequence, ev.timestamp, profile.name, params.get(0), line);
				}
				break;
			}
			
			case "PART": {
				String who = msg.prefixName;
				String chan = params.get(0);
				if (curchans.containsKey(chan) && curchans.get(chan).members.remove(who)) {
					String line = msg.command + " " + who;
					postMessage(conId, ev.sequence, ev.timestamp, profile.name, chan, line);
				}
				if (who.equals(state.currentNickname))
					curchans.remove(chan);
				break;
			}
			
			case "PRIVMSG": {
				String who = msg.prefixName;
				String target = params.get(0);
				if (target.charAt(0) != '#' && target.charAt(0) != '&')  // Not a channel, and is therefore a private message to me
					target = who;
				String text = params.get(1);
				String line = msg.command + " " + who + " " + text;
				postMessage(conId, ev.sequence, ev.timestamp, profile.name, target, line);
				break;
			}
			
			case "QUIT": {
				String who = msg.prefixName;
				if (!who.equals(state.currentNickname)) {
					for (Map.Entry<String,ConnectionState.ChannelState> entry : curchans.entrySet()) {
						if (entry.getValue().members.remove(who)) {
							String line = msg.command + " " + who + " " + params.get(0);
							postMessage(conId, ev.sequence, ev.timestamp, profile.name, entry.getKey(), line);
						}
					}
				}
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
			
			case "353": {  // RPL_NAMREPLY
				String chan = params.get(2);
				if (curchans.containsKey(chan)) {
					ConnectionState.ChannelState chanstate = curchans.get(chan);
					if (!chanstate.processingNamesReply) {
						chanstate.members.clear();
						chanstate.processingNamesReply = true;
					}
					for (String name : params.get(3).split(" ")) {
						if (name.startsWith("@") || name.startsWith("+"))
							name = name.substring(1);
						chanstate.members.add(name);
					}
				}
				break;
			}
			
			case "366": {  // RPL_ENDOFNAMES
				for (ConnectionState.ChannelState chanstate : curchans.values())
					chanstate.processingNamesReply = false;
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
			
			case "NICK": {
				if (state.registrationState == ConnectionState.RegState.OPENED) {
					state.registrationState = ConnectionState.RegState.NICK_SENT;
					if (realtime)
						send(conId, "USER", profile.username, "0", "*", profile.realname);
				}
				if (state.registrationState != ConnectionState.RegState.REGISTERED)
					state.currentNickname = msg.parameters.get(0);
				// Otherwise when registered, rely on receiving NICK from the server
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
				postMessage(conId, ev.sequence, ev.timestamp, profile.name, party, line);
				break;
			}
			
			default:
				break;  // Ignore event
		}
	}
	
	
	public synchronized void finishCatchup() {
		for (int conId : ircConnections.keySet()) {
			ConnectionState state = ircConnections.get(conId);
			IrcNetwork profile = state.profile;
			switch (state.registrationState) {
				case CONNECTING:
					break;
				
				case OPENED: {
					send(conId, "NICK", profile.nicknames.get(0));
					break;
				}
				
				case NICK_SENT:
				case USER_SENT: {
					if (state.currentNickname == null) {
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
					} else if (state.registrationState == ConnectionState.RegState.NICK_SENT)
						send(conId, "USER", profile.username, "0", "*", profile.realname);
					break;
				}
				
				case REGISTERED: {
					if (profile.nickservPassword != null && !state.sentNickservPassword)
						send(conId, "PRIVMSG", "NickServ", "IDENTIFY", profile.nickservPassword);
					for (String chan : profile.channels) {
						if (!state.currentChannels.containsKey(chan))
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
	
	
	// Must be called from one of the synchronized methods above.
	private void postMessage(int connectionId, int sequence, long timestamp, String profile, String party, String line) {
		databaseLogger.postMessage(connectionId, sequence, timestamp, profile, party, line);
		if (server != null) {
			synchronized(server) {
				server.notifyAll();
			}
		}
	}
	
	
	public synchronized Map<Object[],List<String>> getActiveChannels() {
		Map<Object[],List<String>> result = new HashMap<>();
		for (Map.Entry<Integer,ConnectionState> entry : ircConnections.entrySet()) {
			Object[] key = {entry.getKey(), entry.getValue().profile.name};
			result.put(key, new ArrayList<>(entry.getValue().currentChannels.keySet()));
		}
		return result;
	}
	
	
	public synchronized boolean sendMessage(String profile, String party, String line) {
		for (Map.Entry<Integer,ConnectionState> entry : ircConnections.entrySet()) {
			if (entry.getValue().profile.name.equals(profile)) {
				send(entry.getKey(), "PRIVMSG", party, line);
				return true;
			}
		}
		return false;
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
		writer.terminate();
		databaseLogger.terminate();
		if (server != null)
			server.server.stop(0);
	}
	
	
	
	/*---- Nested classes ----*/
	
	private static final class ConnectionState {
		public IrcNetwork profile;             // Not null
		public RegState registrationState;     // Not null
		public Set<String> rejectedNicknames;  // Not null before successful registration, null thereafter
		public String currentNickname;         // Can be null
		public Map<String,ChannelState> currentChannels;  // Not null, size at least 0
		
		// The fields below are only used when processing archived events
		// and during catch-up; they are unused during real-time processing.
		public boolean sentNickservPassword;
		
		
		public ConnectionState(IrcNetwork profile) {
			this.profile = profile;
			registrationState = RegState.CONNECTING;
			rejectedNicknames = new HashSet<>();
			currentNickname = null;
			currentChannels = new TreeMap<>();
			sentNickservPassword = false;
		}
		
		
		public static final class ChannelState {
			public final Set<String> members;  // Not null, size at least 0
			public boolean processingNamesReply;
			
			public ChannelState() {
				members = new TreeSet<>();
				processingNamesReply = false;
			}
		}
		
		
		public enum RegState {
			CONNECTING, OPENED, NICK_SENT, USER_SENT, REGISTERED;
		}
		
	}
	
}
