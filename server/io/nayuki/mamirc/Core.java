package io.nayuki.mamirc;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;


final class Core {
	
	private File databaseFile;
	
	private Map<IrcServerConnection,ConnectionState> connectionToState = new HashMap<>();
	
	private Archiver archiver;
	
	
	public Core(File dbFile) {
		this.databaseFile = dbFile;
		Thread worker = new Thread(this::worker);
		archiver = new Archiver(dbFile, worker);
		worker.start();
	}
	
	
	private void worker() {
		try {
			while (true) {
				eventQueueLength.acquire();
				AugmentedConnectionEvent ace = eventQueue.remove();
				synchronized(this) {
					if (!connectionToState.containsKey(ace.connection))
						continue;
					ConnectionState state = connectionToState.get(ace.connection);
					archiver.postEvent(state.connectionId, ace.event);
					try {
						state.handle(ace.event, ace.connection);
					} catch (IrcSyntaxException|IrcStateException e) {
						e.printStackTrace();
					}
				}
			}
		} catch (InterruptedException e) {
			for (IrcServerConnection con : connectionToState.keySet())
				con.close();
		} finally {
			archiver.postTermination();
		}
	}
	
	
	public synchronized void reloadProfiles() throws IOException, SQLException {
		Map<Integer,IrcServerConnection> toDisconnect = new HashMap<>();
		for (Map.Entry<IrcServerConnection,ConnectionState> entry : connectionToState.entrySet())
			toDisconnect.put(entry.getValue().profileId, entry.getKey());
		
		try (Database db = new Database(databaseFile)) {
			for (int profId : db.getProfileIds()) {
				if (db.getProfileDoConnect(profId) && toDisconnect.remove(profId) == null) {
					List<IrcServer> servers = db.getProfileServers(profId);
					if (!servers.isEmpty()) {
						long conId = db.addConnection(profId);
						IrcServerConnection con = new IrcServerConnection(servers.get(0), db.getProfileCharacterEncoding(profId), this);
						connectionToState.put(con, new ConnectionState(conId, profId, this, archiver));
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
	
	
	private Queue<AugmentedConnectionEvent> eventQueue = new ConcurrentLinkedQueue<>();
	private Semaphore eventQueueLength = new Semaphore(0);
	
	
	public void postEvent(IrcServerConnection con, ConnectionEvent ev) {
		AugmentedConnectionEvent ace = new AugmentedConnectionEvent();
		ace.connection = Objects.requireNonNull(con);
		ace.event = Objects.requireNonNull(ev);
		eventQueue.add(ace);
		eventQueueLength.release();
	}
	
	
	
	private static final class AugmentedConnectionEvent {
		
		public IrcServerConnection connection;
		public ConnectionEvent event;
		
	}
	
}
