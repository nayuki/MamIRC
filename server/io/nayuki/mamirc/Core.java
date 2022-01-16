package io.nayuki.mamirc;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;


final class Core {
	
	private Queue<ConnectionEvent> eventQueue = new ConcurrentLinkedQueue<>();
	private Semaphore eventQueueLength = new Semaphore(0);
	
	
	public void postEvent(IrcServerConnection con, ConnectionEvent ev) {
		Objects.requireNonNull(con);
		eventQueue.add(Objects.requireNonNull(ev));
		eventQueueLength.release();
	}
	
}
