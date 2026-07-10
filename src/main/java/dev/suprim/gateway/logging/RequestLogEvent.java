package dev.suprim.gateway.logging;

import lombok.Builder;

@Builder
public record RequestLogEvent(
		String virtualKeyId,
		String accountId,
		String model,
		String requestedModel,
		int status,
		Integer promptTokens,
		Integer completionTokens,
		Integer latencyMs,
		Integer firstTokenMs,
		Boolean streaming,
		String clientIp,
		String errorMessage,
		Double credits
) {}
