package dev.suprim.gateway.proxy.kiro;

import dev.suprim.gateway.provider.kiro.KiroAuthManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class KiroHeaders {

	private static final String FINGERPRINT = UUID.randomUUID()
	                                              .toString()
	                                              .substring(0, 8);

	private final KiroAuthManager authManager;

	Map<String, String> build(String token) {
		return build(token, authManager.isApiKeyAuth());
	}

	Map<String, String> build(String token, boolean isApiKey) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Authorization", "Bearer " + token);
		if (isApiKey) {
			headers.put("Content-Type", "application/json");
			headers.put("Accept", "*/*");
		} else {
			headers.put("Content-Type", "application/x-amz-json-1.0");
		}
		headers.put(
				"x-amz-target",
				"AmazonCodeWhispererStreamingService.GenerateAssistantResponse"
		);
		headers.put(
				"User-Agent",
				"aws-sdk-js/1.0.34 ua/2.1 os/darwin lang/js md/nodejs#22.0.0 api/codewhispererstreaming#1.0.34 m/E KiroIDE-0.7.45-" +
				FINGERPRINT
		);
		headers.put(
				"x-amz-user-agent",
				"aws-sdk-js/1.0.34 KiroIDE-0.7.45-" + FINGERPRINT
		);
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
