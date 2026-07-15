package dev.suprim.gateway.provider.deepseek;

import dev.suprim.gateway.provider.StoredAccount;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class DeepSeekAuthManagerTest {

	private MockWebServer server;
	private DeepSeekAuthManager authManager;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		DeepSeekHttpClient httpClient = new DeepSeekHttpClient(null);
		String url = server.url("").toString();
		String baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
		authManager = new DeepSeekAuthManager(httpClient, baseUrl);
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	@Test
	void getToken_loginsAndReturnsToken() throws Exception {
		server.enqueue(new MockResponse()
				.setResponseCode(200)
				.setBody(loginResponse("tok_abc123")));

		StoredAccount account = account("user@test.com", "pass123");
		String token = authManager.getToken(account);

		assertEquals("tok_abc123", token);
		RecordedRequest request = server.takeRequest();
		assertEquals("POST", request.getMethod());
		assertTrue(request.getPath().contains("/api/v0/users/login"));
		String body = request.getBody().readUtf8();
		assertTrue(body.contains("user@test.com"));
		assertTrue(body.contains("pass123"));
	}

	@Test
	void getToken_returnsCachedTokenOnSecondCall() throws Exception {
		server.enqueue(new MockResponse()
				.setResponseCode(200)
				.setBody(loginResponse("tok_cached")));

		StoredAccount account = account("user@test.com", "pass123");
		String first = authManager.getToken(account);
		String second = authManager.getToken(account);

		assertEquals("tok_cached", first);
		assertEquals("tok_cached", second);
		assertEquals(1, server.getRequestCount());
	}

	@Test
	void invalidateToken_forcesReloginOnNextCall() throws Exception {
		server.enqueue(new MockResponse()
				.setResponseCode(200)
				.setBody(loginResponse("tok_first")));
		server.enqueue(new MockResponse()
				.setResponseCode(200)
				.setBody(loginResponse("tok_second")));

		StoredAccount account = account("user@test.com", "pass123");
		String first = authManager.getToken(account);
		authManager.invalidateToken(account);
		String second = authManager.getToken(account);

		assertEquals("tok_first", first);
		assertEquals("tok_second", second);
		assertEquals(2, server.getRequestCount());
	}

	@Test
	void getToken_loginFails_throwsIOException() {
		server.enqueue(new MockResponse()
				.setResponseCode(401)
				.setBody("{\"code\":\"unauthorized\"}"));

		StoredAccount account = account("user@test.com", "wrong");
		assertThrows(IOException.class, () -> authManager.getToken(account));
	}

	@Test
	void getToken_malformedResponse_throwsIOException() {
		server.enqueue(new MockResponse()
				.setResponseCode(200)
				.setBody("{\"data\":{}}"));

		StoredAccount account = account("user@test.com", "pass123");
		assertThrows(IOException.class, () -> authManager.getToken(account));
	}

	@Test
	void getToken_differentAccounts_separateTokens() throws Exception {
		server.enqueue(new MockResponse()
				.setResponseCode(200)
				.setBody(loginResponse("tok_a")));
		server.enqueue(new MockResponse()
				.setResponseCode(200)
				.setBody(loginResponse("tok_b")));

		StoredAccount accountA = account("a@test.com", "passA");
		StoredAccount accountB = account("b@test.com", "passB");

		String tokenA = authManager.getToken(accountA);
		String tokenB = authManager.getToken(accountB);

		assertEquals("tok_a", tokenA);
		assertEquals("tok_b", tokenB);
		assertEquals(2, server.getRequestCount());
	}

	@Test
	void getToken_nullResponseBody_throwsIOException() throws Exception {
		DeepSeekHttpClient mockClient = mock(DeepSeekHttpClient.class);
		DeepSeekAuthManager mgr = new DeepSeekAuthManager(mockClient, "http://localhost");

		Response response = mock(Response.class);
		when(response.isSuccessful()).thenReturn(true);
		when(response.body()).thenReturn(null);
		when(mockClient.execute(any())).thenReturn(response);
		when(mockClient.buildPostRequest(any(), any(), any(), any()))
				.thenReturn(new okhttp3.Request.Builder().url("http://localhost/api/v0/users/login").build());

		StoredAccount account = account("null@test.com", "pass");
		assertThrows(IOException.class, () -> mgr.getToken(account));
		verify(response).close();
	}

	@Test
	void getToken_emptyTokenInResponse_throwsIOException() {
		server.enqueue(new MockResponse()
				.setResponseCode(200)
				.setBody("{\"data\":{\"biz_data\":{\"user\":{\"token\":\"\"}}}}"));

		StoredAccount account = account("empty@test.com", "pass");
		assertThrows(IOException.class, () -> authManager.getToken(account));
	}

	private StoredAccount account(String email, String password) {
		return StoredAccount.builder()
				.name(email)
				.accessToken(password)
				.provider("DEEPSEEK")
				.build();
	}

	@Test
	void constructor_trailingSlashStripped() throws Exception {
		server.enqueue(new MockResponse()
				.setResponseCode(200)
				.setBody(loginResponse("tok_slash")));

		DeepSeekHttpClient client = new DeepSeekHttpClient(null);
		String urlWithSlash = server.url("/").toString();
		DeepSeekAuthManager mgr = new DeepSeekAuthManager(client, urlWithSlash);

		StoredAccount account = account("slash@test.com", "pass");
		String token = mgr.getToken(account);
		assertEquals("tok_slash", token);
		RecordedRequest req = server.takeRequest();
		assertEquals("/api/v0/users/login", req.getPath());
	}

	private String loginResponse(String token) {
		return """
				{"data":{"biz_data":{"user":{"token":"%s"}}}}
				""".formatted(token);
	}
}
