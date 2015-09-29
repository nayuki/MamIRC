package io.nayuki.mamirc.processor;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import io.nayuki.json.Json;


// Represents the processor configuration data. Immutable structure.
final class ProcessorConfiguration {
	
	/*---- Fields ----*/
	
	public final File databaseFile;  // Not null. File existence not checked.
	public final int webServerPort;  // In the range [0, 65535].
	public final Map<String,IrcNetwork> ircNetworks;  // Not null, keys/values not null, immutable.
	
	
	/*---- Constructor ----*/
	
	// Reads the given JSON file and initializes this data structure.
	public ProcessorConfiguration(File file) throws IOException {
		// Parse and do basic check
		Object data = Json.parseFromFile(file);
		if (!Json.getString(data, "data-type").equals("mamirc-processor-config"))
			throw new IllegalArgumentException("Invalid configuration file");
		
		// Convert to internal data format
		databaseFile = new File(Json.getString(data, "database-file"));
		webServerPort = Json.getInt(data, "web-server-port");
		if ((webServerPort & 0xFFFF) != webServerPort)
			throw new IllegalStateException("Invalid configuration file");
		
		// 'In' variables have data in JSON-Java format; 'Out' variables are in this data structure's desired format
		Map<String,Object> netsIn = Json.getMap(data, "irc-networks");
		Map<String,IrcNetwork> netsOut = new HashMap<>();
		for (String name : netsIn.keySet())
			convertNetwork(name, Json.getMap(netsIn, name), netsOut);
		ircNetworks = Collections.unmodifiableMap(netsOut);
	}
	
	
	/*---- Helper methods ----*/
	
	private void convertNetwork(String name, Map<String,Object> netIn, Map<String,IrcNetwork> netsOut) {
		String username = Json.getString(netIn, "username");
		String realname = Json.getString(netIn, "realname");
		String nspass = netIn.containsKey("nickserv-password") ? Json.getString(netIn, "nickserv-password") : null;
		
		List<Object> nicknamesIn = Json.getList(netIn, "nicknames");
		List<String> nicknamesOut = new ArrayList<>();
		for (Object o : nicknamesIn)
			nicknamesOut.add((String)o);
		
		List<Object> channelsIn = Json.getList(netIn, "channels");
		Set<String> channelsOut = new TreeSet<>();
		for (Object o : channelsIn)
			channelsOut.add((String)o);
		
		List<Object> serversIn = Json.getList(netIn, "servers");
		List<IrcNetwork.Server> serversOut = new ArrayList<>();
		for (Object servIn : serversIn)
			serversOut.add(convertServer(servIn));
		
		netsOut.put(name, new IrcNetwork(name, serversOut, nicknamesOut, username, realname, nspass, channelsOut));
	}
	
	
	private IrcNetwork.Server convertServer(Object servIn) {
		String hostname = Json.getString(servIn, "hostname");
		int port = Json.getInt(servIn, "port");
		boolean ssl = Json.getBoolean(servIn, "ssl");
		return new IrcNetwork.Server(hostname, port, ssl);
	}
	
	
	
	/* ---- Immutable data structure classes ----*/
	
	public static final class IrcNetwork {
		// Name of this IRC network profile. Not null.
		public final String name;
		// List of servers to try to connect to, in priority sequence. Not null; immutable, length at least 1, elements not null.
		public final List<Server> servers;
		// List of nicknames to try to use, in priority sequence. Not null; immutable, length at least 1, elements not null.
		public final List<String> nicknames;
		// Username at USER registration. Not null.
		public final String username;
		// Real name at USER registration. Not null.
		public final String realname;
		// Password for "/msg NickServ IDENTIFY <password>" immediately after USER registration. Can be null.
		public final String nickservPassword;
		// Initial set of channels to join. Not null; immutable, size at least 0, elements not null.
		public final Set<String> channels;
		
		
		public IrcNetwork(String name, List<Server> servers, List<String> nicknames,
				String username, String realname, String nickservPassword, Set<String> channels) {
			if (name == null || servers == null || nicknames == null || username == null
					|| realname == null || channels == null)
				throw new NullPointerException();
			if (servers.isEmpty())
				throw new IllegalArgumentException("Empty list of servers");
			if (nicknames.isEmpty())
				throw new IllegalArgumentException("Empty list of nicknames");
			this.name = name;
			this.servers = Collections.unmodifiableList(servers);
			this.nicknames = Collections.unmodifiableList(nicknames);
			this.username = username;
			this.realname = realname;
			this.nickservPassword = nickservPassword;
			this.channels = Collections.unmodifiableSet(channels);
		}
		
		
		public static final class Server {
			public final InetSocketAddress hostnamePort;  // Not null
			public final boolean useSsl;
			
			public Server(String hostname, int port, boolean useSsl) {
				hostnamePort = InetSocketAddress.createUnresolved(hostname, port);
				this.useSsl = useSsl;
			}
		}
	}
	
}
