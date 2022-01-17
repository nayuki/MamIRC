package io.nayuki.mamirc;


final class ConnectionState {
	
	public final long connectionId;
	public final IrcNetworkProfile profile;
	
	
	public ConnectionState(long connectionId, IrcNetworkProfile profile) {
		this.connectionId = connectionId;
		this.profile = profile;
	}
	
	
	public void handle(ConnectionEvent ev, IrcServerConnection con) {
	}
	
}
