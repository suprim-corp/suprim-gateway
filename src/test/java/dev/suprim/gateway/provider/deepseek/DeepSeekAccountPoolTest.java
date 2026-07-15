package dev.suprim.gateway.provider.deepseek;

import dev.suprim.gateway.provider.StoredAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekAccountPoolTest {

	private DeepSeekAccountPool pool;
	private StoredAccount accountA;
	private StoredAccount accountB;

	@BeforeEach
	void setUp() {
		accountA = StoredAccount.builder().name("a@test.com").provider("DEEPSEEK").build();
		accountB = StoredAccount.builder().name("b@test.com").provider("DEEPSEEK").build();
		pool = new DeepSeekAccountPool(List.of(accountA, accountB), 2);
	}

	@Test
	void acquire_returnsAccount() {
		StoredAccount result = pool.acquire(Set.of());
		assertNotNull(result);
	}

	@Test
	void acquire_skipsTriedAccounts() {
		StoredAccount result = pool.acquire(Set.of("a@test.com"));
		assertEquals("b@test.com", result.name());
	}

	@Test
	void acquire_allTried_returnsNull() {
		StoredAccount result = pool.acquire(Set.of("a@test.com", "b@test.com"));
		assertNull(result);
	}

	@Test
	void acquire_respectsMaxConcurrency() {
		pool.acquire(Set.of());
		pool.acquire(Set.of());
		pool.acquire(Set.of());
		pool.acquire(Set.of());
		// all 4 slots used (2 per account × 2 accounts)
		StoredAccount result = pool.acquire(Set.of());
		assertNull(result);
	}

	@Test
	void release_freesSlot() {
		pool.acquire(Set.of());
		pool.acquire(Set.of());
		pool.acquire(Set.of());
		pool.acquire(Set.of());
		// full
		assertNull(pool.acquire(Set.of()));
		pool.release(accountA);
		StoredAccount freed = pool.acquire(Set.of());
		assertNotNull(freed);
		assertEquals("a@test.com", freed.name());
	}

	@Test
	void acquire_concurrentThreadSafety() throws Exception {
		DeepSeekAccountPool singlePool = new DeepSeekAccountPool(List.of(accountA), 1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		StoredAccount first = singlePool.acquire(Set.of());
		assertNotNull(first);

		Future<StoredAccount> future = executor.submit(() -> singlePool.acquireBlocking(Set.of(), 5000));

		assertThrows(TimeoutException.class, () -> future.get(200, TimeUnit.MILLISECONDS));

		singlePool.release(accountA);
		StoredAccount second = future.get(2, TimeUnit.SECONDS);
		assertNotNull(second);

		executor.shutdown();
	}

	@Test
	void release_belowZero_clampedAtZero() {
		pool.release(accountA);
		pool.release(accountA);
		// should not go negative — next acquire still works
		StoredAccount result = pool.acquire(Set.of());
		assertNotNull(result);
	}

	@Test
	void acquireBlocking_timesOut_returnsNull() throws Exception {
		DeepSeekAccountPool singlePool = new DeepSeekAccountPool(List.of(accountA), 1);
		singlePool.acquire(Set.of());
		StoredAccount result = singlePool.acquireBlocking(Set.of(), 100);
		assertNull(result);
	}

	@Test
	void acquireBlocking_skipsTriedAccounts() throws Exception {
		StoredAccount result = pool.acquireBlocking(Set.of("a@test.com"), 100);
		assertNotNull(result);
		assertEquals("b@test.com", result.name());
	}

	@Test
	void release_unknownAccount_noOp() {
		StoredAccount unknown = StoredAccount.builder().name("unknown@test.com").provider("DEEPSEEK").build();
		pool.release(unknown);
		// no exception, pool still works
		assertNotNull(pool.acquire(Set.of()));
	}
}
