package io.nayuki.mamirc.common;

import java.util.concurrent.locks.Lock;


/* 
 * A wrapper for java.util.concurrent.locks.Lock that lets you use it in the Java 7+ try-with-resources statement.
 * 
 * Typical code without LockHelper:
 *     Lock lock = (...);
 *     lock.lock();
 *     try {
 *         ...
 *     } finally {
 *         lock.unlock();
 *     }
 * Improved code with LockHelper:
 *     Lock lock = (...);
 *     LockHelper locker = new LockHelper(lock);
 *     try (LockHelper lh = locker.enter()) {
 *         ...
 *     }
 */
public final class LockHelper implements AutoCloseable {
	
	/*---- Fields ----*/
	
	private final Lock lock;
	
	
	
	/*---- Constructor ----*/
	
	public LockHelper(Lock lock) {
		if (lock == null)
			throw new NullPointerException();
		this.lock = lock;
	}
	
	
	
	/*---- Methods ----*/
	
	public LockHelper enter() {
		lock.lock();
		return this;
	}
	
	
	public void close() {
		lock.unlock();
	}
	
}
