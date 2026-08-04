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
import static org.junit.jupiter.api.Assertions.assertNull;
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
	void fetchUsage_usesChatGptPathForBackendApiBase() throws Exception {
		server.enqueue(usageResponse());

		CodexUsage usage = CodexHttpClient.fetchUsage(
				"sk-token", proxyChain, base()
		);

		RecordedRequest request = server.takeRequest();
		assertEquals("/wham/usage", request.getPath());
		assertEquals(1, server.getRequestCount());
		assertEquals("plus", usage.plan());
	}

	@Test
	void fetchUsage_surfacesBackendFailureWithoutTryingAnotherPath() throws Exception {
		server.enqueue(new MockResponse().setResponseCode(403));

		CodexUsage usage = CodexHttpClient.fetchUsage(
				"sk-token", proxyChain, base()
		);

		assertEquals("/wham/usage", server.takeRequest().getPath());
		assertEquals(1, server.getRequestCount());
		assertEquals("Usage unavailable (403)", usage.message());
		assertEquals(true, usage.unauthorized());
	}

	@Test
	void fetchUsage_flagsARejectedCredential() throws Exception {
		server.enqueue(new MockResponse().setResponseCode(401));

		CodexUsage usage = CodexHttpClient.fetchUsage(
				"sk-token", proxyChain, base()
		);

		assertEquals(true, usage.unauthorized());
	}

	@Test
	void fetchUsage_doesNotFlagAnUpstreamOutageAsRejected() throws Exception {
		server.enqueue(new MockResponse().setResponseCode(503));

		CodexUsage usage = CodexHttpClient.fetchUsage(
				"sk-token", proxyChain, base()
		);

		assertEquals("Usage unavailable (503)", usage.message());
		assertNull(
				usage.unauthorized(),
				"A 5xx fixes itself and must not mark the account unusable"
		);
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
		CodexUsage usage = CodexHttpClient.parseUsage("""
				{"plan_type":"pro","rate_limit":{"limit_reached":true,
				 "primary":{"used_percent":40,"resets_at":"2026-07-28T00:00:00Z"},
				 "secondary_window":{"used_percent":12,"reset_at":"2026-08-01T00:00:00Z"}},
				 "rate_limit_reset_credits":{"available_count":3}}
				""");

		assertEquals("pro", usage.plan());
		assertEquals(true, usage.limitReached());
		assertEquals(3, usage.resetCredits());

		assertEquals(40, usage.session().usedPercent());
		assertEquals("2026-07-28T00:00:00Z", usage.session().resetAt());

		assertEquals(12, usage.weekly().usedPercent());
		assertEquals("2026-08-01T00:00:00Z", usage.weekly().resetAt());
	}

	@Test
	void parseUsage_omitsWindowsTheUpstreamDidNotReport() {
		CodexUsage usage = CodexHttpClient.parseUsage("{\"plan_type\":\"free\"}");

		assertNull(usage.session(), "No rate_limit means no window to report");
		assertNull(usage.weekly());
		assertNull(
				usage.limitReached(),
				"No rate_limit means no claim either way about reaching one"
		);
	}

	@Test
	void parseUsage_reportsALimitThatWasNotReached() {
		CodexUsage usage = CodexHttpClient.parseUsage("""
				{"plan_type":"pro","rate_limit":{"primary":{"used_percent":10}}}
				""");

		assertEquals(false, usage.limitReached(), "A described limit reports its state");
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
