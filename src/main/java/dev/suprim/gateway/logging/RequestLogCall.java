package dev.suprim.gateway.logging;

import dev.suprim.gateway.proxy.Format;
import lombok.Builder;

@Builder
public record RequestLogCall(
		String model,
		boolean streaming,
		int estimatedInputTokens,
		String virtualKeyId,
		String clientIp,
		Format format,
		long startedAt
) {

	public static RequestLogCall start(
			String model,
			boolean streaming,
			int estimatedInputTokens,
			String virtualKeyId,
			String clientIp,
			Format format
	) {
		return RequestLogCall.builder()
		                     .model(model)
		                     .streaming(streaming)
		                     .estimatedInputTokens(estimatedInputTokens)
		                     .virtualKeyId(virtualKeyId)
		                     .clientIp(clientIp)
		                     .format(format)
		                     .startedAt(System.currentTimeMillis())
		                     .build();
	}

	public ProviderOutcome success(
			String accountId,
			Integer inputTokens,
			Integer outputTokens,
			Long firstTokenMs,
			Double credits
	) {
		return ProviderOutcome.logged(base(accountId, 200)
				.promptTokens(positiveOr(inputTokens, estimatedInputTokens))
				.completionTokens(positiveOrNull(outputTokens))
				.firstTokenMs(
						firstTokenMs == null ? null : firstTokenMs.intValue())
				.credits(credits != null && credits > 0 ? credits : null)
				.build());
	}

	public RequestLogCall withEstimatedInputTokens(int inputTokens) {
		return RequestLogCall.builder()
		                     .model(model)
		                     .streaming(streaming)
		                     .estimatedInputTokens(inputTokens)
		                     .virtualKeyId(virtualKeyId)
		                     .clientIp(clientIp)
		                     .format(format)
		                     .startedAt(startedAt)
		                     .build();
	}

	public ProviderOutcome upstreamError(
			String accountId,
			int status,
			String message
	) {
		return ProviderOutcome.logged(base(accountId, status)
				.promptTokens(positiveOrNull(estimatedInputTokens))
				.errorMessage(truncate(message, 200))
				.build());
	}

	private RequestLogEvent.RequestLogEventBuilder base(
			String accountId,
			int status
	) {
		return RequestLogEvent.builder()
		                      .virtualKeyId(virtualKeyId)
		                      .accountId(accountId)
		                      .model(model)
		                      .requestedModel(model)
		                      .status(status)
		                      .latencyMs((int) (System.currentTimeMillis() -
		                                        startedAt))
		                      .streaming(streaming)
		                      .clientIp(clientIp);
	}

	private static Integer positiveOr(Integer value, int fallback) {
		return value != null && value > 0 ? value : positiveOrNull(fallback);
	}

	private static Integer positiveOrNull(Integer value) {
		return value != null && value > 0 ? value : null;
	}

	private static String truncate(String value, int maxLength) {
		if (value == null) {
			return null;
		}
		return value.length() > maxLength ? value.substring(
				0,
				maxLength
		) : value;
	}
}
