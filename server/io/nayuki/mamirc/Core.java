package io.nayuki.mamirc;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;


final class Core {
	
	private File databaseFile;
	
	private Set<IrcServerConnection> connections = new HashSet<>();
	
	private WebServer server;
	
	private Archiver archiver;
	
	
	public Core(File dbFile) throws IOException, SQLException {
		this.databaseFile = dbFile;
		server = new WebServer(this);
		Thread worker = new Thread(this::worker);
		archiver = new Archiver(dbFile, worker);
		worker.start();
	}
	
	
	private void worker() {
		try {
			while (true) {
				AugmentedConnectionEvent ace = eventQueue.take();
				synchronized(this) {
					if (!connections.contains(ace.connection))
						continue;
					archiver.postEvent(ace.connection.connectionId, ace.event);
					try {
						ace.connection.handle(ace.event);
					} catch (IrcSyntaxException|IrcStateException e) {
						e.printStackTrace();
					}
				}
			}
		} catch (InterruptedException e) {
			for (IrcServerConnection con : connections)
				con.close();
		} finally {
			archiver.postTermination();
			server.terminate();
		}
	}
	
	
	public synchronized void reloadProfiles() throws IOException, SQLException {
		Map<Integer,IrcServerConnection> toDisconnect = new HashMap<>();
		for (IrcServerConnection con : connections)
			toDisconnect.put(con.profileId, con);
		
		try (Database db = new Database(databaseFile)) {
			for (int profId : db.getProfileIds()) {
				if (db.getProfileDoConnect(profId) && toDisconnect.remove(profId) == null) {
					List<IrcServer> servers = db.getProfileServers(profId);
					if (!servers.isEmpty()) {
						long conId = db.addConnection(profId);
						IrcServerConnection con = new IrcServerConnection(conId, profId, this, archiver, servers.get(0), db.getProfileCharacterEncoding(profId));
						connections.add(con);
					}
				}
			}
		}
		
		for (IrcServerConnection con : toDisconnect.values())
			con.close();
	}
	
	
	public File getDatabaseFile() {
		return databaseFile;
	}
	
	
	private BlockingQueue<AugmentedConnectionEvent> eventQueue = new FastQueue<>();
	
	
	public void postEvent(IrcServerConnection con, ConnectionEvent ev) {
		AugmentedConnectionEvent ace = new AugmentedConnectionEvent();
		ace.connection = Objects.requireNonNull(con);
		ace.event = Objects.requireNonNull(ev);
		try {
			eventQueue.put(ace);
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	}
	
	
	
	private static final class AugmentedConnectionEvent {
		
		public IrcServerConnection connection;
		public ConnectionEvent event;
		
	}
	
}
