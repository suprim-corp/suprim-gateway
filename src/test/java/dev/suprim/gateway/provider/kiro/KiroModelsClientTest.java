package dev.suprim.gateway.provider.kiro;

import dev.suprim.gateway.provider.StoredAccount;
import dev.suprim.gateway.proxy.ProxyChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** URL, headers and 403 retry of the {@code ListAvailableModels} call. */
class KiroModelsClientTest {

	private static final String REGION = "us-east-1";
	private static final String BODY = """
			{"models":[{"modelId":"claude-sonnet-5","supportedInputTypes":["TEXT","IMAGE"]}]}
			""";

	private ProxyChain proxyChain;

	@BeforeEach
	void setUp() {
		proxyChain = mock(ProxyChain.class);
	}

	@Test
	void fetch_sendsProfileArnAndSsoHeadersForOauthAccount() throws Exception {
		stub(200, BODY);

		List<Map<String, Object>> models = KiroModelsClient.fetch(
				ssoAccount(),
				REGION,
				Set.of(),
				proxyChain,
				() -> fail("must not refresh on a 200")
		);

		assertEquals(1, models.size());
		HttpRequest sent = captureRequest();
		assertTrue(sent.uri().toString().contains("ListAvailableModels"));
		assertTrue(sent.uri().toString().contains("profileArn=arn%3Aaws"));
		assertEquals(
				"Bearer sso-token",
				sent.headers().firstValue("Authorization").orElseThrow()
		);
		assertTrue(sent.headers().firstValue("tokentype").isEmpty());
	}

	/** An API key is already scoped by the key, so no profile is sent — but tokentype must be. */
	@Test
	void fetch_sendsTokenTypeAndNoProfileArnForApiKey() throws Exception {
		stub(200, BODY);

		KiroModelsClient.fetch(
				apiKeyAccount(),
				REGION,
				Set.of(),
				proxyChain,
				() -> fail("must not refresh on a 200")
		);

		HttpRequest sent = captureRequest();
		assertFalse(sent.uri().toString().contains("profileArn"));
		assertEquals(
				"API_KEY",
				sent.headers().firstValue("tokentype").orElseThrow()
		);
	}

	@Test
	void fetch_retriesWithRefreshedTokenOn403() throws Exception {
		HttpResponse<String> forbidden = response(403, "{\"message\":\"expired\"}");
		HttpResponse<String> ok = response(200, BODY);
		when(proxyChain.send(any(HttpRequest.class))).thenReturn(forbidden, ok);

		List<Map<String, Object>> models = KiroModelsClient.fetch(
				ssoAccount(),
				REGION,
				Set.of(),
				proxyChain,
				() -> "fresh-token"
		);

		assertEquals(1, models.size());
		ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
		verify(proxyChain, times(2)).send(captor.capture());
		assertEquals(
				"Bearer fresh-token",
				captor.getAllValues().getLast()
				      .headers().firstValue("Authorization").orElseThrow()
		);
	}

	/** A failed refresh must surface the original upstream message, not a retry loop. */
	@Test
	void fetch_refreshFails_throwsUpstreamMessage() throws Exception {
		stub(403, "{\"message\":\"registration expired\"}");

		IOException error = assertThrows(
				IOException.class,
				() -> KiroModelsClient.fetch(
						ssoAccount(),
						REGION,
						Set.of(),
						proxyChain,
						() -> null
				)
		);

		assertEquals("registration expired", error.getMessage());
		verify(proxyChain, times(1)).send(any(HttpRequest.class));
	}

	/** An API key's 403 is an answer about the key, so retrying with a new token is pointless. */
	@Test
	void fetch_apiKey403_doesNotRefresh() throws Exception {
		stub(403, "{\"message\":\"invalid key\"}");

		assertThrows(
				IOException.class,
				() -> KiroModelsClient.fetch(
						apiKeyAccount(),
						REGION,
						Set.of(),
						proxyChain,
						() -> fail("must not refresh for an API key")
				)
		);
	}

	/**
	 * A failure has to throw rather than return empty: an empty list is indistinguishable from an
	 * account that legitimately has no models.
	 */
	@Test
	void fetch_nonRetryableFailure_throwsRatherThanReturningEmpty() throws Exception {
		stub(500, "{\"message\":\"internal\"}");

		assertThrows(
				IOException.class,
				() -> KiroModelsClient.fetch(
						ssoAccount(),
						REGION,
						Set.of(),
						proxyChain,
						() -> fail("must not refresh on a 500")
				)
		);
	}

	@Test
	void fetch_appliesDisabledModels() throws Exception {
		stub(200, BODY);

		List<Map<String, Object>> models = KiroModelsClient.fetch(
				ssoAccount(),
				REGION,
				Set.of("claude-sonnet-5"),
				proxyChain,
				() -> fail("must not refresh on a 200")
		);

		assertTrue(models.isEmpty());
	}

	private static StoredAccount ssoAccount() {
		return StoredAccount.builder()
		                    .name("sso")
		                    .provider("KIRO")
		                    .authType("KIRO_DESKTOP")
		                    .accessToken("sso-token")
		                    .profileArn("arn:aws:codewhisperer:us-east-1:123:profile/ABC")
		                    .build();
	}

	private static StoredAccount apiKeyAccount() {
		return StoredAccount.builder()
		                    .name("key")
		                    .provider("KIRO")
		                    .authType("api_key")
		                    .accessToken("the-key")
		                    .build();
	}

	private void stub(int status, String body) throws Exception {
		HttpResponse<String> response = response(status, body);
		when(proxyChain.send(any(HttpRequest.class))).thenReturn(response);
	}

	@SuppressWarnings("unchecked")
	private static HttpResponse<String> response(int status, String body) {
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		lenient().when(response.body()).thenReturn(body);
		return response;
	}

	private HttpRequest captureRequest() throws Exception {
		ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
		verify(proxyChain).send(captor.capture());
		return captor.getValue();
	}
}
