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
import java.util.logging.Level;
import java.util.logging.Logger;
import io.nayuki.mamirc.common.CleanLine;
import io.nayuki.mamirc.common.ConnectorConfiguration;
import io.nayuki.mamirc.common.Event;
import io.nayuki.mamirc.common.OutputWriterThread;
import io.nayuki.mamirc.common.Utils;
import io.nayuki.mamirc.processor.IrcSession.RegState;
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
	private final Map<Integer,IrcSession> ircSessions;
	private final Map<String,Map<String,Window>> windows;
	private final Map<String,String> windowCaseMap;
	private final List<Object[]> recentUpdates;  // Payload is {int id, List<Object> update}
	private int nextUpdateId;
	private final Map<IrcNetwork,int[]> connectionAttemptState;  // Payload is {next server index, delay in milliseconds}
	
	
	
	/*---- Constructor ----*/
	
	public MamircProcessor(ConnectorConfiguration conConfig, ProcessorConfiguration procConfig) {
		if (conConfig == null || procConfig == null)
			throw new NullPointerException();
		myConfiguration = procConfig;
		
		ircSessions = new HashMap<>();
		windows = new TreeMap<>();
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
		IrcSession state = ircSessions.get(conId);  // Possibly null
		String line = Utils.fromUtf8(ev.line.getData());
		
		if (line.startsWith("connect ")) {
			String metadata = line.split(" ", 5)[4];
			if (!myConfiguration.ircNetworks.containsKey(metadata))
				throw new IllegalStateException("No profile: " + metadata);
			ircSessions.put(conId, new IrcSession(myConfiguration.ircNetworks.get(metadata)));
			
		} else if (line.startsWith("opened ")) {
			state.setRegistrationState(RegState.OPENED);
			if (realtime)
				sendIrcLine(conId, "NICK", state.profile.nicknames.get(0));
			addUpdate("CONNECTED", state.profile.name);
			
		} else if (line.equals("disconnect") || line.equals("closed")) {
			ircSessions.remove(conId);
			tryConnect(state.profile);
		}
	}
	
	
	// Must only be called by processEvent().
	private void processReceive(Event ev, boolean realtime) {
		int conId = ev.connectionId;
		IrcSession state = ircSessions.get(conId);  // Not null
		IrcNetwork profile = state.profile;
		IrcLine msg = new IrcLine(Utils.fromUtf8(ev.line.getData()));
		Map<String,IrcSession.ChannelState> curchans = state.getCurrentChannels();
		switch (msg.command.toUpperCase()) {
			
			case "NICK": {
				String fromname = msg.prefixName;
				String toname = msg.getParameter(0);
				if (fromname.equals(state.getCurrentNickname())) {
					state.setNickname(toname);
					addUpdate("MYNICK", state.profile.name, toname);
				}
				for (Map.Entry<String,IrcSession.ChannelState> entry : curchans.entrySet()) {
					Set<String> members = entry.getValue().members;
					if (members.remove(fromname)) {
						members.add(toname);
						String line = msg.command + " " + fromname + " " + toname;
						addMessage(profile.name, entry.getKey(), ev.timestamp, line, 0);
					}
				}
				break;
			}
			
			case "JOIN": {
				String who = msg.prefixName;
				String chan = msg.getParameter(0);
				if (who.equals(state.getCurrentNickname()) && !curchans.containsKey(chan)) {
					curchans.put(chan, new IrcSession.ChannelState());
					addUpdate("JOINED", state.profile.name, chan);
				}
				if (curchans.containsKey(chan) && curchans.get(chan).members.add(who)) {
					String line = msg.command + " " + who;
					addMessage(profile.name, msg.getParameter(0), ev.timestamp, line, 0);
				}
				break;
			}
			
			case "NOTICE": {
				String who = msg.prefixName;
				String target = msg.getParameter(0);
				if (target.equals(state.getCurrentNickname()))
					target = who;
				String line = msg.command + " " + who + " " + msg.getParameter(1);
				addMessage(profile.name, target, ev.timestamp, line, 0);
				break;
			}
			
			case "PART": {
				String who = msg.prefixName;
				String chan = msg.getParameter(0);
				if (curchans.containsKey(chan) && curchans.get(chan).members.remove(who)) {
					String line = msg.command + " " + who;
					addMessage(profile.name, chan, ev.timestamp, line, 0);
				}
				if (who.equals(state.getCurrentNickname())) {
					curchans.remove(chan);
					addUpdate("PARTED", state.profile.name, chan);
				}
				break;
			}
			
			case "KICK": {
				String reason = msg.getParameter(2);
				for (String chan : msg.getParameter(0).split(",")) {
					for (String target : msg.getParameter(1).split(",")) {
						if (curchans.containsKey(chan) && curchans.get(chan).members.remove(target)) {
							String line = msg.command + " " + target + " " + reason;
							addMessage(profile.name, chan, ev.timestamp, line, 0);
						}
						if (target.equals(state.getCurrentNickname())) {
							curchans.remove(chan);
							addUpdate("KICKED", state.profile.name, chan, reason);
						}
					}
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
				int flags = 0;
				if (state.getNickflagDetector().matcher(text).find())
					flags |= 1 << 1;
				addMessage(profile.name, target, ev.timestamp, line, flags);
				break;
			}
			
			case "QUIT": {
				String who = msg.prefixName;
				if (!who.equals(state.getCurrentNickname())) {
					for (Map.Entry<String,IrcSession.ChannelState> entry : curchans.entrySet()) {
						if (entry.getValue().members.remove(who)) {
							String line = msg.command + " " + who + " " + msg.getParameter(0);
							addMessage(profile.name, entry.getKey(), ev.timestamp, line, 0);
						}
					}
				} else {
					addUpdate("QUITTED", state.profile.name);
				}
				break;
			}
			
			case "TOPIC": {
				String who = msg.prefixName;
				String chan = msg.getParameter(0);
				String text = msg.getParameter(1);
				if (state.getCurrentChannels().containsKey(chan))
					state.getCurrentChannels().get(chan).topic = text;
				addMessage(profile.name, chan, ev.timestamp, "TOPIC " + who + " " + text, 0);
				break;
			}
			
			case "433": {  // ERR_NICKNAMEINUSE
				if (state.getRegistrationState() != RegState.REGISTERED) {
					state.moveNicknameToRejected();
					if (realtime) {
						boolean found = false;
						for (String nickname : profile.nicknames) {
							if (!state.getCurrentNickname().contains(nickname)) {
								sendIrcLine(conId, "NICK", nickname);
								found = true;
								break;
							}
						}
						if (!found)
							writer.postWrite("disconnect " + conId);
					} else
						state.setNickname(null);
				}
				break;
			}
			
			case "001":  // RPL_WELCOME and various welcome messages
			case "002":
			case "003":
			case "004":
			case "005": {
				if (state.getRegistrationState() != RegState.REGISTERED) {
					state.setRegistrationState(RegState.REGISTERED);
					if (realtime) {
						if (profile.nickservPassword != null && !state.getSentNickservPassword())
							sendIrcLine(conId, "PRIVMSG", "NickServ", "IDENTIFY " + profile.nickservPassword);
						for (String chan : profile.channels)
							sendIrcLine(conId, "JOIN", chan);
					}
					addUpdate("MYNICK", profile.name, state.getCurrentNickname());
					connectionAttemptState.remove(state.profile);
				}
				break;
			}
			
			case "331": {  // RPL_NOTOPIC
				String chan = msg.getParameter(1);
				if (state.getCurrentChannels().containsKey(chan))
					state.getCurrentChannels().get(chan).topic = null;
				addMessage(profile.name, chan, ev.timestamp, "INITNOTOPIC", 0);
				break;
			}
			
			case "332": {  // RPL_TOPIC
				String chan = msg.getParameter(1);
				String text = msg.getParameter(2);
				if (state.getCurrentChannels().containsKey(chan))
					state.getCurrentChannels().get(chan).topic = text;
				addMessage(profile.name, chan, ev.timestamp, "INITTOPIC " + text, 0);
				break;
			}
			
			case "353": {  // RPL_NAMREPLY
				String chan = msg.getParameter(2);
				if (curchans.containsKey(chan)) {
					IrcSession.ChannelState chanstate = curchans.get(chan);
					if (!chanstate.processingNamesReply) {
						chanstate.members.clear();
						chanstate.processingNamesReply = true;
					}
					for (String name : msg.getParameter(3).split(" ")) {
						if (name.startsWith("@") || name.startsWith("+") || name.startsWith("!") || name.startsWith("%"))
							name = name.substring(1);
						chanstate.members.add(name);
					}
					addUpdate("SETCHANNELMEMBERS", profile.name, chan, new ArrayList<>(chanstate.members));
				}
				break;
			}
			
			case "366": {  // RPL_ENDOFNAMES
				for (IrcSession.ChannelState chanstate : curchans.values())
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
		IrcSession state = ircSessions.get(conId);  // Not null
		IrcNetwork profile = state.profile;
		IrcLine msg = new IrcLine(Utils.fromUtf8(ev.line.getData()));
		switch (msg.command.toUpperCase()) {
			
			case "NICK": {
				if (state.getRegistrationState() == RegState.OPENED) {
					state.setRegistrationState(RegState.NICK_SENT);
					if (realtime)
						sendIrcLine(conId, "USER", profile.username, "0", "*", profile.realname);
				}
				if (state.getRegistrationState() != RegState.REGISTERED)
					state.setNickname(msg.getParameter(0));
				// Otherwise when registered, rely on receiving NICK from the server
				break;
			}
			
			case "USER": {
				if (state.getRegistrationState() == RegState.NICK_SENT)
					state.setRegistrationState(RegState.USER_SENT);
				break;
			}
			
			case "NOTICE": {
				String src = state.getCurrentNickname();
				String party = msg.getParameter(0);
				String text = msg.getParameter(1);
				String line = msg.command + " " + src + " " + text;
				addMessage(profile.name, party, ev.timestamp, line, 0);
				break;
			}
			
			case "PRIVMSG": {
				if (msg.parameters.size() == 2 && msg.getParameter(0).equals("NickServ") && msg.getParameter(1).toUpperCase().startsWith("IDENTIFY "))
					state.setSentNickservPassword();
				String src = state.getCurrentNickname();
				String party = msg.getParameter(0);
				String text = msg.getParameter(1);
				String line = msg.command + " " + src + " " + text;
				addMessage(profile.name, party, ev.timestamp, line, 1 << 0);
				break;
			}
			
			default:
				break;  // Ignore event
		}
	}
	
	
	// Must only be called from ConnectorReaderThread, and only called once.
	public synchronized void finishCatchup() {
		Set<IrcNetwork> activeProfiles = new HashSet<>();
		for (int conId : ircSessions.keySet()) {
			IrcSession state = ircSessions.get(conId);
			IrcNetwork profile = state.profile;
			if (!activeProfiles.add(profile))
				throw new IllegalStateException("Multiple active connections for profile: " + profile.name);
			switch (state.getRegistrationState()) {
				case CONNECTING:
					break;
				
				case OPENED: {
					sendIrcLine(conId, "NICK", profile.nicknames.get(0));
					break;
				}
				
				case NICK_SENT:
				case USER_SENT: {
					if (state.getCurrentNickname() == null) {
						boolean found = false;
						for (String nickname : profile.nicknames) {
							if (!state.isNicknameRejected(nickname)) {
								sendIrcLine(conId, "NICK", nickname);
								found = true;
								break;
							}
						}
						if (!found)
							writer.postWrite("disconnect " + conId);
					} else if (state.getRegistrationState() == RegState.NICK_SENT)
						sendIrcLine(conId, "USER", profile.username, "0", "*", profile.realname);
					break;
				}
				
				case REGISTERED: {
					if (profile.nickservPassword != null && !state.getSentNickservPassword())
						sendIrcLine(conId, "PRIVMSG", "NickServ", "IDENTIFY " + profile.nickservPassword);
					for (String chan : profile.channels) {
						if (!state.getCurrentChannels().containsKey(chan))
							sendIrcLine(conId, "JOIN", chan);
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
					for (IrcSession state : ircSessions.values()) {
						if (state.profile == net)
							break;
					}
					
					int[] attemptState = connectionAttemptState.get(net);
					IrcNetwork.Server serv = net.servers.get(attemptState[0]);
					String str = "connect " + serv.hostnamePort.getHostString() + " " + serv.hostnamePort.getPort() + " " + serv.useSsl + " " + net.name;
					writer.postWrite(new CleanLine(str));
					
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
	
	
	// Must be called from one of the synchronized methods.
	private void sendIrcLine(int conId, String cmd, String... params) {
		StringBuilder sb = new StringBuilder("send ").append(conId).append(' ').append(cmd);
		for (int i = 0; i < params.length; i++) {
			sb.append(' ');
			if (i == params.length - 1)
				sb.append(':');
			sb.append(params[i]);
		}
		writer.postWrite(sb.toString());
	}
	
	
	// Must only be called from ConnectorReaderThread, and only called once.
	public synchronized void attachConnectorWriter(OutputWriterThread writer) {
		if (this.writer != null)
			throw new IllegalStateException();
		this.writer = writer;
	}
	
	
	public synchronized void terminate() {
		if (server != null)
			server.terminate();
	}
	
	
	// Must be called from one of the synchronized methods.
	private void addUpdate(Object... update) {
		if (update == null)
			throw new NullPointerException();
		
		// Store the update
		recentUpdates.add(new Object[]{nextUpdateId, Arrays.asList(update)});
		nextUpdateId++;
		
		// Clean up the list if it gets too big
		if (recentUpdates.size() > 1000)
			recentUpdates.subList(0, recentUpdates.size() / 2).clear();
		
		// Unblock any currently waiting server threads
		this.notifyAll();
	}
	
	
	// Must be called from one of the synchronized methods above.
	private void addMessage(String profile, String target, long timestamp, String line, int flags) {
		if (!windows.containsKey(profile))
			windows.put(profile, new TreeMap<String,Window>());
		Map<String,Window> innerMap = windows.get(profile);
		if (!innerMap.containsKey(target)) {
			String lower = profile + "\n" + target.toLowerCase();
			if (windowCaseMap.containsKey(lower))
				target = windowCaseMap.get(lower).split("\n", 2)[1];
			else {
				innerMap.put(target, new Window());
				windowCaseMap.put(lower, profile + "\n" + target);
			}
		}
		Window win = innerMap.get(target);
		int sequence = win.nextSequence;
		win.addLine(timestamp, line, flags);
		List<Window.Line> list = win.lines;
		if (list.size() - 100 >= 10000)
			list.subList(0, 100).clear();
		addUpdate("APPEND", profile, target, sequence, timestamp, line, flags);
	}
	
	
	/*---- HTTP web API ----*/
	
	// The methods below should only be called from MessageHttpServer.
	
	public synchronized Map<String,Object> getState() {
		Map<String,Object> result = new HashMap<>();
		
		// States of current connections
		Map<String,Map<String,Object>> outConnections = new HashMap<>();
		for (Map.Entry<Integer,IrcSession> conEntry : ircSessions.entrySet()) {
			IrcSession inConState = conEntry.getValue();
			Map<String,Object> outConState = new HashMap<>();
			outConState.put("currentNickname", inConState.getCurrentNickname());
			
			Map<String,IrcSession.ChannelState> inChannels = inConState.getCurrentChannels();
			Map<String,Map<String,Object>> outChannels = new HashMap<>();
			for (Map.Entry<String,IrcSession.ChannelState> chanEntry : inChannels.entrySet()) {
				Map<String,Object> outChanState = new HashMap<>();
				outChanState.put("members", new ArrayList<>(chanEntry.getValue().members));
				outChanState.put("topic", chanEntry.getValue().topic);
				outChannels.put(chanEntry.getKey(), outChanState);
			}
			outConState.put("channels", outChannels);
			
			outConnections.put(inConState.profile.name, outConState);
		}
		result.put("connections", outConnections);
		
		// States of current windows
		List<List<Object>> outWindows = new ArrayList<>();
		for (Map.Entry<String,Map<String,Window>> profileEntry : windows.entrySet()) {
			for (Map.Entry<String,Window> targetEntry : profileEntry.getValue().entrySet()) {
				List<Object> outWindow = new ArrayList<>();
				outWindow.add(profileEntry.getKey());
				outWindow.add(targetEntry.getKey());
				
				Window inWindow = targetEntry.getValue();
				List<List<Object>> outLines = new ArrayList<>();
				for (Window.Line line : inWindow.lines)
					outLines.add(Arrays.<Object>asList(line.sequence, line.timestamp, line.payload, line.flags));
				
				Map<String,Object> outWinState = new HashMap<>();
				outWinState.put("lines", outLines);
				outWinState.put("markedReadUntil", inWindow.markedReadUntil);
				outWindow.add(outWinState);
				outWindows.add(outWindow);
			}
		}
		result.put("windows", outWindows);
		
		// Miscellaneous
		result.put("nextUpdateId", nextUpdateId);
		return result;
	}
	
	
	// Returns a JSON object containing updates with id >= startId (the list might be empty),
	// or null to indicate that the request is invalid and the client must request the full state.
	@SuppressWarnings("unchecked")
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
			List<List<Object>> updates = new ArrayList<>();
			while (i < recentUpdates.size()) {
				updates.add((List<Object>)recentUpdates.get(i)[1]);
				i++;
			}
			result.put("updates", updates);
			result.put("nextUpdateId", nextUpdateId);
			return result;
		}
	}
	
	
	public synchronized boolean sendLine(String profile, String line) {
		for (Map.Entry<Integer,IrcSession> entry : ircSessions.entrySet()) {
			if (entry.getValue().profile.name.equals(profile)) {
				writer.postWrite("send " + entry.getKey() + " " + line);
				return true;
			}
		}
		return false;
	}
	
	
	public synchronized void markRead(String profile, String party, int sequence) {
		windows.get(profile).get(party).markedReadUntil = sequence;
		addUpdate("MARKREAD", profile, party, sequence);
	}
	
	
	public synchronized void clearLines(String profile, String party, int sequence) {
		windows.get(profile).get(party).clearUntil(sequence);
		addUpdate("CLEARLINES", profile, party, sequence);
	}
	
	
	public synchronized void openWindow(String profile, String party) {
		String lower = profile + "\n" + party.toLowerCase();
		if (windowCaseMap.containsKey(lower))
			return;
		if (!windows.containsKey(profile))
			windows.put(profile, new TreeMap<String,Window>());
		Map<String,Window> inner = windows.get(profile);
		inner.put(party, new Window());
		windowCaseMap.put(lower, profile + "\n" + party);
		addUpdate("OPENWIN", profile, party);
	}
	
	
	public synchronized void closeWindow(String profile, String party) {
		Map<String,Window> inner = windows.get(profile);
		if (inner != null && inner.remove(party) != null && windowCaseMap.remove(profile + "\n" + party.toLowerCase()) != null)
			addUpdate("CLOSEWIN", profile, party);
	}
	
}
