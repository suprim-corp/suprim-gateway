package dev.suprim.gateway.api;

import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.InternalRequest;
import jakarta.servlet.http.HttpServletResponse;

@FunctionalInterface
interface ProviderHandler {
	void handle(
			InternalRequest request,
			String model,
			boolean stream,
			int inputTokens,
			String keyId,
			String clientIp,
			Format format,
			HttpServletResponse httpRes
	) throws Exception;
}
