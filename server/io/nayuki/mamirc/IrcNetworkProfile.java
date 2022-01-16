package io.nayuki.mamirc;

import java.util.ArrayList;
import java.util.List;


final class IrcNetworkProfile {
	
	public int id;
	public String name;
	
	public boolean doConnect;
	
	public List<Server> servers = new ArrayList<>();
	
	public String characterEncoding;
	
	public List<String> nicknames = new ArrayList<>();
	public String username;
	public String realName;
	
	public List<String> afterRegistrationCommands = new ArrayList<>();
	
	
	
	public static final class Server {
		
		public String hostname;
		public int port;
		public TlsMode tlsMode;
		
		
		
		public enum TlsMode {
			UNSECURED,
			TLS_WITHOUT_CERTIFICATE_VERIFICATION,
			TLS_WITH_CERTIFICATE_VERIFICATION,
		}
		
	}
	
}
