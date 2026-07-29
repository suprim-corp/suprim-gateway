package dev.suprim.gateway.api;

import dev.suprim.gateway.logging.ProviderOutcome;
import dev.suprim.gateway.logging.RequestLogCall;
import dev.suprim.gateway.proxy.InternalRequest;
import jakarta.servlet.http.HttpServletResponse;

@FunctionalInterface
interface ProviderHandler {
	ProviderOutcome handle(
			InternalRequest request,
			RequestLogCall call,
			HttpServletResponse httpRes
	) throws Exception;
}
