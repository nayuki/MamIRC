package io.nayuki.mamirc;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;


final class Archiver {
	
	private final File databaseFile;
	private BlockingQueue<AugmentedConnectionEvent> queue = new LinkedBlockingQueue<>();
	
	
	public Archiver(File dbFile) {
		databaseFile = dbFile;
		new Thread(this::worker).start();
	}
	
	
	private void worker() {
		try (Database database = new Database(databaseFile)) {
			
			while (true) {
				AugmentedConnectionEvent ace = queue.take();
				database.beginImmediateTransaction();
				while (true) {
					database.addConnectionEvent(ace.connectionId, ace.event);
					if (queue.isEmpty()) {
						database.commitTransaction();
						break;
					}
					ace = queue.take();
				}
			}
			
		} catch (IOException|SQLException|InterruptedException e) {
			throw new RuntimeException(e);
		}
	}
	
	
	public void postEvent(long conId, ConnectionEvent ev) {
		AugmentedConnectionEvent ace = new AugmentedConnectionEvent();
		ace.connectionId = conId;
		ace.event = Objects.requireNonNull(ev);
		try {
			queue.put(ace);
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	}
	
	
	
	private static final class AugmentedConnectionEvent {
		
		public long connectionId;
		public ConnectionEvent event;
		
	}
	
}
