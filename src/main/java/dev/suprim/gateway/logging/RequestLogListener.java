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
		RequestLog entry = RequestLog.builder()
		                             .id(UUID.randomUUID().toString())
		                             .virtualKeyId(event.virtualKeyId())
		                             .accountId(event.accountId())
		                             .model(event.model())
		                             .requestedModel(event.requestedModel())
		                             .status(event.status())
		                             .promptTokens(event.promptTokens())
		                             .completionTokens(event.completionTokens())
		                             .totalTokens(totalTokens >
		                                          0 ? totalTokens : null
		                             )
		                             .latencyMs(event.latencyMs())
		                             .firstTokenMs(event.firstTokenMs())
		                             .streaming(event.streaming())
		                             .clientIp(event.clientIp())
		                             .errorMessage(event.errorMessage())
		                             .credits(event.credits())
		                             .createdAt(System.currentTimeMillis())
		                             .build();
		try {
			repository.insert(entry);
		} catch (Exception e) {
			log.error("Failed to persist request log: {}", e.getMessage());
		}
	}
}
