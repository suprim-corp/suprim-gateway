package dev.suprim.gateway.proxy;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Component
public class SseHeartbeatScheduler {

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
			new HeartbeatThreadFactory()
	);

	public ScheduledFuture<?> schedule(
			Runnable heartbeat,
			long intervalSeconds
	) {
		return executor.scheduleAtFixedRate(
				heartbeat,
				intervalSeconds,
				intervalSeconds,
				TimeUnit.SECONDS
		);
	}

	@PreDestroy
	public void shutdown() {
		executor.shutdownNow();
	}

	private static final class HeartbeatThreadFactory implements ThreadFactory {

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "sse-heartbeat");
			thread.setDaemon(true);
			return thread;
		}
	}
}
