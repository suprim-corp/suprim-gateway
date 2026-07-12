package dev.suprim.gateway.provider.antigravity;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AntigravityHttpClientTest {

	@Test
	void buildUrl_correctFormat() {
		String url = AntigravityHttpClient.buildUrl();

		assertEquals(
				"https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse",
				url
		);
	}

	@Test
	void buildHeaders_containsRequiredHeaders() {
		Map<String, String> headers = AntigravityHttpClient.buildHeaders("ya29.test-token");

		assertEquals("Bearer ya29.test-token", headers.get("Authorization"));
		assertEquals("application/json", headers.get("Content-Type"));
		assertEquals("antigravity/ide/2.1.1 darwin/arm64", headers.get("User-Agent"));
	}
}
