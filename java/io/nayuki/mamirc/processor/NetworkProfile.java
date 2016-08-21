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


// Mutable structure.
class NetworkProfile {
	
	// Name of this IRC network profile. Not null.
	public String name;
	
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
	
	
	
	public NetworkProfile() {
		servers = new ArrayList<>();
		nicknames = new ArrayList<>();
		channels = new TreeSet<>();
	}
	
	
	
	// Immutable structure.
	public static final class Server {
		
		public final InetSocketAddress hostnamePort;  // Not null
		public final boolean useSsl;
		
		
		public Server(String hostname, int port, boolean useSsl) {
			hostnamePort = InetSocketAddress.createUnresolved(hostname, port);
			this.useSsl = useSsl;
		}
		
	}
	
}
