package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.proxy.ProxyChain;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CodexHttpClientTest {

	private MockWebServer server;
	private ProxyChain proxyChain;

	@BeforeEach
	void setUp() throws Exception {
		server = new MockWebServer();
		server.start();
		// A chain with no proxy returns null, which routes through the direct client.
		proxyChain = mock(ProxyChain.class);
	}

	@AfterEach
	void tearDown() throws Exception {
		server.shutdown();
	}

	@Test
	void fetchUsage_prefersTheAccountScopedPath() throws Exception {
		server.enqueue(usageResponse());

		Map<String, Object> usage = CodexHttpClient.fetchUsage(
				"sk-token", proxyChain, base()
		);

		RecordedRequest request = server.takeRequest();
		assertEquals("/api/codex/usage", request.getPath());
		assertEquals("plus", usage.get("plan"));
	}

	@Test
	void fetchUsage_fallsBackToInternalPathOnNotFound() throws Exception {
		server.enqueue(new MockResponse().setResponseCode(404));
		server.enqueue(usageResponse());

		Map<String, Object> usage = CodexHttpClient.fetchUsage(
				"sk-token", proxyChain, base()
		);

		assertEquals("/api/codex/usage", server.takeRequest().getPath());
		assertEquals("/wham/usage", server.takeRequest().getPath());
		assertEquals("plus", usage.get("plan"));
	}

	@Test
	void fetchUsage_doesNotFallBackOnOtherFailures() throws Exception {
		server.enqueue(new MockResponse().setResponseCode(401));

		Map<String, Object> usage = CodexHttpClient.fetchUsage(
				"sk-token", proxyChain, base()
		);

		assertEquals(1, server.getRequestCount());
		assertEquals("Usage unavailable (401)", usage.get("message"));
	}

	@Test
	void fetchUsage_sendsClientIdentifyingHeaders() throws Exception {
		server.enqueue(usageResponse());

		CodexHttpClient.fetchUsage("sk-token", proxyChain, base());

		RecordedRequest request = server.takeRequest();
		assertEquals("Bearer sk-token", request.getHeader("Authorization"));
		assertEquals("codex_cli_rs", request.getHeader("originator"));
		assertTrue(
				request.getHeader("User-Agent").startsWith("codex_cli_rs/"),
				"unexpected User-Agent: " + request.getHeader("User-Agent")
		);
	}

	@Test
	void parseUsage_normalizesBothWindowNamings() {
		Map<String, Object> usage = CodexHttpClient.parseUsage("""
				{"plan_type":"pro","rate_limit":{"limit_reached":true,
				 "primary":{"used_percent":40,"resets_at":"2026-07-28T00:00:00Z"},
				 "secondary_window":{"used_percent":12,"reset_at":"2026-08-01T00:00:00Z"}},
				 "rate_limit_reset_credits":{"available_count":3}}
				""");

		assertEquals("pro", usage.get("plan"));
		assertEquals(true, usage.get("limitReached"));
		assertEquals(3, usage.get("resetCredits"));

		Map<?, ?> session = (Map<?, ?>) usage.get("session");
		assertEquals(40, session.get("usedPercent"));
		assertEquals("2026-07-28T00:00:00Z", session.get("resetAt"));

		Map<?, ?> weekly = (Map<?, ?>) usage.get("weekly");
		assertEquals(12, weekly.get("usedPercent"));
		assertEquals("2026-08-01T00:00:00Z", weekly.get("resetAt"));
	}

	private String base() {
		String url = server.url("/").toString();
		return url.substring(0, url.length() - 1);
	}

	private MockResponse usageResponse() {
		return new MockResponse()
				.setResponseCode(200)
				.setHeader("Content-Type", "application/json")
				.setBody("{\"plan_type\":\"plus\"}");
	}
}
