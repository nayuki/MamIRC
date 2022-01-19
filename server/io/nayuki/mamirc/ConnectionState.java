package io.nayuki.mamirc;

import java.nio.charset.Charset;


final class ConnectionState {
	
	public final long connectionId;
	public final IrcNetworkProfile profile;
	private final Charset charset;
	
	
	
	public ConnectionState(long connectionId, IrcNetworkProfile profile) {
		this.connectionId = connectionId;
		this.profile = profile;
		charset = Charset.forName(profile.characterEncoding);
	}
	
	
	public void handle(ConnectionEvent ev, IrcServerConnection con) {
		if (ev instanceof ConnectionEvent.Opened) {
			send(con, "NICK", profile.nicknames.get(0));
			send(con, "USER", profile.username, "0", "*", profile.realName);
		}
		
		else if (ev instanceof ConnectionEvent.LineReceived) {
			String line = new String(((ConnectionEvent.LineReceived)ev).line, charset);
			IrcMessage msg;
			try {
				msg = IrcMessage.parseLine(line);
			} catch (IllegalArgumentException e) {
				return;
			}
			
			switch (msg.command) {
				case "PING": {
					if (msg.parameters.size() == 1)
						send(con, "PONG", msg.parameters.get(0));
					break;
				}
			}
		}
	}
	
	
	private void send(IrcServerConnection con, String cmd, String... params) {
		IrcMessage msg = IrcMessage.makeWithoutPrefix(cmd, params);
		con.postWriteLine(msg.toString().getBytes(charset));
	}
	
}
