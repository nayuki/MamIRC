package io.nayuki.mamirc;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
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
		archiver = new Archiver(dbFile);
		new Thread(this::worker).start();
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
					state.handle(ace.event, ace.connection);
				}
			}
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}
	
	
	public synchronized void setProfiles(Collection<IrcNetworkProfile> profiles) throws IOException, SQLException {
		Map<Integer,IrcServerConnection> toDisconnect = new HashMap<>();
		for (Map.Entry<IrcServerConnection,ConnectionState> entry : connectionToState.entrySet())
			toDisconnect.put(entry.getValue().profile.id, entry.getKey());
		
		for (IrcNetworkProfile prof : profiles) {
			if (prof.doConnect && toDisconnect.remove(prof.id) == null && !prof.servers.isEmpty()) {
				long conId;
				try (Database db = new Database(databaseFile)) {
					conId = db.addConnection(prof.id);
				}
				IrcServerConnection con = new IrcServerConnection(prof, this);
				connectionToState.put(con, new ConnectionState(conId, prof));
			}
		}
		
		for (IrcServerConnection con : toDisconnect.values())
			con.close();
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
