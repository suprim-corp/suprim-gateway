package dev.suprim.gateway.api;

import jakarta.servlet.http.HttpServletResponse;

@FunctionalInterface
interface ProviderHandler {
	void handle(
			Object request,
			String model,
			boolean stream,
			int inputTokens,
			String keyId,
			String clientIp,
			HttpServletResponse httpRes
	) throws Exception;
}
