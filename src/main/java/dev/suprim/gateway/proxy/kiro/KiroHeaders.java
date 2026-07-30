package dev.suprim.gateway.proxy.kiro;

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
		return build(token, authManager.isApiKeyAuth());
	}

	Map<String, String> build(String token, boolean isApiKey) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Authorization", "Bearer " + token);
		headers.put("Content-Type", "application/json");
		headers.put("Accept", "application/vnd.amazon.eventstream");
		headers.put(
				"x-amz-target",
				"AmazonCodeWhispererStreamingService.GenerateAssistantResponse"
		);
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
}
