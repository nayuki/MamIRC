/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import io.nayuki.json.Json;
import io.nayuki.mamirc.common.Utils;


// Represents the user configuration of an IRC network. Mutable structure, not thread-safe.
// This includes information like which servers to connect to, what names to use, what channels to join, etc.
final class NetworkProfile {
	
	/*---- Fields ----*/
	
	// Name of this IRC network profile. Immutable, and not null.
	public final String name;
	
	// Indicates whether to start or stop connecting to IRC servers in this profile.
	public boolean connect;
	
	// List of servers to try to connect to, in priority order. Not null, elements not null.
	// If connect is true, then this needs to have at least 1 element.
	public final List<Server> servers;
	
	// List of nicknames to try to use, in priority order. Not null, elements not null.
	// If connect is true, then this needs to have at least 1 element.
	public final List<String> nicknames;
	
	// User name when the USER registration command is sent. Must not contain any spaces.
	// If connect is true, then this must be not null.
	public String username;
	
	// Real name when the USER registration command is sent. Can contain spaces.
	// If connect is true, then this must be not null.
	public String realname;
	
	// Channels to join when the Processor starts or when the profile is applied.
	// Not null; size at least 0, elements not null.
	// An element of this set is either a simple channel name like "#alpha",
	// or a channel and key separated by space like "#beta key".
	public final Set<String> channels;
	
	
	
	/*---- Constructors ----*/
	
	// Constructs a blank network profile with the given name.
	public NetworkProfile(String name) {
		if (name == null)
			throw new NullPointerException();
		this.name = name;
		servers = new ArrayList<>();
		nicknames = new ArrayList<>();
		channels = new TreeSet<>();
	}
	
	
	// Constructs a network profile with the given name and data from the given JSON object.
	// Throws an exception if expected fields are missing or have wrong types.
	NetworkProfile(String name, Object root) {
		this(name);
		
		// Simple fields
		connect  = Json.getBoolean(root, "connect");
		username = Json.getString(root, "username");
		realname = Json.getString(root, "realname");
		
		// List fields
		for (Object serv : Json.getList(root, "servers"))
			servers.add(new Server(serv));
		for (Object nick : Json.getList(root, "nicknames"))
			nicknames.add(Json.getString(nick));
		for (Object chan : Json.getList(root, "channels"))
			channels.add(Json.getString(chan));
	}
	
	
	
	/*---- Methods ----*/
	
	// Returns a new JSON object representing the current state of this network profile.
	Object toJson() {
		Map<String,Object> result = new HashMap<>();
		
		// Simple fields
		result.put("connect", connect);
		result.put("username", username);
		result.put("realname", realname);
		
		// Simple list fields
		result.put("nicknames", new ArrayList<>(nicknames));
		result.put("channels", new ArrayList<>(channels));
		
		// Complex list fields
		List<Object> outServers = new ArrayList<>();
		for (Server serv : servers)
			outServers.add(serv.toJson());
		result.put("servers", outServers);
		
		return result;
	}
	
	
	
	/*---- Nested classes ----*/
	
	// Represents a server to connect to. Immutable structure, performs no I/O.
	public static final class Server {
		
		public final InetSocketAddress hostnamePort;  // Not null
		public final boolean useSsl;
		
		
		// Constructs a server with the given parameters.
		public Server(String hostname, int port, boolean useSsl) {
			if (hostname == null)
				throw new NullPointerException();
			Utils.checkPortNumber(port);
			hostnamePort = InetSocketAddress.createUnresolved(hostname, port);
			this.useSsl = useSsl;
		}
		
		
		// Constructs a server with data from the given JSON object.
		// Throws an exception if expected fields are missing or have wrong types.
		Server(Object root) {
			this(
				Json.getString(root, "hostname"),
				Json.getInt(root, "port"),
				Json.getBoolean(root, "ssl"));
		}
		
		
		// Returns a new JSON object representing this server.
		Object toJson() {
			Map<String,Object> result = new HashMap<>();
			result.put("hostname", hostnamePort.getHostString());
			result.put("port", hostnamePort.getPort());
			result.put("ssl", useSsl);
			return result;
		}
		
	}
	
}
