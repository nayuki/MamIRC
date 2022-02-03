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
					} else if (item instanceof ProcessedMessage) {
						ProcessedMessage pm = (ProcessedMessage)item;
						database.addProcessedMessage(pm.profileId, pm.displayName, pm.timestampUnixMs, pm.data);
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
	
	
	public void postMessage(int profileId, String displayName, long timestampUnixMs, String data) {
		if (profileId < 0)
			throw new IllegalArgumentException("Negative profile ID");
		ProcessedMessage pm = new ProcessedMessage();
		pm.profileId = profileId;
		pm.displayName = Objects.requireNonNull(displayName);
		pm.timestampUnixMs = timestampUnixMs;
		pm.data = Objects.requireNonNull(data);
		try {
			queue.put(pm);
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	}
	
	
	
	private static abstract class QueueItem {}
	
	
	
	private static final class AugmentedConnectionEvent extends QueueItem {
		
		public long connectionId;
		public ConnectionEvent event;
		
	}
	
	
	
	private static final class ProcessedMessage extends QueueItem {
		
		public int profileId;
		public String displayName;
		public long timestampUnixMs;
		public String data;
		
	}
	
}
