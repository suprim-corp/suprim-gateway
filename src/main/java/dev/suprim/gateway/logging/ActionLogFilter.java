package dev.suprim.gateway.logging;

import dev.suprim.gateway.utils.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

@Component
@Order(1)
public class ActionLogFilter extends OncePerRequestFilter {

	private static final Set<String> BLACKLIST_PREFIXES = Set.of(
			"/actuator", "/health", "/swagger-ui", "/api-docs",
			"/login", "/css", "/js", "/favicon"
	);

	private static final Set<String> STREAMING_PATHS = Set.of(
			"/v1/chat/completions", "/v1/responses"
	);

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		for (String prefix : BLACKLIST_PREFIXES) {
			if (path.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain chain
	)
			throws ServletException, IOException {
		String correlationId = request.getHeader("X-Request-ID");
		if (correlationId == null || correlationId.isBlank()) {
			correlationId = UUID.randomUUID().toString();
		}
		response.setHeader("X-Request-ID", correlationId);
		MDC.put("requestId", correlationId);

		String method = request.getMethod();
		String uri = request.getRequestURI();
		String ip = RequestContext.clientIp(request);
		String query = request.getQueryString();
		long start = System.currentTimeMillis();

		boolean isStreaming = STREAMING_PATHS.contains(uri);

		try {
			if (isStreaming) {
				ActionLogger.logRequest(method, uri, ip, query, null);
				chain.doFilter(request, response);
				return;
			}

			HttpServletRequest effectiveRequest = request;
			String body = null;
			if (!"GET".equalsIgnoreCase(method) && shouldCacheBody(request)) {
				CachedHttpServletRequest cachedRequest = new CachedHttpServletRequest(
						request
				);
				effectiveRequest = cachedRequest;
				body = cachedRequest.getBody();
			}
			ActionLogger.logRequest(method, uri, ip, query, body);

			ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(
					response
			);
			chain.doFilter(effectiveRequest, wrappedResponse);
			long duration = System.currentTimeMillis() - start;
			String responseBody = isJsonResponse(wrappedResponse)
					? new String(
					wrappedResponse.getContentAsByteArray(),
					StandardCharsets.UTF_8
			)
					: null;
			ActionLogger.logResponse(
					method,
					uri,
					wrappedResponse.getStatus(),
					responseBody,
					duration,
					null
			);
			wrappedResponse.copyBodyToResponse();
		} finally {
			MDC.remove("requestId");
		}
	}

	private static boolean shouldCacheBody(HttpServletRequest request) {
		String contentType = request.getContentType();
		if (contentType == null) return false;
		String lower = contentType.toLowerCase();
		return lower.contains("application/json") || lower.contains("text/");
	}

	private static boolean isJsonResponse(ContentCachingResponseWrapper response) {
		String contentType = response.getContentType();
		return contentType != null && contentType.contains("application/json");
	}
}
