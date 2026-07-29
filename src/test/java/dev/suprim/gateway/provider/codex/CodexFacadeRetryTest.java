package dev.suprim.gateway.provider.codex;

import dev.suprim.gateway.provider.AccountCooldown;
import dev.suprim.gateway.provider.AccountRotator;
import dev.suprim.gateway.provider.CredentialStore;
import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.proxy.Format;
import dev.suprim.gateway.proxy.InternalRequest;
import dev.suprim.gateway.proxy.ProxyChain;
import dev.suprim.gateway.proxy.SseHeartbeat;
import dev.suprim.gateway.proxy.SseHeartbeatScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CodexFacadeRetryTest {

	private CodexFacade facade;
	private CodexAuthManager authManager;
	private AccountRotator rotator;
	private CredentialStore store;

	@BeforeEach
	void setUp() {
		store = mock(CredentialStore.class);
		authManager = mock(CodexAuthManager.class);
		rotator = mock(AccountRotator.class);
		ProxyChain proxyChain = mock(ProxyChain.class);
		facade = new CodexFacade(
				authManager, rotator, store, proxyChain, new AccountCooldown(),
				new CodexResponseRelay(
						new SseHeartbeat(mock(SseHeartbeatScheduler.class))
				)
		);
	}

	@Test
	void handle_coolsRateLimitedAccountAndUsesHealthyAccountLater() throws Exception {
		StoredAccount limited = account("limited", "token-limited");
		StoredAccount healthy = account("healthy", "token-healthy");
		when(store.findAllByProvider("CODEX")).thenReturn(List.of(limited, healthy));
		when(rotator.next("CODEX")).thenReturn(limited, healthy, limited, healthy);
		when(authManager.getAccessToken(limited)).thenReturn("token-limited");
		when(authManager.getAccessToken(healthy)).thenReturn("token-healthy");

		try (MockedStatic<CodexHttpClient> mocked = mockStatic(CodexHttpClient.class)) {
			mocked.when(() -> CodexHttpClient.call(anyString(), eq("token-limited"), any(ProxyChain.class)))
			      .thenAnswer(ignored -> response(429, "rate limited"));
			mocked.when(() -> CodexHttpClient.call(anyString(), eq("token-healthy"), any(ProxyChain.class)))
			      .thenAnswer(ignored -> response(200, "data: {\"type\":\"response.completed\"}\n\n"));

			MockHttpServletResponse firstResponse = new MockHttpServletResponse();
			facade.handle(request(), "gpt-5", false, 10, "key", "127.0.0.1", Format.RESPONSES, firstResponse);
			assertEquals(200, firstResponse.getStatus());

			MockHttpServletResponse secondResponse = new MockHttpServletResponse();
			facade.handle(request(), "gpt-5", false, 10, "key", "127.0.0.1", Format.RESPONSES, secondResponse);
			assertEquals(200, secondResponse.getStatus());
			mocked.verify(() -> CodexHttpClient.call(anyString(), eq("token-limited"), any(ProxyChain.class)), times(1));
			mocked.verify(() -> CodexHttpClient.call(anyString(), eq("token-healthy"), any(ProxyChain.class)), times(2));
		}
	}

	@Test
	void handle_triesAnotherAccountAfter401() throws Exception {
		StoredAccount unauthorized = account("unauthorized", "token-unauthorized");
		StoredAccount healthy = account("healthy", "token-healthy");
		when(store.findAllByProvider("CODEX")).thenReturn(List.of(unauthorized, healthy));
		when(rotator.next("CODEX")).thenReturn(unauthorized, healthy);
		when(authManager.getAccessToken(unauthorized)).thenReturn("token-unauthorized");
		when(authManager.getAccessToken(healthy)).thenReturn("token-healthy");

		try (MockedStatic<CodexHttpClient> mocked = mockStatic(CodexHttpClient.class)) {
			mocked.when(() -> CodexHttpClient.call(anyString(), eq("token-unauthorized"), any(ProxyChain.class)))
			      .thenAnswer(ignored -> response(401, "token invalidated"));
			mocked.when(() -> CodexHttpClient.call(anyString(), eq("token-healthy"), any(ProxyChain.class)))
			      .thenAnswer(ignored -> response(200, "data: {\"type\":\"response.completed\"}\n\n"));

			MockHttpServletResponse httpResponse = new MockHttpServletResponse();
			facade.handle(request(), "gpt-5", false, 10, "key", "127.0.0.1", Format.RESPONSES, httpResponse);

			assertEquals(200, httpResponse.getStatus());
			mocked.verify(() -> CodexHttpClient.call(anyString(), eq("token-unauthorized"), any(ProxyChain.class)), times(1));
			mocked.verify(() -> CodexHttpClient.call(anyString(), eq("token-healthy"), any(ProxyChain.class)), times(1));
		}
	}

	@Test
	void handle_returnsExhaustedAfterEveryAccountReturns401() throws Exception {
		StoredAccount first = account("first", "token-first");
		StoredAccount second = account("second", "token-second");
		when(store.findAllByProvider("CODEX")).thenReturn(List.of(first, second));
		when(rotator.next("CODEX")).thenReturn(first, second);
		when(authManager.getAccessToken(first)).thenReturn("token-first");
		when(authManager.getAccessToken(second)).thenReturn("token-second");

		try (MockedStatic<CodexHttpClient> mocked = mockStatic(CodexHttpClient.class)) {
			mocked.when(() -> CodexHttpClient.call(anyString(), anyString(), any(ProxyChain.class)))
			      .thenAnswer(ignored -> response(401, "token invalidated"));

			MockHttpServletResponse httpResponse = new MockHttpServletResponse();
			facade.handle(request(), "gpt-5", false, 10, "key", "127.0.0.1", Format.RESPONSES, httpResponse);

			assertEquals(429, httpResponse.getStatus());
			assertTrue(httpResponse.getContentAsString().contains("rate_limit_exhausted"));
			mocked.verify(() -> CodexHttpClient.call(anyString(), anyString(), any(ProxyChain.class)), times(2));
		}
	}

	@Test
	void handle_doesNotCoolAccountAfterNonRetryableResponse() throws Exception {
		StoredAccount account = account("invalid", "token");
		when(store.findAllByProvider("CODEX")).thenReturn(List.of(account));
		when(rotator.next("CODEX")).thenReturn(account);
		when(authManager.getAccessToken(account)).thenReturn("token");

		try (MockedStatic<CodexHttpClient> mocked = mockStatic(CodexHttpClient.class)) {
			mocked.when(() -> CodexHttpClient.call(anyString(), eq("token"), any(ProxyChain.class)))
			      .thenAnswer(ignored -> response(400, "invalid request"));

			MockHttpServletResponse firstResponse = new MockHttpServletResponse();
			facade.handle(request(), "gpt-5", false, 10, "key", "127.0.0.1", Format.RESPONSES, firstResponse);
			MockHttpServletResponse secondResponse = new MockHttpServletResponse();
			facade.handle(request(), "gpt-5", false, 10, "key", "127.0.0.1", Format.RESPONSES, secondResponse);

			assertEquals(400, firstResponse.getStatus());
			assertEquals(400, secondResponse.getStatus());
			mocked.verify(() -> CodexHttpClient.call(anyString(), eq("token"), any(ProxyChain.class)), times(2));
		}
	}

	@Test
	void handle_returnsAnthropicErrorForFailedSseBeforeOutput() throws Exception {
		StoredAccount account = account("failed", "token");
		when(store.findAllByProvider("CODEX")).thenReturn(List.of(account));
		when(rotator.next("CODEX")).thenReturn(account);
		when(authManager.getAccessToken(account)).thenReturn("token");

		String failedStream = "data: {\"type\":\"error\",\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"input too large\"}}\n\n"
				+ "data: {\"type\":\"response.failed\"}\n\n";
		try (MockedStatic<CodexHttpClient> mocked = mockStatic(CodexHttpClient.class)) {
			mocked.when(() -> CodexHttpClient.call(anyString(), eq("token"), any(ProxyChain.class)))
			      .thenAnswer(ignored -> response(200, failedStream));

			MockHttpServletResponse httpResponse = new MockHttpServletResponse();
			facade.handle(request(), "gpt-5", true, 10, "key", "127.0.0.1", Format.ANTHROPIC, httpResponse);

			assertEquals(400, httpResponse.getStatus());
			assertTrue(httpResponse.getContentAsString().contains("Your input exceeds the context window"));
			assertTrue(httpResponse.getContentAsString().contains("invalid_request_error"));
		}
	}

	@Test
	void handle_triesAnotherAccountWhenSseReportsServerOverload() throws Exception {
		StoredAccount overloaded = account("overloaded", "token-overloaded");
		StoredAccount healthy = account("healthy", "token-healthy");
		when(store.findAllByProvider("CODEX")).thenReturn(List.of(overloaded, healthy));
		when(rotator.next("CODEX")).thenReturn(overloaded, healthy);
		when(authManager.getAccessToken(overloaded)).thenReturn("token-overloaded");
		when(authManager.getAccessToken(healthy)).thenReturn("token-healthy");

		String overloadedStream = "data: {\"type\":\"response.created\"}\n\n"
				+ "data: {\"type\":\"error\",\"error\":{\"code\":\"server_is_overloaded\",\"message\":\"try again later\"}}\n\n";
		String healthyStream = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n";
		try (MockedStatic<CodexHttpClient> mocked = mockStatic(CodexHttpClient.class)) {
			mocked.when(() -> CodexHttpClient.call(anyString(), eq("token-overloaded"), any(ProxyChain.class)))
			      .thenAnswer(ignored -> response(200, overloadedStream));
			mocked.when(() -> CodexHttpClient.call(anyString(), eq("token-healthy"), any(ProxyChain.class)))
			      .thenAnswer(ignored -> response(200, healthyStream));

			MockHttpServletResponse httpResponse = new MockHttpServletResponse();
			facade.handle(request(), "gpt-5", true, 10, "key", "127.0.0.1", Format.ANTHROPIC, httpResponse);

			assertEquals(200, httpResponse.getStatus());
			assertTrue(httpResponse.getContentAsString().contains("ok"));
			mocked.verify(() -> CodexHttpClient.call(anyString(), eq("token-overloaded"), any(ProxyChain.class)), times(1));
			mocked.verify(() -> CodexHttpClient.call(anyString(), eq("token-healthy"), any(ProxyChain.class)), times(1));
		}
	}

	@Test
	void handle_omitsOutputTokenLimitsRejectedByCodexBackend() throws Exception {
		StoredAccount account = account("healthy", "token");
		when(store.findAllByProvider("CODEX")).thenReturn(List.of(account));
		when(rotator.next("CODEX")).thenReturn(account);
		when(authManager.getAccessToken(account)).thenReturn("token");
		AtomicReference<String> payload = new AtomicReference<>();

		try (MockedStatic<CodexHttpClient> mocked = mockStatic(CodexHttpClient.class)) {
			mocked.when(() -> CodexHttpClient.call(anyString(), eq("token"), any(ProxyChain.class)))
			      .thenAnswer(invocation -> {
				      payload.set(invocation.getArgument(0));
				      return response(200, "data: {\"type\":\"response.completed\"}\n\n");
			      });

			MockHttpServletResponse httpResponse = new MockHttpServletResponse();
			facade.handle(
					requestWithMaxTokens(), "gpt-5", false, 10, "key", "127.0.0.1",
					Format.RESPONSES, httpResponse
			);
			assertEquals(200, httpResponse.getStatus());
		}

		JsonNode sentPayload = new JsonMapper().readTree(payload.get());
		assertEquals("gpt-5", sentPayload.path("model").asString());
		assertTrue(sentPayload.path("stream").asBoolean());
		assertFalse(sentPayload.has("max_output_tokens"));
		assertFalse(sentPayload.has("max_tokens"));
		assertFalse(sentPayload.has("max_completion_tokens"));
	}

	@Test
	void handle_mapsTemperatureOnlyToEffortLevelsTheCatalogDeclares() throws Exception {
		// The upstream catalog lists low/medium/high/xhigh/max/ultra for every
		// gpt-5.x slug. Effort is serialized by name, so an undeclared level is
		// rejected rather than ignored.
		assertEquals("high", effortForTemperature(0.0));
		assertEquals("high", effortForTemperature(0.3));
		assertEquals("medium", effortForTemperature(0.5));
		assertEquals("medium", effortForTemperature(0.7));
		assertEquals("low", effortForTemperature(1.0));
		assertEquals("low", effortForTemperature(2.0));
	}

	@Test
	void handle_keepsTemperatureOutOfThePayload() throws Exception {
		JsonNode payload = payloadForTemperature(0.5);

		assertFalse(payload.has("temperature"));
	}

	private String effortForTemperature(double temperature) throws Exception {
		return payloadForTemperature(temperature).path("reasoning")
		                                        .path("effort")
		                                        .asString();
	}

	private JsonNode payloadForTemperature(double temperature) throws Exception {
		StoredAccount account = account("healthy", "token");
		when(store.findAllByProvider("CODEX")).thenReturn(List.of(account));
		when(rotator.next("CODEX")).thenReturn(account);
		when(authManager.getAccessToken(account)).thenReturn("token");
		AtomicReference<String> payload = new AtomicReference<>();

		try (MockedStatic<CodexHttpClient> mocked = mockStatic(CodexHttpClient.class)) {
			mocked.when(() -> CodexHttpClient.call(anyString(), eq("token"), any(ProxyChain.class)))
			      .thenAnswer(invocation -> {
				      payload.set(invocation.getArgument(0));
				      return response(200, "data: {\"type\":\"response.completed\"}\n\n");
			      });

			facade.handle(
					requestWithTemperature(temperature), "gpt-5", false, 10, "key",
					"127.0.0.1", Format.RESPONSES, new MockHttpServletResponse()
			);
		}
		return new JsonMapper().readTree(payload.get());
	}

	private InternalRequest requestWithTemperature(double temperature) {
		return InternalRequest.builder()
		                      .model("gpt-5")
		                      .messages(List.of())
		                      .temperature(temperature)
		                      .stream(false)
		                      .build();
	}

	@Test
	void handle_coolsAccountAfter503() throws Exception {
		StoredAccount account = account("unavailable", "token");
		when(store.findAllByProvider("CODEX")).thenReturn(List.of(account));
		when(rotator.next("CODEX")).thenReturn(account);
		when(authManager.getAccessToken(account)).thenReturn("token");

		try (MockedStatic<CodexHttpClient> mocked = mockStatic(CodexHttpClient.class)) {
			mocked.when(() -> CodexHttpClient.call(anyString(), eq("token"), any(ProxyChain.class)))
			      .thenAnswer(ignored -> response(503, "unavailable"));

			MockHttpServletResponse firstResponse = new MockHttpServletResponse();
			facade.handle(request(), "gpt-5", false, 10, "key", "127.0.0.1", Format.RESPONSES, firstResponse);
			assertEquals(429, firstResponse.getStatus());

			MockHttpServletResponse secondResponse = new MockHttpServletResponse();
			facade.handle(request(), "gpt-5", false, 10, "key", "127.0.0.1", Format.RESPONSES, secondResponse);
			assertEquals(429, secondResponse.getStatus());
			assertTrue(secondResponse.getContentAsString().contains("rate_limit_exhausted"));
			mocked.verify(() -> CodexHttpClient.call(anyString(), eq("token"), any(ProxyChain.class)), times(1));
		}
	}

	private StoredAccount account(String name, String token) {
		return StoredAccount.builder()
		                    .name(name)
		                    .provider("CODEX")
		                    .accessToken(token)
		                    .refreshToken("refresh")
		                    .expiresAt(Instant.now().plusSeconds(3600))
		                    .build();
	}

	private InternalRequest request() {
		return InternalRequest.builder()
		                      .model("gpt-5")
		                      .messages(List.of())
		                      .stream(false)
		                      .build();
	}

	private InternalRequest requestWithMaxTokens() {
		return InternalRequest.builder()
		                      .model("gpt-5")
		                      .messages(List.of())
		                      .maxTokens(256)
		                      .stream(false)
		                      .build();
	}

	private CodexHttpClient.CodexResponse response(int status, String body) {
		return CodexHttpClient.CodexResponse.builder()
		                                        .status(status)
		                                        .body(new ByteArrayInputStream(body.getBytes()))
		                                        .build();
	}
}
