package dev.suprim.gateway.xai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class XaiHttpClientTest {

	@Test
	void buildHeaders_containsAuthorizationAndContentType() {
		var headers = XaiHttpClient.buildHeaders("test-token");
		assertEquals("Bearer test-token", headers.get("Authorization"));
		assertEquals("application/json", headers.get("Content-Type"));
	}

	@Test
	void buildHeaders_noUserAgent() {
		var headers = XaiHttpClient.buildHeaders("token");
		assertFalse(headers.containsKey("User-Agent"));
	}
}
