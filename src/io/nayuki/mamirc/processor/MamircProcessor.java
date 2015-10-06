package io.nayuki.mamirc.processor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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
import io.nayuki.mamirc.common.Utils;
import io.nayuki.mamirc.processor.ProcessorConfiguration.IrcNetwork;


public final class MamircProcessor {
	
	/*---- Stub main program ----*/
	
	public static void main(String[] args) throws IOException {
		if (args.length != 2) {
			System.err.println("Usage: java io/nayuki/mamirc/processor/MamircProcessor connector.ini processor.ini");
			System.exit(1);
		}
		
		Logger.getLogger("com.almworks.sqlite4java").setLevel(Level.OFF);  // Prevent sqlite4java module from polluting stderr with debug messages
		new MamircProcessor(
			new ConnectorConfiguration(new File(args[0])),
			new ProcessorConfiguration(new File(args[1])));
		// The main thread returns, while other threads live on
	}
	
	
	
	/*---- Fields (global state) ----*/
	
	// Immutable
	private final ProcessorConfiguration myConfiguration;
	
	// Current workers
	private ConnectorReaderThread reader;
	private OutputWriterThread writer;
	private MessageHttpServer server;
	
	// Mutable current state
	private final Map<Integer,ConnectionState> ircConnections;
	private final Map<String,Map<String,List<Object[]>>> windowMessages;  // Payload is {long timestamp, String message}
	private final Map<String,String> windowCaseMap;
	private final List<Object[]> recentUpdates;  // Payload is {int id, String update}
	private int nextUpdateId;
	private final Map<IrcNetwork,int[]> connectionAttemptState;  // Payload is {next server index, delay in milliseconds}
	
	
	
	/*---- Constructor ----*/
	
	public MamircProcessor(ConnectorConfiguration conConfig, ProcessorConfiguration procConfig) {
		if (conConfig == null || procConfig == null)
			throw new NullPointerException();
		myConfiguration = procConfig;
		
		ircConnections = new HashMap<>();
		windowMessages = new TreeMap<>();
		windowCaseMap = new HashMap<>();
		recentUpdates = new ArrayList<>();
		nextUpdateId = 0;
		connectionAttemptState = new HashMap<>();
		
		writer = null;
		reader = new ConnectorReaderThread(this, conConfig);
		reader.start();
		try {
			server = new MessageHttpServer(this, procConfig.webServerPort, procConfig.webUiPassword);
		} catch (IOException e) {
			e.printStackTrace();
			terminate();
		}
	}
	
	
	/*---- Methods for manipulating global state ----*/
	
