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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import io.nayuki.json.Json;


// Mutable structure.
final class NetworkProfile {
	
	// Name of this IRC network profile. Immutable, and not null.
	public final String name;
	
	// Indicates whether to start or stop connecting to IRC servers in this profile.
	public boolean connect;
	
	// List of servers to try to connect to, in priority order. Not null; length at least 1, elements not null.
	public final List<Server> servers;
	
	// List of nicknames to try to use, in priority order. Not null; length at least 1, elements not null.
	public final List<String> nicknames;
	
	// User name at USER registration. Not null. Must not contain any spaces.
	public String username;
	
	// Real name at USER registration. Not null. Can contain spaces.
	public String realname;
	
	// Names of channels to join when processor starts. Not null; size at least 0, elements not null.
	public final Set<String> channels;
	
	
	
	public NetworkProfile(String name) {
		this.name = name;
		servers = new ArrayList<>();
		nicknames = new ArrayList<>();
		channels = new TreeSet<>();
	}
	
	
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
			nicknames.add((String)nick);
		for (Object chan : Json.getList(root, "channels"))
			channels.add((String)chan);
	}
	
	
	
	// Immutable structure.
	public static final class Server {
		
		public final InetSocketAddress hostnamePort;  // Not null
		public final boolean useSsl;
		
		
		public Server(String hostname, int port, boolean useSsl) {
			hostnamePort = InetSocketAddress.createUnresolved(hostname, port);
			this.useSsl = useSsl;
		}
		
		
		Server(Object root) {
			this(
				Json.getString(root, "hostname"),
				Json.getInt(root, "port"),
				Json.getBoolean(root, "ssl"));
		}
		
	}
	
}
