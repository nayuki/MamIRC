package io.nayuki.mamirc.processor;

import java.io.File;
import java.io.IOException;
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
	public final Map<String,IrcNetwork> ircNetworks;  // Not null, keys/values not null, immutable
	
	
	/*---- Constructor ----*/
	
	// Reads the given JSON file and initializes this data structure.
	public ProcessorConfiguration(File file) throws IOException {
		// Parse and do basic check
		Object data = Json.parseFromFile(file);
		if (!Json.getString(data, "data-type").equals("mamirc-processor-config"))
			throw new IllegalArgumentException("Invalid configuration file");
		
		// Convert to internal data format
		databaseFile = new File(Json.getString(data, "database-file"));
		Map<String,Object> nets = Json.getMap(data, "irc-networks");
		Map<String,IrcNetwork> networks = new HashMap<>();
		for (String name : nets.keySet()) {
			Map<String,Object> net = Json.getMap(nets, name);
			String username = Json.getString(net, "username");
			String realname = Json.getString(net, "realname");
			String nickservPassword = net.containsKey("nickserv-password") ? Json.getString(net, "nickserv-password") : null;
			
			List<String> nicknames = new ArrayList<>();
			for (Object o : Json.getList(net, "nicknames"))
				nicknames.add((String)o);
			if (nicknames.size() == 0)
				throw new IllegalArgumentException("Empty list of nicknames");
			
			Set<String> channels = new TreeSet<>();
			for (Object o : Json.getList(net, "channels"))
				channels.add((String)o);
			
			List<IrcNetwork.Server> servers = new ArrayList<>();
			for (Object serv : Json.getList(net, "servers")) {
				String hostname = Json.getString(serv, "hostname");
				int port = Json.getInt(serv, "port");
				boolean useSsl = Json.getBoolean(serv, "ssl");
				servers.add(new IrcNetwork.Server(hostname, port, useSsl));
			}
			if (servers.size() == 0)
				throw new IllegalArgumentException("Empty list of servers");
			
			networks.put(name, new IrcNetwork(name, servers, nicknames, username, realname, nickservPassword, channels));
		}
		ircNetworks = Collections.unmodifiableMap(networks);
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
		
		
		public IrcNetwork(String name, List<Server> servers, List<String> nicknames, String username, String realname, String nickservPassword, Set<String> channels) {
			this.name = name;
			this.servers = Collections.unmodifiableList(servers);
			this.nicknames = Collections.unmodifiableList(nicknames);
			this.username = username;
			this.realname = realname;
			this.nickservPassword = nickservPassword;
			this.channels = Collections.unmodifiableSet(channels);
		}
		
		
		public static final class Server {
			public final String hostname;  // Not null
			public final int port;  // In the range [0, 65535]
			public final boolean useSsl;
			
			public Server(String hostname, int port, boolean useSsl) {
				if (hostname == null)
					throw new NullPointerException();
				if ((port & 0xFFFF) != port)
					throw new IllegalArgumentException();
				this.hostname = hostname;
				this.port = port;
				this.useSsl = useSsl;
			}
		}
	}
	
}
