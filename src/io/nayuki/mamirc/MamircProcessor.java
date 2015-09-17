package io.nayuki.mamirc;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;
import io.nayuki.mamirc.ProcessorConfiguration.IrcNetwork;


public final class MamircProcessor {
	
	/*---- Stub main program ----*/
	
	public static void main(String[] args) throws IOException, SQLiteException {
		Logger.getLogger("com.almworks.sqlite4java").setLevel(Level.OFF);  // Prevent sqlite4java module from polluting stderr with debug messages
		new MamircProcessor(
			new ConnectorConfiguration(new File(args[0])),
			new ProcessorConfiguration(new File(args[1])));
	}
	
	
	
	/*---- Fields (global state) ----*/
	
	private final ProcessorConfiguration myConfiguration;
	
	private Socket connectorSocket;
	private OutputWriterThread writer;
	
	private final Map<Integer,ConnectionState> ircConnections;
	
	
	
	/*---- Constructor ----*/
	
	public MamircProcessor(ConnectorConfiguration conConfig, ProcessorConfiguration procConfig) throws IOException, SQLiteException {
		if (conConfig == null || procConfig == null)
			throw new NullPointerException();
		myConfiguration = procConfig;
		
		connectorSocket = new Socket("localhost", conConfig.serverPort);
		writer = new OutputWriterThread(connectorSocket.getOutputStream(), new byte[]{'\n'});
		writer.start();
		writer.postWrite(conConfig.getConnectorPassword());
		
		LineReader reader = new LineReader(connectorSocket.getInputStream());
		String line = readStringLine(reader);
		if (line == null)
			throw new RuntimeException("Authentication failure");
		
		// Get set of current connections
		if (!line.equals("active-connections"))
			throw new RuntimeException("Invalid data received");
		Map<Integer,Integer> connectionSequences = new HashMap<>();
		while (true) {
			line = readStringLine(reader);
			if (line.equals("recent-events"))
				break;
			String[] parts = line.split(" ", 3);
			connectionSequences.put(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
		}
		
		// Get recent (potentially uncommitted) events
		List<Event> recentEvents = new ArrayList<>();
		while (true) {
			line = readStringLine(reader);
			if (line.equals("live-events"))
				break;
			String[] parts = line.split(" ", 5);
			int conId = Integer.parseInt(parts[0]);
			if (connectionSequences.containsKey(conId)) {
				recentEvents.add(new Event(
					conId,
					Integer.parseInt(parts[1]),
					Long.parseLong(parts[2]),
					Event.Type.fromOrdinal(Integer.parseInt(parts[3])),
					parts[4].getBytes(OutputWriterThread.UTF8_CHARSET)));
			}
		}
		
		Map<Integer,Integer> archiveSequences = new HashMap<>();
		List<Event> archivedEvents = new ArrayList<>();
		SQLiteConnection database = new SQLiteConnection(conConfig.databaseFile);
		database.open(false);
		SQLiteStatement query = database.prepare("SELECT sequence, timestamp, type, data FROM events WHERE connectionId=? AND sequence<? ORDER BY sequence ASC");
		for (int conId : connectionSequences.keySet()) {
			int nextSeq = connectionSequences.get(conId);
			query.bind(1, conId);
			query.bind(2, nextSeq);
			int maxSeq = -1;
			while (query.step()) {
				Event ev = new Event(conId, query.columnInt(0), query.columnLong(1), Event.Type.fromOrdinal(query.columnInt(2)), query.columnBlob(3));
				archivedEvents.add(ev);
				maxSeq = ev.sequence;
			}
			query.reset();
			archiveSequences.put(conId, maxSeq);
		}
		query.dispose();
		database.dispose();
		
		for (Iterator<Event> iter = recentEvents.iterator(); iter.hasNext(); ) {
			Event ev = iter.next();
			if (ev.sequence <= archiveSequences.get(ev.connectionId))
				iter.remove();
		}
		archivedEvents.addAll(recentEvents);
		recentEvents.clear();
		
		ircConnections = new HashMap<>();
		for (Event ev : archivedEvents)
			processEvent(ev, false);
		finishCatchup();
		
		while (true) {
			line = readStringLine(reader);
			if (line == null) {
				writer.terminate();
				connectorSocket.close();
				break;
			}
			String[] parts = line.split(" ", 5);
			Event ev = new Event(
				Integer.parseInt(parts[0]),
				Integer.parseInt(parts[1]),
				Long.parseLong(parts[2]),
				Event.Type.fromOrdinal(Integer.parseInt(parts[3])),
				parts[4].getBytes(OutputWriterThread.UTF8_CHARSET));
			processEvent(ev, true);
		}
	}
	
	
	/*---- Methods for manipulating global state ----*/
	
	private synchronized void processEvent(Event ev, boolean realtime) {
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
				if (new IrcLine.Prefix(msg.prefix).name.equals(state.currentNickname))
					state.currentChannels.add(msg.parameters.get(0));
				break;
			}
			
			case "PART": {
				if (new IrcLine.Prefix(msg.prefix).name.equals(state.currentNickname))
					state.currentChannels.remove(msg.parameters.get(0));
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
				if (!state.queuedPongs.isEmpty() && state.queuedPongs.element().equals(text)) {
					state.queuedPongs.remove();
				}
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
			}
			
			default:
				break;  // Ignore event
		}
	}
	
	
	private synchronized void finishCatchup() {
		for (int conId : ircConnections.keySet()) {
			ConnectionState state = ircConnections.get(conId);
			while (!state.queuedPongs.isEmpty())
				send(conId, "PONG", state.queuedPongs.remove());
			
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
	
	
	
	
	/*---- Miscellaneous methods ----*/
	
	private static String readStringLine(LineReader reader) throws IOException {
		byte[] line = reader.readLine();
		if (line == null)
			return null;
		else
			return new String(line, OutputWriterThread.UTF8_CHARSET);
	}
	
	
	
	/*---- Nested classes ----*/
	
	private static final class ConnectionState {
		
		public ProcessorConfiguration.IrcNetwork profile;
		public RegState registrationState;
		public Set<String> rejectedNicknames;
		public String currentNickname;
		public Set<String> currentChannels;
		public Queue<String> queuedPongs;  // Only used for catching up; is empty afterwards
		public boolean sentNickservPassword;
		
		
		public ConnectionState(ProcessorConfiguration.IrcNetwork profile) {
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
