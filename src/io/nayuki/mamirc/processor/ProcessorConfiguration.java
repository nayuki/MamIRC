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


// Immutable structure. Data is loaded from a JSON file.
final class ProcessorConfiguration {
	
	/*---- Fields ----*/
	
	public final Map<String,IrcNetwork> ircNetworks;
	
	
	/*---- Constructor ----*/
	
	public ProcessorConfiguration(File file) throws IOException {
		// Parse and do basic check
		Object data = Json.parseFromFile(file);
		if (!Json.getString(data, "data-type").equals("mamirc-processor-config"))
			throw new IllegalArgumentException("Invalid configuration file");
		
		// Convert to internal data format
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
		public final String name;              // Not null
		public final List<Server> servers;     // Length at least 1
		public final List<String> nicknames;   // Length at least 1
		public final String username;          // Not null
		public final String realname;          // Not null
		public final String nickservPassword;  // Can be null
		public final Set<String> channels;     // Length at least 0
		
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
			public final String hostname;
			public final int port;
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
