package io.nayuki.mamirc;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;


final class Archiver {
	
	private final File databaseFile;
	private BlockingQueue<QueueItem> queue = new LinkedBlockingQueue<>();
	
	
	public Archiver(File dbFile) {
		databaseFile = dbFile;
		new Thread(this::worker).start();
	}
	
	
	private void worker() {
		try (Database database = new Database(databaseFile)) {
			
			while (true) {
				QueueItem item = queue.take();
				database.beginImmediateTransaction();
				while (true) {
					
					if (item instanceof AugmentedConnectionEvent) {
						AugmentedConnectionEvent ace = (AugmentedConnectionEvent)item;
						database.addConnectionEvent(ace.connectionId, ace.event);
					} else
						throw new AssertionError();
					
					if (queue.isEmpty()) {
						database.commitTransaction();
						break;
					}
					item = queue.take();
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
	
	
	
	private static abstract class QueueItem {}
	
	
	
	private static final class AugmentedConnectionEvent extends QueueItem {
		
		public long connectionId;
		public ConnectionEvent event;
		
	}
	
}
