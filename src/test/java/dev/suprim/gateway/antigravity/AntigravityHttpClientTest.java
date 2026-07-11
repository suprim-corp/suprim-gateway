package dev.suprim.gateway.antigravity;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AntigravityHttpClientTest {

	@Test
	void buildUrl_correctFormat() {
		String url = AntigravityHttpClient.buildUrl("gemini-2.5-flash");

		assertEquals(
				"https://cloudcode-pa.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
				url
		);
	}

	@Test
	void buildUrl_differentModel() {
		String url = AntigravityHttpClient.buildUrl("gemini-2.5-pro");

		assertEquals(
				"https://cloudcode-pa.googleapis.com/v1beta/models/gemini-2.5-pro:streamGenerateContent?alt=sse",
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
