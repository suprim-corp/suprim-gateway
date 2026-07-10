package dev.suprim.gateway.proxy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ProxyRotator {

	private final List<ProxyEntry> entries;
	private final AtomicInteger activeIndex = new AtomicInteger(0);
	private final AtomicInteger attempts = new AtomicInteger(0);

	private ProxyRotator(List<ProxyEntry> entries) {
		this.entries = entries;
	}

	public static ProxyRotator of(List<ProxyEntry> entries) {
		return new ProxyRotator(entries);
	}

	public ProxyEntry current() {
		if (entries.isEmpty()) {
			return null;
		}
		return entries.get(activeIndex.get());
	}

	public void advance() {
		if (entries.size() <= 1) {
			attempts.incrementAndGet();
			return;
		}
		activeIndex.updateAndGet(i -> (i + 1) % entries.size());
		attempts.incrementAndGet();
	}

	public boolean isExhausted() {
		if (entries.isEmpty()) {
			return false;
		}
		return attempts.get() >= entries.size();
	}

	public void resetAttempts() {
		attempts.set(0);
	}

	public int size() {
		return entries.size();
	}
}
