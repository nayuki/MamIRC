package io.nayuki.mamirc;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;


final class FastQueue<E> extends AbstractQueue<E> implements BlockingQueue<E> {
	
	private Semaphore size = new Semaphore(0);
	private Queue<E> data = new ConcurrentLinkedQueue<>();
	
	
	@Override public void put(E obj) {
		data.add(Objects.requireNonNull(obj));
		size.release();
	}
	
	
	@Override public E take() throws InterruptedException {
		size.acquire();
		return data.remove();
	}
	
	
	@Override public int size() {
		throw new UnsupportedOperationException();
	}
	
	@Override public int remainingCapacity() {
		throw new UnsupportedOperationException();
	}
	
	@Override public boolean offer(E obj) {
		throw new UnsupportedOperationException();
	}
	
	@Override public boolean offer(E obj, long timeout, TimeUnit unit) {
		throw new UnsupportedOperationException();
	}
	
	@Override public E peek() {
		throw new UnsupportedOperationException();
	}
	
	@Override public Iterator<E> iterator() {
		throw new UnsupportedOperationException();
	}
	
	@Override public E poll() {
		throw new UnsupportedOperationException();
	}
	
	@Override public E poll(long timeout, TimeUnit unit) {
		throw new UnsupportedOperationException();
	}
	
	@Override public int drainTo(Collection<? super E> coll) {
		throw new UnsupportedOperationException();
	}
	
	@Override public int drainTo(Collection<? super E> coll, int maxElems) {
		throw new UnsupportedOperationException();
	}
	
}
