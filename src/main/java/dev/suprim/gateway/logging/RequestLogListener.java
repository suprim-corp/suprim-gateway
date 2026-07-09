package dev.suprim.gateway.logging;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

@RequiredArgsConstructor
@Component
class RequestLogListener {

	private static final Logger log = LoggerFactory.getLogger(RequestLogListener.class);
	private final RequestLogRepository repository;

	@Async
	@EventListener
	void handle(RequestLogEvent event) {
		int totalTokens =
				(event.promptTokens() != null ? event.promptTokens() : 0)
				+ (event.completionTokens() !=
				   null ? event.completionTokens() : 0);
		RequestLog entry = new RequestLog(
				UUID.randomUUID().toString(),
				event.virtualKeyId(),
				event.accountId(),
				event.model(),
				event.requestedModel(),
				event.status(),
				event.promptTokens(),
				event.completionTokens(),
				totalTokens > 0 ? totalTokens : null,
				event.latencyMs(),
				event.firstTokenMs(),
				event.streaming(),
				event.clientIp(),
				event.errorMessage(),
				System.currentTimeMillis()
		);
		try {
			repository.insert(entry);
		} catch (Exception e) {
			log.error("Failed to persist request log: {}", e.getMessage());
		}
	}
}