	public synchronized void processEvent(Event ev, boolean realtime) {
		if (ev == null)
			throw new NullPointerException();
		try {
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
		} catch (IrcSyntaxException e) {
			e.printStackTrace();
		}
	}
	
	
	// Must only be called by processEvent().
	private void processConnection(Event ev, boolean realtime) {
		int conId = ev.connectionId;
		ConnectionState state = ircConnections.get(conId);  // Possibly null
		String line = Utils.fromUtf8(ev.getLine());
		
		if (line.startsWith("connect ")) {
			String metadata = line.split(" ", 5)[4];
			if (!myConfiguration.ircNetworks.containsKey(metadata))
				throw new IllegalStateException("No profile: " + metadata);
			ircConnections.put(conId, new ConnectionState(myConfiguration.ircNetworks.get(metadata)));
			
		} else if (line.startsWith("opened ")) {
			state.registrationState = ConnectionState.RegState.OPENED;
			if (realtime)
				send(conId, "NICK", state.profile.nicknames.get(0));
			addUpdate("CONNECTED\n" + state.profile.name);
			
		} else if (line.equals("disconnect") || line.equals("closed")) {
			ircConnections.remove(conId);
			tryConnect(state.profile);
		}
	}
	
	
	// Must only be called by processEvent().
	private void processReceive(Event ev, boolean realtime) {
		int conId = ev.connectionId;
		ConnectionState state = ircConnections.get(conId);  // Not null
		IrcNetwork profile = state.profile;
		IrcLine msg = new IrcLine(Utils.fromUtf8(ev.getLine()));
		Map<String,ConnectionState.ChannelState> curchans = state.currentChannels;
		switch (msg.command.toUpperCase()) {
			
			case "NICK": {
				String fromname = msg.prefixName;
				String toname = msg.getParameter(0);
				if (fromname.equals(state.currentNickname)) {
					state.currentNickname = toname;
					addUpdate("MYNICK\n" + state.profile.name + "\n" + toname);
				}
				for (Map.Entry<String,ConnectionState.ChannelState> entry : curchans.entrySet()) {
					Set<String> members = entry.getValue().members;
					if (members.remove(fromname)) {
						members.add(toname);
						String line = msg.command + " " + fromname + " " + toname;
						addMessage(profile.name, entry.getKey(), ev.timestamp, line);
					}
				}
				break;
			}
			
			case "JOIN": {
				String who = msg.prefixName;
				String chan = msg.getParameter(0);
				if (who.equals(state.currentNickname) && !curchans.containsKey(chan)) {
					curchans.put(chan, new ConnectionState.ChannelState());
					addUpdate("JOINED\n" + state.profile.name + "\n" + chan);
				}
				if (curchans.containsKey(chan) && curchans.get(chan).members.add(who)) {
					String line = msg.command + " " + who;
					addMessage(profile.name, msg.getParameter(0), ev.timestamp, line);
				}
				break;
			}
			
			case "NOTICE": {
				String who = msg.prefixName;
				String target = msg.getParameter(0);
				if (target.equals(state.currentNickname))
					target = who;
				String line = msg.command + " " + who + " " + msg.getParameter(1);
				addMessage(profile.name, target, ev.timestamp, line);
				break;
			}
			
			case "PART": {
				String who = msg.prefixName;
				String chan = msg.getParameter(0);
				if (curchans.containsKey(chan) && curchans.get(chan).members.remove(who)) {
					String line = msg.command + " " + who;
					addMessage(profile.name, chan, ev.timestamp, line);
				}
				if (who.equals(state.currentNickname)) {
					curchans.remove(chan);
					addUpdate("PARTED\n" + state.profile.name + "\n" + chan);
				}
				break;
			}
			
			case "PRIVMSG": {
				String who = msg.prefixName;
				String target = msg.getParameter(0);
				if (target.charAt(0) != '#' && target.charAt(0) != '&')  // Not a channel, and is therefore a private message to me
					target = who;
				String text = msg.getParameter(1);
				String line = msg.command + " " + who + " " + text;
				addMessage(profile.name, target, ev.timestamp, line);
				break;
			}
			
			case "QUIT": {
				String who = msg.prefixName;
				if (!who.equals(state.currentNickname)) {
					for (Map.Entry<String,ConnectionState.ChannelState> entry : curchans.entrySet()) {
						if (entry.getValue().members.remove(who)) {
							String line = msg.command + " " + who + " " + msg.getParameter(0);
							addMessage(profile.name, entry.getKey(), ev.timestamp, line);
						}
					}
				} else {
					addUpdate("QUITTED\n" + state.profile.name);
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
							send(conId, "PRIVMSG", "NickServ", "IDENTIFY " + profile.nickservPassword);
						for (String chan : profile.channels)
							send(conId, "JOIN", chan);
					}
					addUpdate("MYNICK\n" + profile.name + "\n" + state.currentNickname);
					connectionAttemptState.remove(state.profile);
				}
				break;
			}
			
			case "353": {  // RPL_NAMREPLY
				String chan = msg.getParameter(2);
				if (curchans.containsKey(chan)) {
					ConnectionState.ChannelState chanstate = curchans.get(chan);
					if (!chanstate.processingNamesReply) {
						chanstate.members.clear();
						chanstate.processingNamesReply = true;
					}
					for (String name : msg.getParameter(3).split(" ")) {
						if (name.startsWith("@") || name.startsWith("+"))
							name = name.substring(1);
						chanstate.members.add(name);
					}
					StringBuilder sb = new StringBuilder();
					for (String name : chanstate.members) {
						if (sb.length() > 0)
							sb.append(" ");
						sb.append(name);
					}
					addUpdate("SETCHANNELMEMBERS\n" + profile.name + "\n" + sb.toString());
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
		IrcLine msg = new IrcLine(Utils.fromUtf8(ev.getLine()));
		switch (msg.command.toUpperCase()) {
			
			case "NICK": {
				if (state.registrationState == ConnectionState.RegState.OPENED) {
					state.registrationState = ConnectionState.RegState.NICK_SENT;
					if (realtime)
						send(conId, "USER", profile.username, "0", "*", profile.realname);
				}
				if (state.registrationState != ConnectionState.RegState.REGISTERED)
					state.currentNickname = msg.getParameter(0);
				// Otherwise when registered, rely on receiving NICK from the server
				break;
			}
			
			case "USER": {
				if (state.registrationState == ConnectionState.RegState.NICK_SENT)
					state.registrationState = ConnectionState.RegState.USER_SENT;
				break;
			}
			
			case "NOTICE": {
				String src = state.currentNickname;
				String party = msg.getParameter(0);
				String text = msg.getParameter(1);
				String line = msg.command + " " + src + " " + text;
				addMessage(profile.name, party, ev.timestamp, line);
				break;
			}
			
			case "PRIVMSG": {
				if (msg.parameters.size() == 3 && msg.getParameter(0).equals("NickServ") && msg.getParameter(1).toUpperCase().startsWith("IDENTIFY "))
					state.sentNickservPassword = true;
				String src = state.currentNickname;
				String party = msg.getParameter(0);
				String text = msg.getParameter(1);
				String line = msg.command + " " + src + " " + text;
				addMessage(profile.name, party, ev.timestamp, line);
				break;
			}
			
			default:
				break;  // Ignore event
		}
	}
	
	
	// Must only be called from ConnectorReaderThread, and only called once.
	public synchronized void finishCatchup() {
		Set<IrcNetwork> activeProfiles = new HashSet<>();
		for (int conId : ircConnections.keySet()) {
			ConnectionState state = ircConnections.get(conId);
			IrcNetwork profile = state.profile;
			if (!activeProfiles.add(profile))
				throw new IllegalStateException("Multiple active connections for profile: " + profile.name);
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
						send(conId, "PRIVMSG", "NickServ", "IDENTIFY " + profile.nickservPassword);
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
		
		// Connect to networks
		for (IrcNetwork net : myConfiguration.ircNetworks.values()) {
			if (!activeProfiles.contains(net))
				tryConnect(net);
		}
	}
	
	
	// Must be called from one of the synchronized methods above.
	private void tryConnect(final IrcNetwork net) {
		final int delay;
		if (!connectionAttemptState.containsKey(net)) {
			connectionAttemptState.put(net, new int[]{0, 1000});
			delay = 0;
		} else
			delay = connectionAttemptState.get(net)[1];
		
		new Thread() {
			public void run() {
				try {
					Thread.sleep(delay);
				} catch (InterruptedException e) {}
				
				synchronized(MamircProcessor.this) {
					for (ConnectionState state : ircConnections.values()) {
						if (state.profile == net)
							break;
					}
					
					int[] attemptState = connectionAttemptState.get(net);
					IrcNetwork.Server serv = net.servers.get(attemptState[0]);
					String str = "connect " + serv.hostnamePort.getHostString() + " " + serv.hostnamePort.getPort() + " " + serv.useSsl + " " + net.name;
					writer.postWrite(Utils.toUtf8(str));
					
					if (1000 < attemptState[1] && attemptState[1] < 200000)
						attemptState[1] *= 2;  // Exponential backoff
					attemptState[0]++;
					if (attemptState[0] == net.servers.size()) {
						attemptState[0] = 0;
						if (attemptState[1] == 1000)
							attemptState[1] *= 2;
					}
				}
			}
		}.start();
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
	
	
	public synchronized boolean sendMessage(String profile, String party, String line) {
		for (Map.Entry<Integer,ConnectionState> entry : ircConnections.entrySet()) {
			if (entry.getValue().profile.name.equals(profile)) {
				send(entry.getKey(), "PRIVMSG", party, line);
				return true;
			}
		}
		return false;
	}
	
	
	public synchronized void attachConnectorWriter(OutputWriterThread writer) {
		if (this.writer != null)
			throw new IllegalStateException();
		this.writer = writer;
	}
	
	
	public synchronized void terminate() {
		if (server != null)
			server.terminate();
	}
	
	
	// Must be called from one of the synchronized methods above.
	private void addUpdate(String update) {
		if (update == null)
			throw new NullPointerException();
		
		// Store the update
		recentUpdates.add(new Object[]{nextUpdateId, update});
		nextUpdateId++;
		
		// Clean up the list if it gets too big
		if (recentUpdates.size() > 1000)
			recentUpdates.subList(0, recentUpdates.size() / 2).clear();
		
		// Unblock any currently waiting server threads
		this.notifyAll();
	}
	
	
	// Must be called from one of the synchronized methods above.
	private void addMessage(String profile, String target, long timestamp, String line) {
		if (!windowMessages.containsKey(profile))
			windowMessages.put(profile, new TreeMap<String,List<Object[]>>());
		Map<String,List<Object[]>> innerMap = windowMessages.get(profile);
		if (!innerMap.containsKey(target)) {
			String lower = profile + "\n" + target.toLowerCase();
			if (windowCaseMap.containsKey(lower))
				target = windowCaseMap.get(lower).split("\n", 2)[1];
			else {
				innerMap.put(target, new ArrayList<Object[]>());
				windowCaseMap.put(lower, profile + "\n" + target);
			}
		}
		List<Object[]> list = innerMap.get(target);
		list.add(new Object[]{timestamp, line});
		if (list.size() - 100 >= 10000)
			list.subList(0, 100).clear();
		addUpdate("APPEND\n" + profile + "\n" + target + "\n" + timestamp + "\n" + line);
	}
	
	
	public synchronized Map<String,Object> getState() {
		Map<String,Object> result = new HashMap<>();
		
		// States of current connections
		Map<String,Map<String,Object>> outConnections = new HashMap<>();
		for (Map.Entry<Integer,ConnectionState> conEntry : ircConnections.entrySet()) {
			ConnectionState inConState = conEntry.getValue();
			Map<String,Object> outConState = new HashMap<>();
			outConState.put("currentNickname", inConState.currentNickname);
			
			Map<String,ConnectionState.ChannelState> inChannels = inConState.currentChannels;
			Map<String,Map<String,Object>> outChannels = new HashMap<>();
			for (Map.Entry<String,ConnectionState.ChannelState> chanEntry : inChannels.entrySet()) {
				Map<String,Object> outChanState = new HashMap<>();
				outChanState.put("members", new ArrayList<>(chanEntry.getValue().members));
				outChannels.put(chanEntry.getKey(), outChanState);
			}
			outConState.put("channels", outChannels);
			
			outConnections.put(inConState.profile.name, outConState);
		}
		result.put("connections", outConnections);
		
		// Messages in current windows
		Map<String,Map<String,List<List<Object>>>> outMessages = new HashMap<>();
		for (Map.Entry<String,Map<String,List<Object[]>>> profileEntry : windowMessages.entrySet()) {
			Map<String,List<List<Object>>> outSuperMsgs = new HashMap<>();
			for (Map.Entry<String,List<Object[]>> targetEntry : profileEntry.getValue().entrySet()) {
				List<List<Object>> outMsgs = new ArrayList<>();
				for (Object[] msg : targetEntry.getValue())
					outMsgs.add(Arrays.asList(msg));
				outSuperMsgs.put(targetEntry.getKey(), outMsgs);
			}
			outMessages.put(profileEntry.getKey(), outSuperMsgs);
		}
		result.put("messages", outMessages);
		
		result.put("nextUpdateId", nextUpdateId);
		return result;
	}
	
	
	// Returns a JSON object containing updates with id >= startId (the list might be empty),
	// or null to indicate that the request is invalid and the client must request the full state.
	public synchronized Map<String,Object> getUpdates(int startId) {
		if (startId < 0 || startId > nextUpdateId)
			return null;
		
		int i = recentUpdates.size();
		while (i >= 1 && (Integer)recentUpdates.get(i - 1)[0] >= startId)
			i--;
		
		if (i == 0)
			return null;  // No overlap
		else {
			Map<String,Object> result = new HashMap<>();
			List<String> updates = new ArrayList<>();
			while (i < recentUpdates.size()) {
				updates.add((String)recentUpdates.get(i)[1]);
				i++;
			}
			result.put("updates", updates);
			result.put("nextUpdateId", nextUpdateId);
			return result;
		}
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
			currentChannels = new CaseInsensitiveTreeMap<>();
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
