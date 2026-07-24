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

	@Test
	void buildProjectBody_includesProjectWhenPresent() {
		assertEquals("{\"project\":\"projects/test\"}", AntigravityHttpClient.buildProjectBody("projects/test"));
		assertEquals("{}", AntigravityHttpClient.buildProjectBody(null));
	}

	@Test
	void parseQuotaSummary_returnsNormalizedValues() {
		Map<String, Object> quota = AntigravityHttpClient.parseQuotaSummary("""
				{"quotaInfo":{"remainingFraction":0.42,"resetTime":"2026-07-25T00:00:00Z"}}
				""");

		assertEquals(42, quota.get("quota"));
		assertEquals("2026-07-25T00:00:00Z", quota.get("resetTime"));
	}

	@Test
	void parseQuotaSummary_rejectsInvalidOrMissingFractions() {
		assertTrue(AntigravityHttpClient.parseQuotaSummary("{}").isEmpty());
		assertTrue(AntigravityHttpClient.parseQuotaSummary("{\"remainingFraction\":1.2}").isEmpty());
		assertTrue(AntigravityHttpClient.parseQuotaSummary("not json").isEmpty());
	}
}
