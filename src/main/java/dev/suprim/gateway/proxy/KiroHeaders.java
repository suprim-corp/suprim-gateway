package dev.suprim.gateway.proxy;

import dev.suprim.gateway.auth.KiroAuthManager;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
class KiroHeaders {

	private static final String FINGERPRINT = UUID.randomUUID()
	                                              .toString()
	                                              .substring(0, 8);

	Map<String, String> build(KiroAuthManager auth, String token) {
		return Map.ofEntries(
				Map.entry("Authorization", "Bearer " + token),
				Map.entry("Content-Type", "application/x-amz-json-1.0"),
				Map.entry(
						"x-amz-target",
						"AmazonCodeWhispererStreamingService.GenerateAssistantResponse"
				),
				Map.entry(
						"User-Agent",
						"aws-sdk-js/1.0.27 os/darwin arch/arm64 lang/js md/nodejs#22.0.0 KiroIDE-0.7.45-" +
						FINGERPRINT
				),
				Map.entry(
						"x-amz-user-agent",
						"aws-sdk-js/1.0.27 KiroIDE-0.7.45-" + FINGERPRINT
				),
				Map.entry("x-amzn-codewhisperer-optout", "true"),
				Map.entry("x-amzn-kiro-agent-mode", "vibe"),
				Map.entry(
						"amz-sdk-invocation-id",
						UUID.randomUUID().toString()
				),
				Map.entry("amz-sdk-request", "attempt=1; max=3")
		);
	}
}
