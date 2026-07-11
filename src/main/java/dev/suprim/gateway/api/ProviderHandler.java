package dev.suprim.gateway.api;

import jakarta.servlet.http.HttpServletResponse;

import java.util.Map;

@FunctionalInterface
interface ProviderHandler {
	void handle(
			Map<String, Object> request,
			String model,
			boolean stream,
			int inputTokens,
			String keyId,
			String clientIp,
			HttpServletResponse httpRes
	) throws Exception;
}
