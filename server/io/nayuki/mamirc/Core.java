package io.nayuki.mamirc;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;


final class Core {
	
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
