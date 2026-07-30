package dev.suprim.gateway.proxy.kiro;

import dev.suprim.gateway.instants.Kiro;
import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import dev.suprim.gateway.provider.kiro.KiroUserAgent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class KiroHeaders {

	private final KiroAuthManager authManager;

	Map<String, String> build(String token) {
		return build(token, authManager.isApiKeyAuth(), null);
	}

	/**
	 * The streaming headers for one call to {@code url}.
	 * <p>
	 * {@code x-amz-target} follows the host instead of going out everywhere: only the
	 * CodeWhisperer surface speaks that protocol, while the kiro.dev gateway and the Q surface
	 * answer 400 to a request that carries one.
	 */
	Map<String, String> build(String token, boolean isApiKey, String url) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Authorization", "Bearer " + token);
		headers.put("Content-Type", "application/json");
		headers.put("Accept", "application/vnd.amazon.eventstream");
		if (isCodeWhispererSurface(url)) {
			headers.put("x-amz-target", Kiro.AMZ_TARGET);
		}
		headers.put("User-Agent", KiroUserAgent.streaming());
		headers.put("x-amz-user-agent", KiroUserAgent.amzStreaming());
		headers.put("x-amzn-codewhisperer-optout", "true");
		headers.put("x-amzn-kiro-agent-mode", "vibe");
		headers.put("amz-sdk-invocation-id", UUID.randomUUID().toString());
		headers.put("amz-sdk-request", "attempt=1; max=3");
		if (isApiKey) {
			headers.put("Tokentype", "API_KEY");
		}
		return headers;
	}

	private static boolean isCodeWhispererSurface(String url) {
		return url != null && url.contains(Kiro.CODEWHISPERER_URL_MARKER);
	}
}
