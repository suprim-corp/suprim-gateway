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
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Authorization", "Bearer " + token);
		headers.put("Content-Type", "application/x-amz-json-1.0");
		headers.put(
				"x-amz-target",
				"AmazonCodeWhispererStreamingService.GenerateAssistantResponse"
		);
		headers.put(
				"User-Agent",
				"aws-sdk-js/1.0.27 os/darwin arch/arm64 lang/js md/nodejs#22.0.0 KiroIDE-0.7.45-" +
				FINGERPRINT
		);
		headers.put(
				"x-amz-user-agent",
				"aws-sdk-js/1.0.27 KiroIDE-0.7.45-" + FINGERPRINT
		);
		headers.put("x-amzn-codewhisperer-optout", "true");
		headers.put("x-amzn-kiro-agent-mode", "vibe");
		headers.put("amz-sdk-invocation-id", UUID.randomUUID().toString());
		headers.put("amz-sdk-request", "attempt=1; max=3");
		if (authManager.isApiKeyAuth()) {
			headers.put("tokentype", "API_KEY");
		}
		return headers;
	}
}
