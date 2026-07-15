package dev.suprim.gateway.provider.deepseek;

import dev.suprim.gateway.provider.StoredAccount;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Manages concurrent access to DeepSeek accounts with per-account concurrency limits.
 */
public class DeepSeekAccountPool {

	private final List<StoredAccount> accounts;
	private final ConcurrentHashMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();
	private final int maxConcurrency;

	public DeepSeekAccountPool(
			List<StoredAccount> accounts,
			int maxConcurrency
	) {
		this.accounts = accounts;
		this.maxConcurrency = maxConcurrency;
		for (StoredAccount account : accounts) {
			semaphores.put(account.name(), new Semaphore(maxConcurrency));
		}
	}

	public StoredAccount acquire(Set<String> triedAccounts) {
		for (StoredAccount account : accounts) {
			if (triedAccounts.contains(account.name())) {
				continue;
			}
			Semaphore semaphore = semaphores.get(account.name());
			if (semaphore.tryAcquire()) {
				return account;
			}
		}
		return null;
	}

	public StoredAccount acquireBlocking(
			Set<String> triedAccounts,
			long timeoutMs
	) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			for (StoredAccount account : accounts) {
				if (triedAccounts.contains(account.name())) {
					continue;
				}
				Semaphore semaphore = semaphores.get(account.name());
				if (semaphore.tryAcquire(50, TimeUnit.MILLISECONDS)) {
					return account;
				}
			}
		}
		return null;
	}

	public void release(StoredAccount account) {
		Semaphore semaphore = semaphores.get(account.name());
		if (semaphore != null &&
		    semaphore.availablePermits() < maxConcurrency) {
			semaphore.release();
		}
	}
}
