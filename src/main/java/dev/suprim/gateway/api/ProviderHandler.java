package dev.suprim.gateway.api;

import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.ProxyFacade;
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
			ProxyFacade.Format format,
			HttpServletResponse httpRes
	) throws Exception;
}
