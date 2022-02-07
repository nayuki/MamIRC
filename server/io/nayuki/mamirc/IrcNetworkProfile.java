package io.nayuki.mamirc;


final class IrcNetworkProfile {
	
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
