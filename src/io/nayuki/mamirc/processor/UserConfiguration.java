package io.nayuki.mamirc.processor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import io.nayuki.json.Json;


/* 
 * Represents configuration data for the end user. This structure is mutable.
 */
final class UserConfiguration {
	
	/*---- Fields ----*/
	
	public Map<String,IrcNetwork> ircNetworks;  // Not null; keys and values not null; immutable.
	
	public int dateBoundaryOffsetSeconds;
	
	
	/*---- Constructor ----*/
	
	// Constructs an object by reading the file at the given path.
	public UserConfiguration(File file) throws IOException {
		// Parse JSON data and check signature
		Object data = Json.parseFromFile(file);
		if (!Json.getString(data, "data-type").equals("mamirc-user-config"))
			throw new IllegalArgumentException("Invalid configuration file type");
		
		// Convert to internal data format
		dateBoundaryOffsetSeconds = Json.getInt(data, "date-boundary-offset-seconds");
		
		// 'In'-suffixed variables have data in JSON-Java format;
		// 'Out'-suffixed variables are in this data structure's desired format.
		Map<String,Object> netsIn = Json.getMap(data, "network-profiles");
		Map<String,IrcNetwork> netsOut = new HashMap<>();
		for (String name : netsIn.keySet())
			netsOut.put(name, convertNetwork(name, Json.getMap(netsIn, name)));
		ircNetworks = Collections.unmodifiableMap(netsOut);
	}
	
	
	/*---- Methods ----*/
	
	public Object toJsonObject() {
		Map<String,Object> result = new HashMap<>();
		result.put("date-boundary-offset-seconds", dateBoundaryOffsetSeconds);
		return result;
	}
	
	
	public void writeToFile(File file) throws IOException {
		// Manually serialize data in JSON format
		StringBuilder sb = new StringBuilder();
		sb.append("{\n");
		sb.append("\t\"data-type\": \"mamirc-user-config\",\n");
		sb.append("\t\n");
		sb.append("\t\"network-profiles\": {\n");
		List<String> profileNames = new ArrayList<>(ircNetworks.keySet());
		Collections.sort(profileNames);
		for (String name : profileNames) {
			IrcNetwork profile = ircNetworks.get(name);
			sb.append("\t\t\"").append(name).append("\": {\n");
			sb.append("\t\t\t\"connect\": ").append(profile.connect).append(",\n");
			sb.append("\t\t\t\"servers\": [\n");
			for (IrcNetwork.Server server : profile.servers) {
				sb.append("\t\t\t\t{\"hostname\": \"").append(server.hostnamePort.getHostString());
				sb.append("\", \"port\": ").append(server.hostnamePort.getPort());
				sb.append(", \"ssl\": ").append(server.useSsl).append("}");
				if (server != profile.servers.get(profile.servers.size() - 1))
					sb.append(",");
				sb.append("\n");
			}
			sb.append("\t\t\t],\n");
			sb.append("\t\t\t\"nicknames\": [");
			boolean head = true;
			for (String nick : profile.nicknames) {
				if (head) head = false;
				else sb.append(", ");
				sb.append('"').append(nick).append('"');
			}
			sb.append("],\n");
			sb.append("\t\t\t\"username\": \"").append(profile.username).append("\",\n");
			sb.append("\t\t\t\"realname\": \"").append(profile.realname).append("\",\n");
			sb.append("\t\t\t\"channels\": [");
			head = true;
			for (String chan : profile.channels) {
				if (head) head = false;
				else sb.append(", ");
				sb.append('"').append(chan).append('"');
			}
			sb.append("]\n");
			sb.append("\t\t}").append(name != profileNames.get(profileNames.size() - 1) ? "," : "").append("\n");
		}
		sb.append("\t},\n");
		sb.append("\t\n");
		sb.append("\t\"date-boundary-offset-seconds\": ").append(dateBoundaryOffsetSeconds).append("\n");
		sb.append("}\n");
		String data = sb.toString();
		
		Json.parse(data);  // Ensure that the JSON is well-formed
		Writer out = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
		try {
			out.write(data);
		} finally {
			out.close();
		}
	}
	
	
	/*---- Helper functions ----*/
	
	private static IrcNetwork convertNetwork(String name, Map<String,Object> netIn) {
		boolean connect = Json.getBoolean(netIn, "connect");
		String username = Json.getString(netIn, "username");
		String realname = Json.getString(netIn, "realname");
		String nspass = netIn.containsKey("nickserv-password") ? Json.getString(netIn, "nickserv-password") : null;
		
		List<Object> nicksIn = Json.getList(netIn, "nicknames");
		List<String> nicksOut = new ArrayList<>();
		for (Object nick : nicksIn)
			nicksOut.add((String)nick);
		
		List<Object> chansIn = Json.getList(netIn, "channels");
		Set<String> chansOut = new TreeSet<>();
		for (Object chan : chansIn)
			chansOut.add((String)chan);
		
		List<Object> serversIn = Json.getList(netIn, "servers");
		List<IrcNetwork.Server> serversOut = new ArrayList<>();
		for (Object servIn : serversIn)
			serversOut.add(convertServer(servIn));
		
		return new IrcNetwork(name, connect, serversOut, nicksOut, username, realname, nspass, chansOut);
	}
	
	
	private static IrcNetwork.Server convertServer(Object servIn) {
		String hostname = Json.getString(servIn, "hostname");
		int port = Json.getInt(servIn, "port");
		boolean ssl = Json.getBoolean(servIn, "ssl");
		return new IrcNetwork.Server(hostname, port, ssl);
	}
	
	
	/*---- Enclosing immutable types ----*/
	
	public static final class IrcNetwork {
		// Name of this IRC network profile. Not null.
		public final String name;
		// Indicates whether to start or stop connecting to IRC servers in this profile.
		public final boolean connect;
		// List of servers to try to connect to, in priority order. Not null; immutable, length at least 1, elements not null.
		public final List<Server> servers;
		// List of nicknames to try to use, in priority order. Not null; immutable, length at least 1, elements not null.
		public final List<String> nicknames;
		// User name at USER registration. Not null. Must not contain any spaces.
		public final String username;
		// Real name at USER registration. Not null. Can contain spaces.
		public final String realname;
		// Password for "/msg NickServ IDENTIFY <password>" immediately after USER registration. Can be null.
		public final String nickservPassword;
		// Names of channels to join when processor starts. Not null; immutable, size at least 0, elements not null.
		public final Set<String> channels;
		
		
		public IrcNetwork(String name, boolean connect, List<Server> servers, List<String> nicknames,
				String username, String realname, String nickservPassword, Set<String> channels) {
			if (name == null || servers == null || nicknames == null || username == null
					|| realname == null || channels == null)
				throw new NullPointerException();
			if (servers.isEmpty())
				throw new IllegalArgumentException("Empty list of servers");
			if (nicknames.isEmpty())
				throw new IllegalArgumentException("Empty list of nicknames");
			this.name = name;
			this.connect = connect;
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
